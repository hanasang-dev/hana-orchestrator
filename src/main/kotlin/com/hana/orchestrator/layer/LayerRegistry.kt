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
     * 레지스트리에 저장된 모든 레이어 로드.
     *
     * .class 파일이 없으면 (build clean 등) gradlew compileKotlin을 자동 실행 후 재시도.
     * .kt 소스가 존재하는 한 재시작 후에도 반드시 복원됨.
     *
     * @param projectRoot 프로젝트 루트 디렉토리
     * @param parentClassLoader 부모 클래스로더 (공유 인터페이스 위임용)
     * @return 로드된 레이어 인스턴스 목록
     */
    fun loadAll(projectRoot: File, parentClassLoader: ClassLoader): List<CommonLayerInterface> {
        val names = readNames(registryFile(projectRoot))
        if (names.isEmpty()) return emptyList()

        recompileIfClassesMissing(projectRoot, names)

        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        if (!buildDir.exists()) return emptyList()

        // 레이어마다 별도 child-first 클래스로더 사용:
        // - 공유 클래스로더를 쓰면 부모에 클래스가 캐시되어 reloadLayer 시 구버전이 반환됨
        // - child-first는 buildDir를 먼저 탐색하여 항상 최신 .class를 로드
        return names.mapNotNull { name ->
            val classLoader = childFirstClassLoader(buildDir, name, parentClassLoader)
            loadLayer("com.hana.orchestrator.layer.${name}Layer", classLoader)
        }
    }

    /**
     * 특정 레이어 클래스에 대해 child-first 위임을 사용하는 URLClassLoader 생성.
     * "${normalized}Layer" 접두사 클래스만 buildDir를 먼저 탐색하고 나머지는 부모에 위임.
     */
    private fun childFirstClassLoader(buildDir: File, normalized: String, parent: ClassLoader): URLClassLoader {
        val prefix = "com.hana.orchestrator.layer.${normalized}Layer"
        return object : URLClassLoader(arrayOf(buildDir.toURI().toURL()), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith(prefix)) {
                    synchronized(getClassLoadingLock(name)) {
                        findLoadedClass(name)?.let { return it }
                        try {
                            return findClass(name).also { if (resolve) resolveClass(it) }
                        } catch (_: ClassNotFoundException) { }
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    /**
     * 등록된 레이어의 .class 파일이 하나라도 없으면 gradlew compileKotlin 실행.
     * build clean 후 재시작해도 .kt 소스가 있으면 복원 보장.
     */
    private fun recompileIfClassesMissing(projectRoot: File, names: List<String>) {
        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        val hasMissing = names.any { name ->
            !File(buildDir, "com/hana/orchestrator/layer/${name}Layer.class").exists()
        }
        if (!hasMissing) return

        val gradlew = if (System.getProperty("os.name").lowercase().contains("win"))
            "gradlew.bat" else "./gradlew"
        ProcessBuilder(gradlew, "compileKotlin")
            .directory(projectRoot)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    private fun loadLayer(className: String, classLoader: ClassLoader): CommonLayerInterface? = try {
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
