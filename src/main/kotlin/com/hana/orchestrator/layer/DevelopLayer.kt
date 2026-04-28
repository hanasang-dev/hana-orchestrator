package com.hana.orchestrator.layer

import java.io.File
import java.net.URLClassLoader

/**
 * 새 레이어 생성 및 런타임 등록 레이어
 *
 * ⭐ "새 레이어를 만들어줘", "XXXLayer를 만들어줘" 요청이 오면 반드시 develop.createLayer() 를 사용할 것.
 *
 * 워크플로우:
 * 1. develop.createLayer(name, description, functions) → 파일 저장 완료
 * 2. build.compileKotlin() → 컴파일 확인
 * 3. develop.hotLoad(name) → 런타임 즉시 등록 (서버 재시작 불필요)
 */
@Layer
class DevelopLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private var layerManagerRef: com.hana.orchestrator.orchestrator.core.LayerManager? = null

    /**
     * LayerManager 참조 설정 (LayerManager에서 주입)
     */
    fun setLayerManager(layerManager: com.hana.orchestrator.orchestrator.core.LayerManager) {
        layerManagerRef = layerManager
    }

    private val layerDir: File
        get() = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")

    /**
     * 기존 레이어 파일 읽기 (패턴 참고용)
     *
     * @param layerName 레이어 이름 (예: "Build", "FileSystem", "Git"). "Layer" 접미사 불필요.
     * @return 레이어 소스 코드
     */
    @LayerFunction
    suspend fun readLayerExample(layerName: String): String {
        val normalized = layerName.removeSuffix("Layer")
        val file = File(layerDir, "${normalized}Layer.kt")
        if (!file.exists()) {
            val available = listLayerNames()
            return "ERROR: ${normalized}Layer.kt 를 찾을 수 없습니다.\n사용 가능한 레이어:\n$available"
        }
        return file.readText()
    }

    /**
     * CommonLayerInterface 읽기 (인터페이스 + 데이터 구조 파악용)
     *
     * @return CommonLayerInterface.kt 전체 내용
     */
    @LayerFunction
    suspend fun readLayerInterface(): String {
        val file = File(layerDir, "CommonLayerInterface.kt")
        if (!file.exists()) return "ERROR: CommonLayerInterface.kt 를 찾을 수 없습니다."
        return file.readText()
    }

    /**
     * LayerFactory 읽기 (등록 패턴 파악용)
     *
     * @return LayerFactory.kt 전체 내용
     */
    @LayerFunction
    suspend fun readLayerFactory(): String {
        val file = File(layerDir, "LayerFactory.kt")
        if (!file.exists()) return "ERROR: LayerFactory.kt 를 찾을 수 없습니다."
        return file.readText()
    }

    /**
     * 등록된 레이어 목록 조회
     *
     * @return *Layer.kt 파일 목록 (이름만)
     */
    @LayerFunction
    suspend fun listLayers(): String {
        val names = listLayerNames()
        return if (names.isEmpty()) "레이어 파일 없음" else names
    }

    /**
     * 새 레이어 스캐폴드 코드 생성 (내부용 — LLM에게 노출 안 됨)
     *
     * createLayer() 내부에서만 사용. LLM이 직접 호출할 필요 없음.
     */
    suspend fun scaffoldLayer(name: String, description: String, functions: List<String>): String {
        val normalized = name.removeSuffix("Layer")
        val className = "${normalized}Layer"

        val functionBlocks = functions.joinToString("\n\n") { fn ->
            """    /**
     * TODO: $fn 구현
     *
     * @param input 입력값
     */
    @LayerFunction
    suspend fun $fn(input: String): String {
        return "TODO: 구현 필요"
    }"""
        }

        val whenBranches = functions.joinToString("\n            ") { fn ->
            "\"$fn\" -> $fn(args[\"input\"] as? String ?: \"\")"
        }

        val functionList = functions.joinToString(", ") { "\\\"$it\\\"" }

        return """package com.hana.orchestrator.layer

/**
 * $normalized 레이어
 *
 * 목적: $description
 */
@Layer
class $className : CommonLayerInterface {

$functionBlocks

    override suspend fun describe(): LayerDescription {
        return ${className}_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            $whenBranches
            else -> "Unknown function: ${'$'}function. Available: $functionList"
        }
    }
}
"""
    }

    /**
     * 새 레이어 생성 및 저장 (원스텝) — 파일이 즉시 디스크에 저장됨
     *
     * "레이어 만들어줘" 요청에 사용. 호출 한 번으로 파일 저장 완료.
     * ⚠️ 이 함수 호출 후 writeLayerCode()를 추가로 호출하지 말 것 (이미 저장됨).
     * 이후에는 build.compileKotlin()으로 컴파일 확인.
     *
     * @param name 레이어 이름 (예: "Greeting"). "Layer" 접미사 불필요.
     * @param description 레이어 목적 설명
     * @param functions 함수 이름 목록. 이름 문자열만 포함. 예: ["greet", "get", "post"]
     * @return 저장된 파일 경로
     */
    @LayerFunction
    suspend fun createLayer(name: String, description: String, functions: List<String>): String {
        val code = scaffoldLayer(name, description, functions)
        return writeLayerCode(name, code)
    }

    /**
     * .class 파일에서 레이어 인스턴스 로드 (공통 헬퍼)
     *
     * child-first 클래스로더를 사용하여 부모 클래스로더의 캐시를 우회.
     * 같은 클래스 파일을 재컴파일 후 reloadLayer 시에도 최신 버전이 로드됨.
     */
    private fun loadLayerInstance(normalized: String): CommonLayerInterface {
        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        if (!buildDir.exists()) error("빌드 출력 디렉토리가 없습니다. build.compileKotlin()을 먼저 실행하세요.")
        val classLoader = childFirstClassLoader(buildDir, normalized, this::class.java.classLoader)
        return classLoader.loadClass("com.hana.orchestrator.layer.${normalized}Layer")
            .getDeclaredConstructor().newInstance() as CommonLayerInterface
    }

    /**
     * 특정 레이어 클래스에 대해 child-first 위임을 사용하는 URLClassLoader 생성.
     *
     * 표준 URLClassLoader는 parent-first 방식이라 부모가 캐싱한 구버전을 반환.
     * 이 로더는 "${normalized}Layer" 접두사 클래스에 대해서만 buildDir를 먼저 탐색.
     * CommonLayerInterface 등 공유 인터페이스는 여전히 부모에 위임 → ClassCastException 방지.
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
     * 컴파일 완료된 레이어를 런타임에 즉시 등록합니다 (신규 레이어 전용).
     * 이미 등록된 레이어를 교체하려면 reloadLayer()를 사용하세요.
     *
     * @param name 레이어 이름 (예: "Farewell"). "Layer" 접미사 불필요.
     */
    @LayerFunction
    suspend fun hotLoad(name: String): String {
        val layerManager = layerManagerRef ?: return "ERROR: LayerManager가 주입되지 않았습니다."
        val normalized = name.removeSuffix("Layer")
        val existingNames = layerManager.getAllLayerDescriptions().map { it.name }
        return try {
            val instance = loadLayerInstance(normalized)
            val desc = instance.describe()
            if (desc.name in existingNames) {
                "INFO: '${desc.name}' 레이어는 이미 등록되어 있습니다. 코드를 수정했다면 develop.reloadLayer()를 사용하세요."
            } else {
                layerManager.registerLayer(instance)
                LayerRegistry.register(normalized, projectRoot)
                "SUCCESS: '${desc.name}' 레이어 동적 등록 완료. 즉시 사용 가능. 함수: ${desc.functions.joinToString(", ")}"
            }
        } catch (e: IllegalStateException) { "ERROR: ${e.message}" }
        catch (e: ClassNotFoundException) { "ERROR: 클래스를 찾을 수 없습니다. build.compileKotlin()이 성공했는지 확인하세요." }
        catch (e: NoSuchMethodException) { "ERROR: 기본 생성자(no-arg constructor)가 없습니다." }
        catch (e: ClassCastException) { "ERROR: CommonLayerInterface를 구현하지 않습니다." }
        catch (e: Exception) { "ERROR: 동적 로드 실패: ${e.message}" }
    }

    /**
     * 기존 레이어를 새 컴파일 결과로 교체합니다 (신규 등록에도 사용 가능).
     * 레이어 코드 수정 후 build.compileKotlin() 성공 시 호출하세요.
     *
     * @param name 레이어 이름 (예: "Farewell"). "Layer" 접미사 불필요.
     */
    @LayerFunction
    suspend fun reloadLayer(name: String): String {
        val layerManager = layerManagerRef ?: return "ERROR: LayerManager가 주입되지 않았습니다."
        val normalized = name.removeSuffix("Layer")
        return try {
            val instance = loadLayerInstance(normalized)
            val desc = instance.describe()
            val wasRegistered = layerManager.unregisterLayer(desc.name)
            layerManager.registerLayer(instance)
            LayerRegistry.register(normalized, projectRoot)
            val action = if (wasRegistered) "리로드(교체)" else "신규 등록"
            "SUCCESS: '${desc.name}' 레이어 $action 완료. 함수: ${desc.functions.joinToString(", ")}"
        } catch (e: IllegalStateException) { "ERROR: ${e.message}" }
        catch (e: ClassNotFoundException) { "ERROR: 클래스를 찾을 수 없습니다. build.compileKotlin()이 성공했는지 확인하세요." }
        catch (e: NoSuchMethodException) { "ERROR: 기본 생성자(no-arg constructor)가 없습니다." }
        catch (e: ClassCastException) { "ERROR: CommonLayerInterface를 구현하지 않습니다." }
        catch (e: Exception) { "ERROR: 리로드 실패: ${e.message}" }
    }

    /**
     * 레이어 코드 파일 저장 (내부용 — LLM에게 노출 안 됨)
     *
     * createLayer() 내부에서만 사용.
     */
    suspend fun writeLayerCode(name: String, code: String): String {
        return try {
            val normalized = name.removeSuffix("Layer")
            val file = File(layerDir, "${normalized}Layer.kt")

            if (file.exists()) {
                val backup = File(layerDir, "${normalized}Layer.kt.bak")
                file.copyTo(backup, overwrite = true)
            }

            layerDir.mkdirs()
            file.writeText(code)
            """SUCCESS: ${file.relativeTo(projectRoot).path} 저장 완료
다음 단계: layerName="build", function="compileKotlin"
[필수후속] 컴파일 완료 후 반드시 develop.reloadLayer(name="$normalized") 실행. reloadLayer 성공 전 finish 불가."""
        } catch (e: Exception) {
            "ERROR: 파일 저장 실패: ${e.message}"
        }
    }

    /**
     * 함수 이름 추출 — LLM이 객체 형식({name=greet, ...})으로 보낼 때도 이름만 추출
     */
    private fun extractFunctionNames(raw: String): List<String> {
        val trimmed = raw.trim()
        return if (trimmed.contains("name=") || trimmed.startsWith("[{") || trimmed.startsWith("{")) {
            // 객체 배열 형식에서 name= 값 추출: {name=greet, description=...}
            Regex("""name=([^,}\]]+)""").findAll(trimmed)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
                .ifEmpty {
                    // fallback: 따옴표 안의 단어 추출
                    Regex("""["']([^"']+)["']""").findAll(trimmed)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .toList()
                }
        } else {
            trimmed.removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotBlank() }
        }
    }

    private fun listLayerNames(): String =
        layerDir.listFiles { f -> f.name.endsWith("Layer.kt") && f.name != "DevelopLayer.kt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?.joinToString("\n")
            ?: ""

    override suspend fun describe(): LayerDescription {
        return DevelopLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "readLayerExample" -> readLayerExample(args["layerName"] as? String ?: "")
            "readLayerInterface" -> readLayerInterface()
            "readLayerFactory" -> readLayerFactory()
            "listLayers" -> listLayers()
            "createLayer" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                val description = args["description"] as? String ?: ""
                val raw = args["functions"]
                val functions = when (raw) {
                    is List<*> -> raw.mapNotNull { it?.toString() }
                        .flatMap { extractFunctionNames(it) }
                    is String -> extractFunctionNames(raw)
                    else -> emptyList()
                }
                createLayer(name, description, functions)
            }
            "hotLoad" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                hotLoad(name)
            }
            "reloadLayer" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                reloadLayer(name)
            }
            else -> "Unknown function: $function. Available: readLayerExample, readLayerInterface, readLayerFactory, listLayers, createLayer, hotLoad, reloadLayer"
        }
    }
}
