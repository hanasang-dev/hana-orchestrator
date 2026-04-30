package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.CandidateRegistry
import java.io.File
import java.net.URLClassLoader

/**
 * 레이어 생성 / 개선 / ReAct 전략 진화 담당 레이어
 *
 * ━━━ [A] 레이어 개선 — "레이어 개선해줘", "XXX 개선해줘", "코드 품질 개선" ━━━
 * ⭐ 2단계 워크플로우 (원본 보호):
 * 1. develop.improveLayer()              → LLM 개선안 생성 → .hana/candidates/ 에 후보 저장 (원본 미수정)
 * 2. develop.applyLayerCandidate(name)   → 후보 검토 후 원본 교체 + 컴파일 + 리로드
 *    또는 develop.rejectLayerCandidate(name) → 후보 파기
 *
 *    layerName 비워두면 자동 선택. 레이어 목록 조회·파일 읽기 불필요.
 *    improveLayer() 반환값에 diff와 다음 호출할 함수명이 포함됨.
 *
 * ━━━ [B] 새 레이어 생성 — "XXX 레이어 만들어줘" ━━━
 * ⭐ develop.createLayer(name, description, functions) → build.compileKotlin → develop.hotLoad(name)
 *
 * ━━━ [C] ReAct 전략 후보 생성 — "전략 후보", "ReAct 루프 개선", "전략을 바꿔줘" ━━━
 * ReAct 전략 = 오케스트레이터가 쿼리를 처리하는 방식 (사고·계획·실행 루프) 을 구현하는 Kotlin 클래스.
 * 후보를 만들 때 기존 소스를 절대 직접 수정하지 않는다.
 *
 * ⭐ 전략 후보 워크플로우 (반드시 이 순서):
 * 1. develop.readDefaultStrategy() → 현재 ReAct 전략 Kotlin 소스 전체 확인
 * 2. llm.analyze(context=전략소스, query="수정사항 반영한 전체 Kotlin 소스 작성") → 새 전략 소스 생성
 * 3. develop.createStrategyCandidate(name="후보이름", sourceCode=생성된소스) → 후보 저장
 * 4. build.compileKotlin → develop.hotLoadStrategy(name) → 런타임 교체
 *
 * ⛔ 전략 후보 작업 시 절대 금지:
 *    - file-system.writeFile 로 기존 파일을 수정하는 것
 *    - develop.createLayer 를 전략 후보 목적으로 사용하는 것
 *    - git.createBranch 를 사용하는 것 (전략 후보는 candidates/ 디렉토리가 격리 역할)
 */
@Layer
class DevelopLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private var layerManagerRef: com.hana.orchestrator.orchestrator.core.LayerManager? = null
    private var reactiveExecutorRef: com.hana.orchestrator.orchestrator.core.ReactiveExecutor? = null
    private var strategyContextRef: com.hana.orchestrator.orchestrator.core.StrategyContext? = null
    private var llmClientFactoryRef: com.hana.orchestrator.llm.factory.LLMClientFactory? = null

    /** LayerManager 참조 설정 (LayerManager에서 주입) */
    fun setLayerManager(layerManager: com.hana.orchestrator.orchestrator.core.LayerManager) {
        layerManagerRef = layerManager
    }

    /** ReactiveExecutor 참조 설정 (LayerManager에서 주입) */
    fun setReactiveExecutor(executor: com.hana.orchestrator.orchestrator.core.ReactiveExecutor) {
        reactiveExecutorRef = executor
    }

    /** 전략 핫로드에 필요한 의존성 컨텍스트 설정 (LayerManager에서 주입) */
    fun setStrategyContext(ctx: com.hana.orchestrator.orchestrator.core.StrategyContext) {
        strategyContextRef = ctx
    }

    /** 내부 LLM 호출용 팩토리 설정 (LayerManager에서 주입) */
    fun setLlmClientFactory(factory: com.hana.orchestrator.llm.factory.LLMClientFactory) {
        llmClientFactoryRef = factory
    }

    private val layerDir: File
        get() = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")

    private val candidatesSourceDir: File
        get() = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/orchestrator/core/candidates")

    private val candidateLayerDir: File
        get() = File(projectRoot, ".hana/candidates")

    /**
     * "retry-v1" → "RetryV1Strategy", "fast-react" → "FastReactStrategy"
     * 케밥/스네이크케이스 → PascalCase + "Strategy" 접미사
     */
    private fun candidateNameToClassName(name: String): String =
        name.split("-", "_")
            .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Strategy"

    /**
     * 현재 기본 전략 소스 조회 (후보 작성 시 참고용)
     *
     * @return DefaultReActStrategy.kt 전체 소스
     */
    @LayerFunction
    suspend fun readDefaultStrategy(): String {
        val file = projectRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "DefaultReActStrategy.kt" }
            ?: return "ERROR: DefaultReActStrategy.kt를 찾을 수 없습니다."
        return file.readText()
    }

    /**
     * 전략 후보 소스 저장 — src/DefaultReActStrategy.kt 는 수정하지 않음.
     *
     * 후보는 고유 클래스명(PascalCase + "Strategy")으로 candidates 패키지에 저장됩니다.
     * 일반 compileKotlin으로 기존 전략과 함께 빌드되므로 별도 컴파일 불필요.
     * 저장 후: compileKotlin → hotLoadStrategy(name) 순서로 진행.
     *
     * 소스 작성 규칙:
     * - package: com.hana.orchestrator.orchestrator.core.candidates
     * - 클래스명: {name → PascalCase}Strategy (예: "retry-v1" → RetryV1Strategy)
     * - 생성자: LayerManager, ExecutionHistoryManager, ExecutionStatePublisher, ModelSelectionStrategy, TreeExecutor (순서 동일)
     *
     * @param name       후보 이름 (예: "retry-v1"). 클래스명 자동 도출.
     * @param sourceCode 후보 전략 Kotlin 소스 전체
     * @return 저장된 파일 경로
     */
    @LayerFunction
    suspend fun createStrategyCandidate(name: String, sourceCode: String): String {
        val className = candidateNameToClassName(name)
        candidatesSourceDir.mkdirs()
        val file = File(candidatesSourceDir, "$className.kt")
        if (file.exists()) file.copyTo(File(candidatesSourceDir, "$className.kt.bak"), overwrite = true)
        return try {
            file.writeText(sourceCode)
            "SUCCESS: 후보 전략 저장 완료\n파일: ${file.relativeTo(projectRoot).path}\n클래스: com.hana.orchestrator.orchestrator.core.candidates.$className\n다음: compileKotlin → hotLoadStrategy(name=\"$name\")"
        } catch (e: Exception) {
            "ERROR: 파일 저장 실패: ${e.message}"
        }
    }

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
     * 기존 레이어 소스 코드를 개선된 버전으로 교체한다.
     *
     * 워크플로우: readLayerExample(layerName) → [LLM이 개선 코드 작성] → developLayer(layerName, 개선된소스)
     *
     * sourceCode 작성 규칙:
     * - 반드시 `package com.hana.orchestrator.layer`로 시작
     * - 순수 Kotlin 소스만 허용 (마크다운, 설명 텍스트 금지)
     * - 기존 클래스명·`@Layer`·`CommonLayerInterface` 구조 유지
     * - 개선 후 자동으로 백업 생성
     *
     * 저장 완료 후 build.compileKotlin → develop.reloadLayer(name) 순서로 실행.
     *
     * @param layerName 개선할 레이어 이름 (예: "Echo", "TextTransformer"). "Layer" 접미사 불필요.
     * @param sourceCode 개선된 Kotlin 소스 전체
     * @return 저장 결과 또는 오류 메시지
     */
    @LayerFunction
    suspend fun developLayer(layerName: String, sourceCode: String): String {
        val trimmed = sourceCode.trimStart()
            .let { if (it.startsWith("```")) it.lines().drop(1).joinToString("\n") else it }
            .trimEnd()
            .let { if (it.endsWith("```")) it.dropLast(3).trimEnd() else it }

        if (!trimmed.trimStart().startsWith("package ")) {
            return "ERROR: sourceCode가 'package' 선언으로 시작하지 않습니다. 순수 Kotlin 소스만 허용됩니다."
        }
        return writeLayerCode(layerName, trimmed)
    }

    /**
     * ⭐ "레이어 개선" 요청 → 즉시 호출. 인자 없이 사용 가능.
     *
     * LLM으로 개선안 생성 → .hana/candidates/ 에 후보 저장 (원본 미수정) → diff 반환.
     * 이후 applyLayerCandidate(layerName) 로 적용하거나 rejectLayerCandidate(layerName) 로 파기.
     *
     * @param layerName 개선할 레이어 이름 (예: "Echo", "Git"). 비워두면 자동 선택.
     * @param goal 개선 목표. 기본값: "코드 품질 및 가독성 개선"
     * @return diff 및 후보 경로, 또는 "ERROR: ..."
     */
    @LayerFunction
    suspend fun improveLayer(layerName: String = "", goal: String = "코드 품질 및 가독성 개선"): String {
        val factory = llmClientFactoryRef ?: return "ERROR: LLMClientFactory가 주입되지 않았습니다."

        val resolvedName = if (layerName.isBlank()) {
            val candidates = layerDir.listFiles { f ->
                f.name.endsWith("Layer.kt") &&
                f.name != "DevelopLayer.kt" &&
                !f.name.endsWith(".bak") &&
                !f.name.endsWith(".candidate.kt")
            }?.filter { f ->
                val classLine = f.readLines().firstOrNull { it.trimStart().startsWith("class ") } ?: ""
                val parenContent = classLine.substringAfter("(", "").substringBefore(")", "").trim()
                parenContent.isEmpty()
            }?.map { it.nameWithoutExtension.removeSuffix("Layer") } ?: emptyList()
            if (candidates.isEmpty()) return "ERROR: 개선할 레이어가 없습니다 (no-arg constructor 레이어 없음)."
            candidates.random()
        } else layerName.removeSuffix("Layer")

        val source = readLayerExample(resolvedName)
        if (source.startsWith("ERROR")) return source

        val originalClassDecl = source.lines().firstOrNull { it.trimStart().startsWith("class ") }?.trim() ?: ""

        val prompt = """아래 Kotlin 소스 코드를 분석하고 개선된 버전을 작성하세요.

개선 목표: $goal

현재 소스:
$source

[절대 준수 규칙]
1. 출력은 순수 Kotlin 소스만. 마크다운 코드펜스(```) 금지. 설명 텍스트 금지.
2. 반드시 `package com.hana.orchestrator.layer` 로 시작.
3. 클래스 선언 "$originalClassDecl" 을 정확히 그대로 유지. 생성자 시그니처 변경 금지.
4. @Layer 어노테이션, CommonLayerInterface 구현, describe(), execute() 구조 그대로 유지.
5. 기존 함수 시그니처(@LayerFunction 포함) 유지. 구현 내용만 개선.
6. 개선 사항이 없으면 package 선언 다음 줄에 `// 개선 없음: (이유)` 주석 후 원본 그대로 출력."""

        val llmClient = factory.createMediumClient()
        val improved = try {
            llmClient.generateDirectAnswer(prompt)
        } catch (e: Exception) {
            return "ERROR: LLM 호출 실패: ${e.message}"
        } finally {
            try { llmClient.close() } catch (_: Exception) {}
        }

        val extracted = extractKotlinCode(improved)
        if (!extracted.trimStart().startsWith("package ")) {
            return "ERROR: LLM 출력이 유효한 Kotlin 소스가 아닙니다. 'package' 선언으로 시작해야 합니다."
        }

        // 후보 파일 저장 (원본 미수정)
        candidateLayerDir.mkdirs()
        val candidateFile = File(candidateLayerDir, "${resolvedName}Layer.candidate.kt")
        candidateFile.writeText(extracted)

        val originalFile = File(layerDir, "${resolvedName}Layer.kt")
        val diff = computeDiff(originalFile, candidateFile)

        return buildString {
            appendLine("CANDIDATE: ${resolvedName}Layer 개선 후보 저장 완료 (원본 미수정)")
            appendLine("후보: ${candidateFile.relativeTo(projectRoot).path}")
            appendLine()
            appendLine("=== DIFF (원본 → 후보) ===")
            appendLine(diff.take(1500))
            if (diff.length > 1500) appendLine("...[diff 잘림, 전체 ${diff.length}자]")
            appendLine()
            append("적용: applyLayerCandidate(layerName=\"$resolvedName\") | 거부: rejectLayerCandidate(layerName=\"$resolvedName\")")
        }
    }

    /**
     * 후보 레이어를 원본에 적용 (원본 교체 + 컴파일 + 리로드).
     * improveLayer() 로 생성된 후보를 검토 후 호출.
     *
     * @param layerName 적용할 레이어 이름 (improveLayer와 동일)
     * @return "SUCCESS: ..." 또는 "ERROR: ..."
     */
    @LayerFunction
    suspend fun applyLayerCandidate(layerName: String): String {
        val normalized = layerName.removeSuffix("Layer")
        val candidateFile = File(candidateLayerDir, "${normalized}Layer.candidate.kt")
        if (!candidateFile.exists()) {
            return "ERROR: 후보 없음: ${candidateFile.relativeTo(projectRoot).path}. improveLayer()를 먼저 실행하세요."
        }

        val content = candidateFile.readText()
        val writeResult = developLayer(normalized, content)
        if (!writeResult.startsWith("SUCCESS")) return writeResult

        candidateFile.delete()

        val compileResult = runGradleTask("compileKotlin")
        if (compileResult.contains("BUILD FAILED") || compileResult.startsWith("ERROR")) {
            return "ERROR: 컴파일 실패 — 백업(.bak)에서 복구 가능\n$compileResult"
        }

        val reloadRaw = try { reloadLayer(normalized) } catch (e: Exception) { e.message ?: "skip" }
        val reloadSummary = when {
            reloadRaw.startsWith("SUCCESS") -> reloadRaw
            reloadRaw.contains("no-arg") || reloadRaw.contains("기본 생성자") ->
                "컴파일 완료 — 서버 재시작 시 자동 적용"
            else -> "컴파일 완료 (리로드: $reloadRaw)"
        }

        return "SUCCESS: ${normalized}Layer 후보 적용·컴파일 완료. $reloadSummary ← 목표 달성. finish 선택."
    }

    /**
     * 후보 레이어 파기 (원본 유지, 후보 파일 삭제).
     *
     * @param layerName 파기할 레이어 이름
     * @return "SUCCESS: ..." 또는 "ERROR: ..."
     */
    @LayerFunction
    suspend fun rejectLayerCandidate(layerName: String): String {
        val normalized = layerName.removeSuffix("Layer")
        val candidateFile = File(candidateLayerDir, "${normalized}Layer.candidate.kt")
        return if (candidateFile.exists()) {
            candidateFile.delete()
            "SUCCESS: ${normalized}Layer 후보 파기 완료. 원본 유지됨."
        } else {
            "ERROR: 후보 없음: ${candidateFile.relativeTo(projectRoot).path}"
        }
    }

    /**
     * 현재 대기 중인 레이어 후보 목록 조회.
     *
     * @return 후보 목록 또는 "대기 중인 후보 없음"
     */
    @LayerFunction
    suspend fun listLayerCandidates(): String {
        val files = candidateLayerDir.listFiles { f -> f.name.endsWith(".candidate.kt") }
            ?: return "대기 중인 후보 없음"
        if (files.isEmpty()) return "대기 중인 후보 없음"
        return files.sortedBy { it.name }.joinToString("\n") { f ->
            val name = f.nameWithoutExtension.removeSuffix(".candidate")
            "  - $name  (${f.length()} bytes, ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(f.lastModified()))})"
        }
    }

    /** unified diff 생성 (diff 커맨드 사용, 실패 시 간단 비교로 폴백) */
    private fun computeDiff(original: File, candidate: File): String {
        return try {
            val process = ProcessBuilder("diff", "-u", original.absolutePath, candidate.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.ifBlank { "(변경 없음)" }
        } catch (_: Exception) {
            // diff 명령어 없는 환경 폴백
            val origLines = original.readLines().size
            val candLines = candidate.readLines().size
            "(diff 생성 불가 — 원본 ${origLines}줄 → 후보 ${candLines}줄)"
        }
    }

    /**
     * Gradle 태스크 내부 실행 (improveLayer 전용 — LLM에게 노출 안 됨)
     */
    private fun runGradleTask(task: String): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val gradlew = if (isWindows) "gradlew.bat" else "./gradlew"
        return try {
            val process = ProcessBuilder(gradlew, task, "--no-daemon")
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) "BUILD SUCCESSFUL" else "BUILD FAILED\n$output"
        } catch (e: Exception) {
            "ERROR: Gradle 실행 실패: ${e.message}"
        }
    }

    /**
     * LLM 응답에서 순수 Kotlin 코드 추출.
     * ```kotlin ... ``` 블록이 있으면 내용만 추출, 없으면 원본 반환.
     */
    private fun extractKotlinCode(text: String): String {
        val trimmed = text.trim()
        val codeBlockRegex = Regex("```(?:kotlin)?\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        return codeBlockRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
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
     * 이후에는 컴파일 확인 후 hotLoad(name)으로 런타임 등록.
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
        if (!buildDir.exists()) error("빌드 출력 디렉토리가 없습니다. 먼저 컴파일을 실행하세요.")
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
     * 전략 클래스를 build 출력 디렉토리에서 로드하고 의존성을 주입해 반환.
     *
     * - "DefaultReActStrategy" → core 패키지에서 로드 (원본 복구용)
     * - 그 외 이름            → candidates 패키지에서 로드 (후보 전략)
     *
     * 생성자 시그니처: (LayerManager, ExecutionHistoryManager,
     * ExecutionStatePublisher, ModelSelectionStrategy, TreeExecutor)
     */
    private fun loadStrategyInstance(
        name: String,
        ctx: com.hana.orchestrator.orchestrator.core.StrategyContext
    ): com.hana.orchestrator.orchestrator.core.ReActStrategy {
        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        if (!buildDir.exists()) error("빌드 출력 디렉토리가 없습니다. 먼저 컴파일을 실행하세요.")
        val fqcn = if (name == "DefaultReActStrategy") {
            "com.hana.orchestrator.orchestrator.core.DefaultReActStrategy"
        } else {
            "com.hana.orchestrator.orchestrator.core.candidates.${candidateNameToClassName(name)}"
        }
        val classLoader = childFirstStrategyClassLoader(buildDir, fqcn, this::class.java.classLoader)
        val clazz = classLoader.loadClass(fqcn)
        // 6-param 생성자(ClarificationGate 포함)를 먼저 시도, 없으면 5-param fallback
        val ctor6 = try {
            clazz.getDeclaredConstructor(
                com.hana.orchestrator.orchestrator.core.LayerManager::class.java,
                com.hana.orchestrator.orchestrator.ExecutionHistoryManager::class.java,
                com.hana.orchestrator.orchestrator.ExecutionStatePublisher::class.java,
                com.hana.orchestrator.llm.strategy.ModelSelectionStrategy::class.java,
                com.hana.orchestrator.orchestrator.core.TreeExecutor::class.java,
                com.hana.orchestrator.orchestrator.ClarificationGate::class.java
            )
        } catch (_: NoSuchMethodException) { null }

        if (ctor6 != null) {
            return ctor6.newInstance(
                ctx.layerManager,
                ctx.historyManager,
                ctx.statePublisher,
                ctx.modelSelectionStrategy,
                ctx.treeExecutor,
                ctx.clarificationGate
            ) as com.hana.orchestrator.orchestrator.core.ReActStrategy
        }

        // 5-param fallback (구버전 후보 전략)
        val ctor5 = clazz.getDeclaredConstructor(
            com.hana.orchestrator.orchestrator.core.LayerManager::class.java,
            com.hana.orchestrator.orchestrator.ExecutionHistoryManager::class.java,
            com.hana.orchestrator.orchestrator.ExecutionStatePublisher::class.java,
            com.hana.orchestrator.llm.strategy.ModelSelectionStrategy::class.java,
            com.hana.orchestrator.orchestrator.core.TreeExecutor::class.java
        )
        return ctor5.newInstance(
            ctx.layerManager,
            ctx.historyManager,
            ctx.statePublisher,
            ctx.modelSelectionStrategy,
            ctx.treeExecutor
        ) as com.hana.orchestrator.orchestrator.core.ReActStrategy
    }

    /**
     * 전략 클래스 전용 child-first URLClassLoader.
     * fqcn 접두사를 가진 클래스(중첩/람다 포함)는 buildDir에서 먼저 탐색한다.
     */
    private fun childFirstStrategyClassLoader(
        buildDir: File,
        fqcn: String,
        parent: ClassLoader
    ): URLClassLoader {
        return object : URLClassLoader(arrayOf(buildDir.toURI().toURL()), parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith(fqcn)) {
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
     * 소스 파일 저장 후 컴파일이 성공한 경우에 호출하세요.
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
        catch (e: ClassNotFoundException) { "ERROR: 클래스를 찾을 수 없습니다. 먼저 컴파일을 실행하세요." }
        catch (e: NoSuchMethodException) { "ERROR: 기본 생성자(no-arg constructor)가 없습니다." }
        catch (e: ClassCastException) { "ERROR: CommonLayerInterface를 구현하지 않습니다." }
        catch (e: Exception) { "ERROR: 동적 로드 실패: ${e.message}" }
    }

    /**
     * 기존 레이어를 새 컴파일 결과로 교체합니다 (신규 등록에도 사용 가능).
     * 소스 파일을 수정하고 컴파일이 성공한 후 호출하세요.
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
        catch (e: ClassNotFoundException) { "ERROR: 클래스를 찾을 수 없습니다. 먼저 컴파일을 실행하세요." }
        catch (e: NoSuchMethodException) { "ERROR: 기본 생성자(no-arg constructor)가 없습니다." }
        catch (e: ClassCastException) { "ERROR: CommonLayerInterface를 구현하지 않습니다." }
        catch (e: Exception) { "ERROR: 리로드 실패: ${e.message}" }
    }

    /**
     * 컴파일 완료된 ReAct 전략을 런타임에 교체합니다. 다음 실행부터 즉시 적용됩니다.
     * "DefaultReActStrategy": 원본 전략 복구. 후보 이름(예: "retry-v1"): 해당 후보 적용.
     *
     * @param name 후보 이름(예: "retry-v1") 또는 "DefaultReActStrategy"(원본 복구)
     */
    @LayerFunction
    suspend fun hotLoadStrategy(name: String): String {
        val executor = reactiveExecutorRef ?: return "ERROR: ReactiveExecutor가 주입되지 않았습니다."
        val stratCtx = strategyContextRef ?: return "ERROR: StrategyContext가 주입되지 않았습니다."
        return try {
            val instance = loadStrategyInstance(name, stratCtx)
            executor.setStrategy(instance)
            "SUCCESS: '$name' 전략 핫로드 완료. 다음 실행부터 새 전략이 적용됩니다."
        } catch (e: IllegalStateException) { "ERROR: ${e.message}" }
        catch (e: ClassNotFoundException) { "ERROR: 클래스를 찾을 수 없습니다: $name. 컴파일이 성공했는지 확인하세요." }
        catch (e: NoSuchMethodException) { "ERROR: 호환 생성자가 없습니다. DefaultReActStrategy와 동일한 생성자 시그니처를 사용하세요." }
        catch (e: ClassCastException) { "ERROR: ReActStrategy를 구현하지 않습니다." }
        catch (e: Exception) { "ERROR: 전략 핫로드 실패: ${e.message}" }
    }

    /**
     * 후보 전략을 기본 전략으로 승격 (이때만 src/DefaultReActStrategy.kt 교체됨).
     * 이후 compileKotlin → hotLoadStrategy("DefaultReActStrategy") 순서로 진행하세요.
     * 승격 전 원본은 .bak 파일로 자동 백업됩니다.
     *
     * @param name 후보 이름 (예: "retry-v1"). createStrategyCandidate로 저장한 이름과 동일.
     * @return 성공 메시지 또는 에러
     */
    @LayerFunction
    suspend fun promoteCandidateToCore(name: String): String {
        val className = candidateNameToClassName(name)
        val candidateFile = File(candidatesSourceDir, "$className.kt")
        if (!candidateFile.exists()) {
            return "ERROR: 후보 소스 없음: ${candidateFile.relativeTo(projectRoot).path}"
        }
        val targetFile = projectRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "DefaultReActStrategy.kt" }
            ?: return "ERROR: DefaultReActStrategy.kt를 찾을 수 없습니다."

        val promoted = candidateFile.readText()
            .replace(
                "package com.hana.orchestrator.orchestrator.core.candidates",
                "package com.hana.orchestrator.orchestrator.core"
            )
            .replace(Regex("class\\s+${Regex.escape(className)}\\s*\\("), "class DefaultReActStrategy(")

        val backup = File(targetFile.parent, "DefaultReActStrategy.kt.bak")
        targetFile.copyTo(backup, overwrite = true)
        targetFile.writeText(promoted)

        return "SUCCESS: '$name'($className) → DefaultReActStrategy 승격 완료\n" +
            "백업: ${backup.relativeTo(projectRoot).path}\n" +
            "다음: compileKotlin → hotLoadStrategy(name=\"DefaultReActStrategy\")"
    }

    /**
     * 현재 전략을 즉시 DefaultReActStrategy로 복구합니다.
     * build/classes의 DefaultReActStrategy.class를 로드하므로 재컴파일 불필요.
     *
     * @return 성공 메시지 또는 에러
     */
    @LayerFunction
    suspend fun rollbackStrategy(): String {
        val executor = reactiveExecutorRef ?: return "ERROR: ReactiveExecutor가 주입되지 않았습니다."
        val stratCtx = strategyContextRef ?: return "ERROR: StrategyContext가 주입되지 않았습니다."
        return try {
            val instance = loadStrategyInstance("DefaultReActStrategy", stratCtx)
            executor.setStrategy(instance)
            "SUCCESS: DefaultReActStrategy로 즉시 복구 완료."
        } catch (e: Exception) {
            "ERROR: 롤백 실패: ${e.message}"
        }
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

    /**
     * 현재 소스 파일의 스냅샷을 저장하고 beta/alpha/rc 단계로 마킹
     *
     * @param name  대상 이름 (레이어면 "Layer" 접미사 생략 가능. 예: "Greeter", "DefaultReActStrategy")
     * @param stage 단계 — "beta" | "alpha" | "rc"
     * @param description 이번 스냅샷의 변경 내용 요약 (선택)
     * @return 저장된 스냅샷 경로 또는 에러 메시지
     */
    @LayerFunction
    suspend fun promote(name: String, stage: String, description: String = ""): String {
        val stageEnum = try {
            CandidateRegistry.Stage.valueOf(stage.lowercase())
        } catch (_: IllegalArgumentException) {
            return "ERROR: stage는 beta/alpha/rc 중 하나여야 합니다: $stage"
        }
        val sourceFile = resolveSourceFile(name)
            ?: return "ERROR: '$name' 소스 파일을 찾을 수 없습니다. 레이어면 '${name}Layer.kt', 전략이면 '${name}.kt'를 확인하세요."
        val snapshotPath = CandidateRegistry.promote(name, stageEnum, sourceFile, description)
        return if (snapshotPath.startsWith("ERROR")) snapshotPath
        else "SUCCESS: '$name' → $stage 마킹 완료\n스냅샷: $snapshotPath"
    }

    /**
     * 등록된 후보 목록 조회
     *
     * @param name 필터할 이름 (생략 시 전체)
     * @return 후보 목록 (이름 / 단계 / 설명 / 날짜)
     */
    @LayerFunction
    suspend fun listCandidates(name: String = ""): String {
        val filter = name.ifBlank { null }
        val list = CandidateRegistry.list(filter)
        if (list.isEmpty()) return "등록된 후보가 없습니다."
        return list.joinToString("\n") { entry ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(entry.createdAt))
            "[${entry.stage.uppercase()}] ${entry.name}  ($date)${if (entry.description.isNotBlank()) "\n  └ ${entry.description}" else ""}"
        }
    }

    /**
     * 이름으로 소스 파일 탐색 (레이어 디렉토리 → 프로젝트 전체 순)
     */
    private fun resolveSourceFile(name: String): File? {
        val normalized = name.removeSuffix("Layer")
        // 1. 레이어 디렉토리에서 XxxLayer.kt 탐색
        val layerFile = File(layerDir, "${normalized}Layer.kt")
        if (layerFile.exists()) return layerFile
        // 2. 레이어 디렉토리에서 Xxx.kt 탐색 (전략 등)
        val plainFile = File(layerDir, "${name}.kt")
        if (plainFile.exists()) return plainFile
        // 3. 프로젝트 전체에서 파일명으로 탐색
        return projectRoot.walkTopDown()
            .firstOrNull { it.isFile && (it.name == "${normalized}Layer.kt" || it.name == "$name.kt") }
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
            "readDefaultStrategy" -> readDefaultStrategy()
            "createStrategyCandidate" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                val sourceCode = args["sourceCode"] as? String ?: return "ERROR: sourceCode 필수"
                createStrategyCandidate(name, sourceCode)
            }
            "hotLoadStrategy" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                hotLoadStrategy(name)
            }
            "promoteCandidateToCore" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                promoteCandidateToCore(name)
            }
            "rollbackStrategy" -> rollbackStrategy()
            "promote" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                val stage = args["stage"] as? String ?: return "ERROR: stage 필수 (beta/alpha/rc)"
                val description = args["description"] as? String ?: ""
                promote(name, stage, description)
            }
            "listCandidates" -> {
                val name = args["name"] as? String ?: ""
                listCandidates(name)
            }
            "developLayer" -> {
                val layerName = args["layerName"] as? String ?: return "ERROR: layerName 필수"
                val sourceCode = args["sourceCode"] as? String ?: return "ERROR: sourceCode 필수"
                developLayer(layerName, sourceCode)
            }
            "improveLayer" -> {
                val layerName = args["layerName"] as? String ?: ""
                val goal = args["goal"] as? String ?: "코드 품질 및 가독성 개선"
                improveLayer(layerName, goal)
            }
            "applyLayerCandidate" -> {
                val layerName = args["layerName"] as? String ?: return "ERROR: layerName 필수"
                applyLayerCandidate(layerName)
            }
            "rejectLayerCandidate" -> {
                val layerName = args["layerName"] as? String ?: return "ERROR: layerName 필수"
                rejectLayerCandidate(layerName)
            }
            "listLayerCandidates" -> listLayerCandidates()
            else -> "Unknown function: $function. Available: readLayerExample, readLayerInterface, readLayerFactory, listLayers, createLayer, hotLoad, reloadLayer, readDefaultStrategy, createStrategyCandidate, hotLoadStrategy, promoteCandidateToCore, rollbackStrategy, promote, listCandidates, developLayer, improveLayer, applyLayerCandidate, rejectLayerCandidate, listLayerCandidates"
        }
    }
}
