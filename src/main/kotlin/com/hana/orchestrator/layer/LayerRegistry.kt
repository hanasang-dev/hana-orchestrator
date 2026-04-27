package com.hana.orchestrator.layer

import java.io.File
import java.net.URLClassLoader

/**
 * 동적으로 핫로드된 레이어의 영속 레지스트리
 *
 * layer-registry.json 파일에 핫로드된 레이어 이름 목록을 저장.
 * 서버 재시작 시 LayerManager가 이 파일을 읽어 자동 복원.
 *
 * 파일 형식: ["Farewell", "Calculator", "Weather"]
 */
object LayerRegistry {

    private fun registryFile(projectRoot: File) = File(projectRoot, "layer-registry.json")

    /**
     * 핫로드된 레이어를 레지스트리에 등록 (중복 방지)
     *
     * @param normalizedName "Layer" 접미사 없는 이름 (예: "Farewell")
     * @param projectRoot 프로젝트 루트 디렉토리
     */
    fun register(normalizedName: String, projectRoot: File) {
        val file = registryFile(projectRoot)
        val existing = readNames(file).toMutableList()
        if (normalizedName !in existing) {
            existing.add(normalizedName)
            file.writeText("[${existing.joinToString(", ") { "\"$it\"" }}]")
        }
    }

    /**
     * 레지스트리에 저장된 모든 레이어 로드
     *
     * .class 파일이 없으면 (빌드 clean 등) 해당 레이어는 조용히 스킵.
     *
     * @param projectRoot 프로젝트 루트 디렉토리
     * @param parentClassLoader 부모 클래스로더 (공유 인터페이스 위임용)
     * @return 로드된 레이어 인스턴스 목록
     */
    fun loadAll(projectRoot: File, parentClassLoader: ClassLoader): List<CommonLayerInterface> {
        val file = registryFile(projectRoot)
        if (!file.exists()) return emptyList()
        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        if (!buildDir.exists()) return emptyList()

        val classLoader = URLClassLoader(arrayOf(buildDir.toURI().toURL()), parentClassLoader)
        return readNames(file).mapNotNull { name ->
            loadLayer("com.hana.orchestrator.layer.${name}Layer", classLoader)
        }
    }

    private fun loadLayer(className: String, classLoader: URLClassLoader): CommonLayerInterface? = try {
        classLoader.loadClass(className).getDeclaredConstructor().newInstance() as CommonLayerInterface
    } catch (_: Exception) {
        null
    }

    private fun readNames(file: File): List<String> = try {
        file.readText()
            .trim().removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}
