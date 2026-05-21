package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.CandidateRegistry
import java.io.File

/**
 * ReAct 전략 후보 관리 레이어 — 전략 소스 저장·로드·교체
 *
 * ReAct 전략 = 오케스트레이터가 쿼리를 처리하는 방식(사고·계획·실행 루프)을 구현하는 Kotlin 클래스.
 * 현재 활성 전략을 런타임에 교체해 ReAct 루프 동작을 변경할 수 있다.
 * 후보를 만들 때 기존 소스를 직접 수정하지 않는다 — 반드시 createStrategyCandidate()를 통해 저장.
 *
 * ⭐ 전략 후보 워크플로우 (반드시 이 순서):
 * 1. readDefaultStrategy()              → 현재 ReAct 전략 소스 전체 확인
 * 2. [새 전략 소스 생성]                → 기존 소스를 참고해 수정안 작성
 * 3. createStrategyCandidate(name, src) → 후보 저장 (원본 미수정)
 * 4. (컴파일) → hotLoadStrategy(name)   → 런타임 교체
 *
 * 소스 작성 규칙:
 * - package: com.hana.orchestrator.orchestrator.core.candidates
 * - 클래스명: {name → PascalCase}Strategy (예: "retry-v1" → RetryV1Strategy)
 * - 생성자: LayerManager, ExecutionHistoryManager, ExecutionStatePublisher, ModelSelectionStrategy, TreeExecutor (순서 동일)
 *
 * ⛔ 반드시 createStrategyCandidate()로만 후보 저장 — 직접 파일 쓰기 금지
 */
@Layer
class StrategyLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private var reactiveExecutorRef: com.hana.orchestrator.orchestrator.core.ReactiveExecutor? = null
    private var strategyContextRef: com.hana.orchestrator.orchestrator.core.StrategyContext? = null

    /** ReactiveExecutor 참조 설정 (LayerManager에서 주입) */
    fun setReactiveExecutor(executor: com.hana.orchestrator.orchestrator.core.ReactiveExecutor) {
        reactiveExecutorRef = executor
    }

    /** StrategyContext 참조 설정 (LayerManager에서 주입) */
    fun setStrategyContext(ctx: com.hana.orchestrator.orchestrator.core.StrategyContext) {
        strategyContextRef = ctx
    }

    private val candidatesSourceDir: File
        get() = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/orchestrator/core/candidates")

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
     * 전략 후보 소스 저장 — 기존 DefaultReActStrategy.kt는 수정하지 않음.
     *
     * 후보는 고유 클래스명(PascalCase + "Strategy")으로 candidates 패키지에 저장됩니다.
     * 일반 compileKotlin으로 기존 전략과 함께 빌드되므로 별도 컴파일 불필요.
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
     * 이후 (컴파일) → hotLoadStrategy("DefaultReActStrategy") 순서로 진행하세요.
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
     * 현재 전략 소스 파일의 스냅샷을 저장하고 beta/alpha/rc 단계로 마킹
     *
     * @param name  전략 이름 (예: "DefaultReActStrategy", "retry-v1")
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
            ?: return "ERROR: '$name' 소스 파일을 찾을 수 없습니다. 전략이면 '${name}.kt'를 확인하세요."
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

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

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
        val classLoader = childFirstClassLoader(buildDir, fqcn, this::class.java.classLoader)
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
     * 이름으로 전략 소스 파일 탐색 (candidates 디렉토리 → 프로젝트 전체 순)
     */
    private fun resolveSourceFile(name: String): File? {
        val className = candidateNameToClassName(name)
        val candidateFile = File(candidatesSourceDir, "$className.kt")
        if (candidateFile.exists()) return candidateFile
        return projectRoot.walkTopDown()
            .firstOrNull { it.isFile && (it.name == "$className.kt" || it.name == "$name.kt") }
    }

    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview {
        val kind = when (function) {
            "readDefaultStrategy", "listCandidates" -> ApprovalKind.READ_ONLY
            "createStrategyCandidate", "promoteCandidateToCore" -> ApprovalKind.FILE
            else -> ApprovalKind.EXECUTION
        }
        val preview = args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return ApprovalPreview(
            path = "strategy.$function",
            oldContent = null,
            newContent = preview,
            kind = kind
        )
    }

    override suspend fun describe(): LayerDescription {
        return StrategyLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
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
            else -> throw IllegalArgumentException("Unknown function: $function. Available: readDefaultStrategy, createStrategyCandidate, hotLoadStrategy, promoteCandidateToCore, rollbackStrategy, promote, listCandidates")
        }
    }
}
