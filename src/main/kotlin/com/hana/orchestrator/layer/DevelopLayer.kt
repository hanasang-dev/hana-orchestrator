package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.CandidateRegistry
import java.io.File
import com.hana.orchestrator.layer.SelfAction
import com.hana.orchestrator.layer.SelfActionTiming

/**
 * 레이어 생성 / 개선 담당 레이어
 *
 * ━━━ [A] 레이어 개선 — "레이어 개선해줘", "XXX 개선해줘", "코드 품질 개선" ━━━
 * ⭐ 3단계 워크플로우 (자동 리뷰 게이트):
 * 1. develop.improveLayer()                → LLM 개선안 생성 → .hana/candidates/ 저장 (원본 미수정)
 * 2. develop.reviewLayerCandidate(name)    → LLM이 diff 리뷰 → 격리 규칙·품질 검증 → 승인시 자동 적용, 거절시 자동 파기
 *    (수동 override 필요시만: applyLayerCandidate / rejectLayerCandidate 직접 호출)
 *
 *    layerName 비워두면 자동 선택. 레이어 목록 조회·파일 읽기 불필요.
 *    improveLayer() 성공 후 프레임워크가 reviewLayerCandidate()를 자동 실행 (@RequiresSelfAction).
 *
 * ━━━ [B] 새 레이어 생성 — "XXX 레이어 만들어줘" ━━━
 * ⭐ develop.createLayer(name, description, functions) → (컴파일) → develop.hotLoad(name)
 */
@Layer
class DevelopLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private var layerManagerRef: com.hana.orchestrator.orchestrator.core.LayerManager? = null
    private var llmClientFactoryRef: com.hana.orchestrator.llm.factory.LLMClientFactory? = null

    /** LayerManager 참조 설정 (LayerManager에서 주입) */
    fun setLayerManager(layerManager: com.hana.orchestrator.orchestrator.core.LayerManager) {
        layerManagerRef = layerManager
    }

    /** 내부 LLM 호출용 팩토리 설정 (LayerManager에서 주입) */
    fun setLlmClientFactory(factory: com.hana.orchestrator.llm.factory.LLMClientFactory) {
        llmClientFactoryRef = factory
    }

    private val layerDir: File
        get() = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")

    private val candidateLayerDir: File
        get() = File(projectRoot, ".hana/candidates")

    /**
     * 레이어 이름 정규화 — LLM 이 보내는 다양한 표기를 PascalCase 로 통일.
     *
     * 허용 입력:
     *   "TextValidator"        → "TextValidator"  (그대로)
     *   "TextValidatorLayer"   → "TextValidator"  (Layer suffix 제거)
     *   "text-validator"       → "TextValidator"  (kebab → Pascal)
     *   "text_validator"       → "TextValidator"  (snake → Pascal)
     *   "text-validator-layer" → "TextValidator"  (suffix + kebab)
     *   " TextValidator "      → "TextValidator"  (trim)
     */
    private fun normalizeLayerName(raw: String): String {
        var s = raw.trim()
        if (s.endsWith("Layer", ignoreCase = true)) {
            s = s.dropLast(5).trimEnd('-', '_')
        }
        if (s.contains('-') || s.contains('_')) {
            s = s.split('-', '_')
                .filter { it.isNotBlank() }
                .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        }
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * 기존 레이어 파일 읽기 (패턴 참고용)
     *
     * @param layerName 레이어 이름 (예: "Build", "FileSystem", "Git"). "Layer" 접미사 불필요.
     * @return 레이어 소스 코드
     */
    @LayerFunction
    suspend fun readLayerExample(layerName: String): String {
        val normalized = normalizeLayerName(layerName)
        val file = File(layerDir, "${normalized}Layer.kt")
        if (!file.exists()) {
            val available = listLayerNames()
            throw IllegalArgumentException("${normalized}Layer.kt 를 찾을 수 없습니다. 사용 가능한 레이어:\n$available")
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
        if (!file.exists()) throw IllegalStateException("CommonLayerInterface.kt 를 찾을 수 없습니다.")
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
        if (!file.exists()) throw IllegalStateException("LayerFactory.kt 를 찾을 수 없습니다.")
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
     * ⭐ 레이어 개선 요청 시 즉시 호출.
     *
     * 특정 레이어를 개선할 때 layerName 을 반드시 지정하라.
     * 파일을 직접 찾거나 읽을 필요 없음 — layerName 만 넘기면 내부에서 처리.
     *
     * LLM으로 개선안 생성 → .hana/candidates/ 에 후보 저장 (원본 미수정) → diff 반환.
     * 완료 후 프레임워크가 reviewLayerCandidate()를 자동 실행 — 수동 호출 불필요.
     *
     * @param layerName 개선할 레이어 이름 (예: "Echo", "Git"). 지정하지 않으면 랜덤 선택.
     * @param goal 개선 목표. 기본값: "코드 품질 및 가독성 개선"
     * @return diff 및 후보 경로
     */
    @SelfAction("findRelevantFiles", timing = SelfActionTiming.PRE)
    @RequiresSelfAction("reviewLayerCandidate")
    @LayerFunction
    suspend fun improveLayer(layerName: String = "", goal: String = "코드 품질 및 가독성 개선", context: String = ""): String {
        val factory = llmClientFactoryRef
            ?: throw IllegalStateException("LLMClientFactory가 주입되지 않았습니다.")

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
        } else normalizeLayerName(layerName)

        val source = readLayerExample(resolvedName)

        val originalClassDecl = source.lines().firstOrNull { it.trimStart().startsWith("class ") }?.trim() ?: ""

        // 컨텍스트 번들: 인터페이스 계약 + 레이어 개발 규칙 (구조적 이해 기반 개선)
        val interfaceSource = File(layerDir, "CommonLayerInterface.kt").takeIf { it.exists() }?.readText() ?: ""
        val layerRules = File(layerDir, "CLAUDE.md").takeIf { it.exists() }?.readText() ?: ""
        val contextSection = buildString {
            if (interfaceSource.isNotBlank()) {
                appendLine("=== 레이어 인터페이스 계약 (반드시 준수) ===")
                appendLine(interfaceSource)
            }
            if (layerRules.isNotBlank()) {
                appendLine("=== 레이어 개발 규칙 (반드시 준수) ===")
                appendLine(layerRules)
            }
            if (context.isNotBlank()) {
                appendLine("=== 관련 파일 컨텍스트 (구조적 개선 참고) ===")
                appendLine(context)
            }
        }

        val numberedSource = source.lines()
            .mapIndexed { i, line -> "${i + 1}: $line" }
            .joinToString("\n")

        val prompt = """아래 Kotlin 소스 코드를 분석하고, 개선이 필요한 줄 범위와 교체 코드를 출력하세요.

개선 목표: $goal

$contextSection=== 개선 대상 소스 (${resolvedName}Layer.kt) — 줄 번호 포함 ===
$numberedSource

[출력 형식 — 반드시 준수]
개선할 각 구간마다 아래 블록 하나씩 출력. 블록 수 제한 없음.

REPLACE 시작줄-끝줄:
(교체할 새 코드. 줄 번호 제외)
END

예시: 3번 줄부터 7번 줄을 교체하려면:
REPLACE 3-7:
fun example() = "improved"
END

[절대 준수 규칙]
1. 줄 번호는 위 소스의 번호를 그대로 사용. 1-based.
2. 기존 함수 시그니처(@LayerFunction 포함) 유지. 구현 내용만 개선.
3. 클래스 선언 "$originalClassDecl" 변경 금지.
4. 레이어 격리 규칙: KDoc 및 코드에 타 레이어명·함수명 절대 언급 금지.
5. 개선 사항이 없으면 NO_CHANGES 한 줄만 출력."""

        val llmClient = factory.createMediumClient()
        val patchText = try {
            llmClient.generateDirectAnswer(prompt)
        } catch (e: Exception) {
            return "ERROR: LLM 호출 실패: ${e.message}"
        } finally {
            try { llmClient.close() } catch (_: Exception) {}
        }

        if (patchText.trim() == "NO_CHANGES") {
            throw IllegalStateException("${resolvedName}Layer 개선 사항 없음 — 다른 레이어 또는 다른 goal을 지정하세요.")
        }

        val candidate = applyLinePatch(source, patchText)
            ?: return "ERROR: 패치 적용 실패 — REPLACE 블록이 유효하지 않습니다 (줄 번호 범위 초과 등).\n\n[LLM 출력]\n${patchText.take(500)}"

        // E1: 후보 자가검토 — SIMPLE LLM으로 품질 게이트
        val reviewResult = reviewCandidateCode(factory, source, candidate, resolvedName, originalClassDecl)
        if (reviewResult != null) {
            return "REJECTED(E1): ${resolvedName}Layer 후보 자가검토 실패 — $reviewResult"
        }

        // 후보 파일 저장 (원본 미수정)
        candidateLayerDir.mkdirs()
        val candidateFile = File(candidateLayerDir, "${resolvedName}Layer.candidate.kt")
        candidateFile.writeText(candidate)

        val originalFile = File(layerDir, "${resolvedName}Layer.kt")
        val diff = computeDiff(originalFile, candidateFile)

        return buildString {
            appendLine("CANDIDATE: ${resolvedName}Layer 개선 후보 저장 완료 (원본 미수정)")
            appendLine("후보: ${candidateFile.relativeTo(projectRoot).path}")
            appendLine()
            appendLine("=== DIFF (원본 → 후보) ===")
            appendLine(diff.take(1500))
            if (diff.length > 1500) appendLine("...[diff 잘림, 전체 ${diff.length}자]")
        }
    }

    /**
     * 후보 레이어를 원본에 적용한다. Two-Phase Commit 으로 원자성을 보장한다.
     *
     * Phase 1 (prepare): 원본을 후보로 교체 → 컴파일 검증
     *   - 컴파일 실패 → 원본 자동 복구, 후보 보존, ERROR 반환
     * Phase 2 (commit): 컴파일 통과 시에만 후보 파일 정리 + 런타임 리로드
     *
     * 불변식: 함수 반환 시점에 원본 파일은 항상 컴파일 가능한 상태.
     *
     * @param layerName 적용할 레이어 이름 (improveLayer와 동일)
     * @return "SUCCESS: ..." 또는 "ERROR: ..."
     */
    @LayerFunction
    suspend fun applyLayerCandidate(layerName: String): String {
        val normalized = normalizeLayerName(layerName)
        val candidateFile = File(candidateLayerDir, "${normalized}Layer.candidate.kt")
        if (!candidateFile.exists()) {
            return "ERROR: 후보 없음: ${candidateFile.relativeTo(projectRoot).path}. improveLayer()를 먼저 실행하세요."
        }

        val sourceFile = File(layerDir, "${normalized}Layer.kt")
        val backupFile = File(layerDir, "${normalized}Layer.kt.bak")

        // B6 — sandbox: src/ 건드리기 전 컴파일 검증 (실패 시 throw, src/ 미수정)
        com.hana.orchestrator.build.Sandbox(projectRoot)
            .stage(sourceFile, candidateFile)
            .compile()

        // Phase 1a — prepare: swap source (writeLayerCode 가 .bak 생성)
        val content = candidateFile.readText()
        val writeResult = developLayer(normalized, content)
        if (!writeResult.startsWith("SUCCESS")) return writeResult

        // Phase 1b — validate: 실제 src/ 컴파일 (sandbox 통과 후 안전망)
        val compileResult = runGradleTask("compileKotlin")
        val compileFailed = compileResult.contains("BUILD FAILED") || compileResult.startsWith("ERROR")
        if (compileFailed) {
            // Rollback: .bak → 원본 복구. 후보는 그대로 보존(재시도 가능).
            if (backupFile.exists()) {
                backupFile.copyTo(sourceFile, overwrite = true)
            }
            return "ERROR: 컴파일 실패 — 원본 자동 복구 완료. 후보 보존: ${candidateFile.relativeTo(projectRoot).path}\n$compileResult"
        }

        // Phase 2 — commit: 후보 정리 + 리로드
        candidateFile.delete()

        val reloadRaw = try { reloadLayer(normalized) } catch (e: Throwable) { e.message ?: "skip" }
        val reloadSummary = when {
            reloadRaw.startsWith("SUCCESS") -> reloadRaw
            reloadRaw.contains("no-arg") || reloadRaw.contains("기본 생성자") ->
                "컴파일 완료 — 서버 재시작 시 자동 적용"
            else -> "컴파일 완료 (리로드: $reloadRaw)"
        }

        return "SUCCESS: ${normalized}Layer 후보 적용·컴파일 완료. $reloadSummary ← 목표 달성. finish 선택."
    }

    /**
     * 후보 레이어를 LLM으로 리뷰한 뒤 자동 적용 또는 파기.
     *
     * improveLayer() 이후 반드시 이 함수를 호출하세요.
     * 리뷰 기준: 유의미한 개선인가? 격리 규칙 위반 없는가? 버그·회귀 없는가?
     * 승인 → applyLayerCandidate 내부 호출 (컴파일+리로드)
     * 거절 → rejectLayerCandidate 내부 호출 (후보 파기, 원본 유지)
     *
     * @param layerName 리뷰할 레이어 이름 (improveLayer와 동일)
     * @return "APPROVED: ..." 또는 "REJECTED: ..." + 이유
     */
    @LayerFunction
    suspend fun reviewLayerCandidate(layerName: String = ""): String {
        val factory = llmClientFactoryRef
            ?: throw IllegalStateException("LLMClientFactory가 주입되지 않았습니다.")
        val normalized = if (layerName.isBlank()) {
            val candidates = candidateLayerDir.listFiles { f -> f.name.endsWith("Layer.candidate.kt") }
            if (candidates.isNullOrEmpty()) throw IllegalStateException("검토할 후보 없음 — 이미 처리됨.")
            candidates.first().name.removeSuffix("Layer.candidate.kt")
        } else normalizeLayerName(layerName)
        val candidateFile = File(candidateLayerDir, "${normalized}Layer.candidate.kt")
        val originalFile = File(layerDir, "${normalized}Layer.kt")

        if (!candidateFile.exists()) throw IllegalStateException("후보 파일 없음 — improveLayer()를 먼저 실행하거나 이미 처리됨.")
        if (!originalFile.exists()) throw IllegalStateException("원본 없음: ${originalFile.name}")

        val diff = computeDiff(originalFile, candidateFile)
        val layerRules = File(layerDir, "CLAUDE.md").takeIf { it.exists() }?.readText() ?: ""

        val reviewPrompt = """아래 레이어 후보의 품질을 리뷰하고 적용 여부를 판단하세요.

=== 레이어 개발 규칙 ===
$layerRules

=== DIFF (원본 → 후보) ===
$diff

판단 기준 (하나라도 해당하면 REJECT):
1. 격리 규칙 위반: KDoc·코드에 다른 레이어명(file-system, llm, git, build, shell, context, echo 등) 언급
2. 변경 없음: diff가 공백·주석만 다르고 실질적 개선 없음
3. 회귀: 기존 함수 시그니처·동작 변경, 컴파일 오류 가능성
4. 규칙 위반: @Layer, @LayerFunction, CommonLayerInterface 구조 훼손

모든 기준 통과 시 APPROVE.

반드시 다음 형식으로만 응답하세요 (다른 텍스트 없이):
APPROVE: (한 줄 이유)
또는
REJECT: (한 줄 이유)"""

        val llmClient = factory.createMediumClient()
        val review = try {
            llmClient.generateDirectAnswer(reviewPrompt).trim()
        } catch (e: Exception) {
            return "ERROR: 리뷰 LLM 호출 실패: ${e.message}"
        } finally {
            try { llmClient.close() } catch (_: Exception) {}
        }

        return when {
            review.startsWith("APPROVE") -> {
                val reason = review.removePrefix("APPROVE:").trim()
                val applyResult = applyLayerCandidate(normalized)
                "APPROVED ($reason)\n$applyResult"
            }
            review.startsWith("REJECT") -> {
                val reason = review.removePrefix("REJECT:").trim()
                rejectLayerCandidate(normalized)
                "REJECTED ($reason)\n원본 유지됨. 다른 레이어 또는 다른 목표로 재시도 가능."
            }
            else -> {
                // LLM이 형식을 안 지킨 경우 — 안전하게 거절
                rejectLayerCandidate(normalized)
                "REJECTED (리뷰 형식 불명확 — 안전을 위해 원본 유지)\nLLM 응답: ${review.take(200)}"
            }
        }
    }

    /**
     * 후보 레이어 파기 (원본 유지, 후보 파일 삭제).
     *
     * @param layerName 파기할 레이어 이름
     * @return "SUCCESS: ..." 또는 "ERROR: ..."
     */
    @LayerFunction
    suspend fun rejectLayerCandidate(layerName: String): String {
        val normalized = normalizeLayerName(layerName)
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
            val origLines = original.readLines().size
            val candLines = candidate.readLines().size
            "(diff 생성 불가 — 원본 ${origLines}줄 → 후보 ${candLines}줄)"
        }
    }

    /**
     * E1 후보 자가검토: SIMPLE LLM이 후보 코드를 독립적으로 검토.
     * 문제 없으면 null 반환. 문제 있으면 거절 사유 문자열 반환.
     *
     * 검사 항목:
     * 1. 레이어 격리: 타 레이어명·함수명 언급 여부
     * 2. 클래스 선언 불변: originalClassDecl 변경 여부
     * 3. @LayerFunction 시그니처 보존: 파라미터·반환타입 변경 여부
     * 4. 명백한 Kotlin 구문 오류 (미완성 블록 등)
     */
    private suspend fun reviewCandidateCode(
        factory: com.hana.orchestrator.llm.factory.LLMClientFactory,
        original: String,
        candidate: String,
        layerName: String,
        originalClassDecl: String
    ): String? {
        val originalSigs = original.lines()
            .filter { it.contains("@LayerFunction") || (it.trimStart().startsWith("suspend fun ") || it.trimStart().startsWith("fun ")) }
            .joinToString("\n") { it.trim() }

        val prompt = """아래 Kotlin 후보 코드를 검토하고 문제 여부를 판단하세요.

[원본 클래스 선언 (변경 금지)]
$originalClassDecl

[원본 @LayerFunction 시그니처 목록 (변경 금지)]
$originalSigs

[후보 코드]
${candidate.take(6000)}
${if (candidate.length > 6000) "...[이하 생략]" else ""}

[검사 기준]
1. 클래스 선언이 원본과 다르면 REJECT
2. @LayerFunction 붙은 함수의 이름·파라미터·반환타입이 변경되면 REJECT
3. 다른 레이어 이름(예: GitLayer, FileSystemLayer 등)을 코드나 KDoc에 직접 언급하면 REJECT
4. 명백히 닫히지 않은 중괄호·괄호가 있으면 REJECT

문제 없으면 정확히: OK
문제 있으면 정확히: REJECT: (한 줄 사유)"""

        val reviewClient = factory.createSimpleClient()
        return try {
            val response = reviewClient.generateDirectAnswer(prompt).trim()
            when {
                response.startsWith("OK") -> null
                response.startsWith("REJECT") -> response.removePrefix("REJECT:").trim().ifBlank { "검토 실패 (사유 불명)" }
                else -> null  // 불명확한 응답 → 통과 (false-positive 보다 false-negative 선호)
            }
        } catch (e: Exception) {
            null  // 검토 실패 → 통과 (검토 불가가 후보를 막아선 안 됨)
        } finally {
            try { reviewClient.close() } catch (_: Exception) {}
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
     * OLD/NEW 패치 블록을 파싱해 source에 순서대로 적용.
     * OLD가 하나라도 매칭 실패하면 null 반환.
     */
    private fun applyLinePatch(source: String, patchText: String): String? {
        val blockRegex = Regex("""REPLACE (\d+)-(\d+):\n(.*?)\nEND""", setOf(RegexOption.DOT_MATCHES_ALL))
        val blocks = blockRegex.findAll(patchText).toList()
        if (blocks.isEmpty()) return null

        // 내림차순 정렬: 뒤에서부터 교체해야 앞쪽 줄 번호가 유효하게 유지됨
        val sortedBlocks = blocks.sortedByDescending { it.groupValues[1].toInt() }

        val lines = source.lines().toMutableList()
        for (block in sortedBlocks) {
            val start = block.groupValues[1].toInt() - 1  // 0-based
            val end = block.groupValues[2].toInt()        // exclusive (1-based 끝줄 = 0-based exclusive)
            val newLines = block.groupValues[3].lines()
            if (start < 0 || end > lines.size || start >= end) return null
            repeat(end - start) { lines.removeAt(start) }
            lines.addAll(start, newLines)
        }
        return lines.joinToString("\n")
    }

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
        val normalized = normalizeLayerName(name)
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
            else -> throw IllegalArgumentException("Unknown function: ${'$'}function. Available: $functionList")
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
     * childFirstClassLoader(HotLoadUtils)를 사용해 부모 클래스로더 캐시 우회.
     * 재컴파일 후 reloadLayer 시에도 최신 버전이 로드됨.
     */
    private fun loadLayerInstance(normalized: String): CommonLayerInterface {
        val buildDir = File(projectRoot, "build/classes/kotlin/main")
        if (!buildDir.exists()) error("빌드 출력 디렉토리가 없습니다. 먼저 컴파일을 실행하세요.")
        val prefix = "com.hana.orchestrator.layer.${normalized}Layer"
        val classLoader = childFirstClassLoader(buildDir, prefix, this::class.java.classLoader)
        return classLoader.loadClass("com.hana.orchestrator.layer.${normalized}Layer")
            .getDeclaredConstructor().newInstance() as CommonLayerInterface
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
        val layerManager = layerManagerRef
            ?: throw IllegalStateException("LayerManager가 주입되지 않았습니다.")
        val normalized = normalizeLayerName(name)
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
        val layerManager = layerManagerRef
            ?: throw IllegalStateException("LayerManager가 주입되지 않았습니다.")
        val normalized = normalizeLayerName(name)
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
     * 레이어 코드 파일 저장 (내부용 — LLM에게 노출 안 됨)
     *
     * createLayer() 내부에서만 사용.
     */
    suspend fun writeLayerCode(name: String, code: String): String {
        return try {
            val normalized = normalizeLayerName(name)
            val file = File(layerDir, "${normalized}Layer.kt")

            if (file.exists()) {
                val backup = File(layerDir, "${normalized}Layer.kt.bak")
                file.copyTo(backup, overwrite = true)
            }

            layerDir.mkdirs()
            file.writeText(code)
            """SUCCESS: ${file.relativeTo(projectRoot).path} 저장 완료
다음 단계: build.compileKotlin() → develop.reloadLayer(name="$normalized")"""
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
            Regex("""name=([^,}\]]+)""").findAll(trimmed)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
                .ifEmpty {
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
     * 현재 레이어 소스 파일의 스냅샷을 저장하고 beta/alpha/rc 단계로 마킹
     *
     * @param name  레이어 이름 ("Layer" 접미사 생략 가능. 예: "Echo")
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
            ?: return "ERROR: '$name' 소스 파일을 찾을 수 없습니다. 레이어면 '${name}Layer.kt'를 확인하세요."
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
     * 이름으로 레이어 소스 파일 탐색 (레이어 디렉토리 → 프로젝트 전체 순)
     */
    private fun resolveSourceFile(name: String): File? {
        val normalized = normalizeLayerName(name)
        val layerFile = File(layerDir, "${normalized}Layer.kt")
        if (layerFile.exists()) return layerFile
        val plainFile = File(layerDir, "${name}.kt")
        if (plainFile.exists()) return plainFile
        return projectRoot.walkTopDown()
            .firstOrNull { it.isFile && (it.name == "${normalized}Layer.kt" || it.name == "$name.kt") }
    }

    private fun listLayerNames(): String =
        layerDir.listFiles { f -> f.name.endsWith("Layer.kt") && f.name != "DevelopLayer.kt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?.joinToString("\n")
            ?: ""

    /**
     * 함수별 승인 종류 분류
     * - READ_ONLY : 조회·후보 생성만 — 원본 미수정
     * - FILE      : 실제 소스 파일 쓰기 (apply/develop)
     * - EXECUTION : 런타임 변경 (hotLoad, reload 등)
     */
    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview {
        val kind = when (function) {
            "readLayerExample", "readLayerInterface", "readLayerFactory",
            "listLayers", "listCandidates", "listLayerCandidates",
            "improveLayer", "reviewLayerCandidate",
            "rejectLayerCandidate" -> ApprovalKind.READ_ONLY
            "applyLayerCandidate", "developLayer" -> ApprovalKind.FILE
            else -> ApprovalKind.EXECUTION
        }
        val preview = args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return ApprovalPreview(
            path = "develop.$function",
            oldContent = null,
            newContent = preview,
            kind = kind
        )
    }

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
                val context = args["context"] as? String ?: ""
                improveLayer(layerName, goal, context)
            }
            "reviewLayerCandidate" -> {
                val layerName = args["layerName"] as? String ?: ""
                reviewLayerCandidate(layerName)
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
            else -> throw IllegalArgumentException("Unknown function: $function. Available: readLayerExample, readLayerInterface, readLayerFactory, listLayers, createLayer, hotLoad, reloadLayer, promote, listCandidates, developLayer, improveLayer, reviewLayerCandidate, applyLayerCandidate, rejectLayerCandidate, listLayerCandidates")
        }
    }
}
