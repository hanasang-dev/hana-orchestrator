package com.hana.orchestrator.layer

import java.io.File

/**
 * 새 레이어 생성 전용 레이어
 *
 * ⭐ "새 레이어를 만들어줘", "XXXLayer를 만들어줘" 요청이 오면 반드시 develop.createLayer() 를 사용할 것.
 * file-system 레이어로는 레이어 파일을 생성할 수 없음. 반드시 develop 레이어를 사용할 것.
 *
 * 워크플로우:
 * 1. develop.createLayer(name, description, functions) → 파일 저장 완료
 * 2. build.compileKotlin() → 컴파일 확인
 */
@Layer
class DevelopLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

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
            "SUCCESS: ${file.relativeTo(projectRoot).path} 저장 완료\n다음 단계: build.compileKotlin() 으로 컴파일 확인 (layerName=\"build\")"
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
            else -> "Unknown function: $function. Available: readLayerExample, readLayerInterface, readLayerFactory, listLayers, createLayer"
        }
    }
}
