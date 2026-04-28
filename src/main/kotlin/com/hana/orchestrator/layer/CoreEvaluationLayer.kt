package com.hana.orchestrator.layer

import com.hana.orchestrator.orchestrator.CandidateRegistry
import java.io.File

/**
 * 코어 평가 레이어 — "시스템 콜" 역할
 *
 * rc 단계에 도달한 후보들을 평가하고 최종 선택을 위한 보고서를 생성.
 * 선택된 rc 후보를 현재 소스에 반영하는 기능도 제공.
 *
 * 워크플로우:
 * 1. develop.promote(name, stage="rc") → rc 등록
 * 2. listRcCandidates() → rc 목록 확인
 * 3. evaluateReport(name) → 후보 비교 보고서 생성
 * 4. (선택) applyCandidate(name, snapshotFile) → rc를 현재 소스에 반영
 * 5. 컴파일 확인 (소스 변경이 런타임에 반영되려면 컴파일이 선행되어야 함)
 * 6. 런타임 반영 (컴파일 성공 후 레이어를 교체해야 변경이 적용됨)
 */
@Layer
class CoreEvaluationLayer : CommonLayerInterface {

    private val projectRoot: File = File(System.getProperty("user.dir"))

    private var reactiveExecutorRef: com.hana.orchestrator.orchestrator.core.ReactiveExecutor? = null
    private val scenarioResults = mutableListOf<ScenarioResult>()

    /** ReactiveExecutor 참조 설정 (LayerManager에서 주입) */
    fun setReactiveExecutor(executor: com.hana.orchestrator.orchestrator.core.ReactiveExecutor) {
        reactiveExecutorRef = executor
    }

    private data class ScenarioResult(
        val label: String,
        val query: String,
        val stepCount: Int,
        val durationMs: Long,
        val resultSummary: String,
        val error: String?,
        val successfulFunctions: List<String>,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 현재 활성 전략으로 시나리오를 실행하고 결과를 기록합니다.
     * 전략을 교체한 뒤 동일한 쿼리로 다시 호출하면 비교용 데이터가 누적됩니다.
     *
     * @param query 실행할 시나리오 쿼리
     * @param label 결과를 구분할 레이블 (예: "baseline", "candidate-v2"). 생략 시 시간으로 자동 부여.
     */
    @LayerFunction
    suspend fun runScenario(query: String, label: String = ""): String {
        val executor = reactiveExecutorRef ?: return "ERROR: ReactiveExecutor가 주입되지 않았습니다."
        val effectiveLabel = label.ifBlank { java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()) }

        val executionId = java.util.UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        val result = try {
            executor.execute(query, executionId, startTime)
        } catch (e: Exception) {
            return "ERROR: 시나리오 실행 실패: ${e.message}"
        }

        val durationMs = System.currentTimeMillis() - startTime
        val functions = result.stepHistory.flatMap { it.successfulFunctions }.distinct()
        val scenarioResult = ScenarioResult(
            label = effectiveLabel,
            query = query,
            stepCount = result.stepHistory.size,
            durationMs = durationMs,
            resultSummary = result.result.take(200),
            error = result.error,
            successfulFunctions = functions
        )
        synchronized(scenarioResults) { scenarioResults.add(scenarioResult) }

        return buildString {
            append("📊 시나리오 결과 [$effectiveLabel]\n")
            append("쿼리: $query\n")
            append("스텝 수: ${scenarioResult.stepCount}\n")
            append("소요 시간: ${durationMs}ms\n")
            append("실행 함수: ${functions.joinToString(", ").ifBlank { "없음" }}\n")
            if (result.error != null) append("❌ 에러: ${result.error}\n")
            else append("✅ 결과: ${result.result.take(200)}\n")
            append("\n저장됨 — compareScenarioResults()로 비교 가능.")
        }
    }

    /**
     * 저장된 시나리오 결과 목록을 조회합니다.
     */
    @LayerFunction
    suspend fun listScenarioResults(): String {
        val results = synchronized(scenarioResults) { scenarioResults.toList() }
        if (results.isEmpty()) return "저장된 시나리오 결과가 없습니다. runScenario()로 먼저 실행하세요."
        return "📋 시나리오 결과 (${results.size}건):\n\n" + results.mapIndexed { i, r ->
            val date = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(r.createdAt))
            val status = if (r.error != null) "❌" else "✅"
            "$status #${i + 1} [${r.label}] ($date)  스텝: ${r.stepCount}  시간: ${r.durationMs}ms\n" +
                "   함수: ${r.successfulFunctions.joinToString(", ").ifBlank { "없음" }}"
        }.joinToString("\n")
    }

    /**
     * 저장된 두 시나리오 결과를 나란히 비교합니다.
     * 스텝 수·소요 시간·실행 함수·결과를 항목별로 비교해 판정합니다.
     *
     * @param labelA 비교할 첫 번째 레이블
     * @param labelB 비교할 두 번째 레이블
     */
    @LayerFunction
    suspend fun compareScenarioResults(labelA: String, labelB: String): String {
        val results = synchronized(scenarioResults) { scenarioResults.toList() }
        val a = results.lastOrNull { it.label == labelA }
            ?: return "ERROR: '$labelA' 레이블의 결과를 찾을 수 없습니다."
        val b = results.lastOrNull { it.label == labelB }
            ?: return "ERROR: '$labelB' 레이블의 결과를 찾을 수 없습니다."

        return buildString {
            append("# ⚡ 시나리오 비교: [$labelA] vs [$labelB]\n\n")
            append("| 항목 | $labelA | $labelB | 판정 |\n")
            append("|------|---------|---------|------|\n")
            append("| 스텝 수 | ${a.stepCount} | ${b.stepCount} | 🏆 ${if (a.stepCount <= b.stepCount) labelA else labelB} |\n")
            append("| 소요 시간 | ${a.durationMs}ms | ${b.durationMs}ms | 🏆 ${if (a.durationMs <= b.durationMs) labelA else labelB} |\n")
            append("| 성공 여부 | ${if (a.error == null) "✅" else "❌"} | ${if (b.error == null) "✅" else "❌"} | - |\n\n")
            append("**[$labelA] 실행 함수**: ${a.successfulFunctions.joinToString(", ").ifBlank { "없음" }}\n")
            append("**[$labelB] 실행 함수**: ${b.successfulFunctions.joinToString(", ").ifBlank { "없음" }}\n\n")
            if (a.resultSummary.isNotBlank()) append("**[$labelA] 결과**: ${a.resultSummary}\n")
            if (b.resultSummary.isNotBlank()) append("**[$labelB] 결과**: ${b.resultSummary}\n")
        }.trim()
    }

    /**
     * rc 단계 후보 목록 조회
     *
     * @return 이름 / 스냅샷 파일 / 설명 형식의 목록
     */
    @LayerFunction
    suspend fun listRcCandidates(): String {
        val candidates = CandidateRegistry.list().filter { it.stage == "rc" }
        if (candidates.isEmpty()) return "등록된 rc 후보가 없습니다."
        return "🏁 rc 후보 목록 (${candidates.size}건):\n\n" + candidates.joinToString("\n") { entry ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(entry.createdAt))
            "• ${entry.name}  ($date)\n  파일: ${entry.snapshotFile}${if (entry.description.isNotBlank()) "\n  설명: ${entry.description}" else ""}"
        }
    }

    /**
     * 특정 대상의 모든 후보(beta/alpha/rc)를 비교한 평가 보고서 생성
     *
     * 각 스냅샷 파일의 라인 수, 주요 함수 목록, 최신 rc와의 diff 요약을 출력.
     *
     * @param name 평가할 대상 이름 (예: "DefaultReActStrategy", "GreeterLayer")
     * @return 마크다운 형식의 평가 보고서
     */
    @LayerFunction
    suspend fun evaluateReport(name: String): String {
        val candidates = CandidateRegistry.list(name)
        if (candidates.isEmpty()) return "ERROR: '$name' 에 등록된 후보가 없습니다. develop.promote()로 먼저 등록하세요."

        val report = StringBuilder()
        report.append("# 📋 평가 보고서: $name\n\n")
        report.append("후보 ${candidates.size}건 (${candidates.map { it.stage }.distinct().joinToString(", ")})\n\n")

        val rcCandidates = candidates.filter { it.stage == "rc" }
        val latestRc = rcCandidates.lastOrNull()

        candidates.forEachIndexed { idx, entry ->
            val file = File(entry.snapshotFile)
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(entry.createdAt))
            report.append("## [${entry.stage.uppercase()}] #${idx + 1}  ($date)\n")
            report.append("파일: `${entry.snapshotFile}`\n")
            if (entry.description.isNotBlank()) report.append("설명: ${entry.description}\n")

            if (!file.exists()) {
                report.append("⚠️ 스냅샷 파일 없음\n\n")
                return@forEachIndexed
            }

            val lines = file.readLines()
            report.append("라인 수: ${lines.size}\n")

            // 함수 목록 추출
            val functions = lines.filter { it.trimStart().startsWith("fun ") || it.trimStart().startsWith("suspend fun ") }
                .map { it.trim().removePrefix("suspend ").removePrefix("fun ").substringBefore("(") }
                .filter { it.isNotBlank() }
            if (functions.isNotEmpty()) report.append("함수: ${functions.joinToString(", ")}\n")

            // 최신 rc와 diff (rc가 2개 이상일 때 직전 rc와 비교)
            if (entry.stage == "rc" && latestRc != null && entry != latestRc) {
                val prevFile = File(entry.snapshotFile)
                val latestFile = File(latestRc.snapshotFile)
                if (prevFile.exists() && latestFile.exists()) {
                    val diff = simpleDiff(prevFile.readText(), latestFile.readText())
                    if (diff.isNotBlank()) report.append("최신 rc와의 차이:\n```\n$diff\n```\n")
                }
            }
            report.append("\n")
        }

        if (rcCandidates.size > 1) {
            report.append("---\n⚡ 최신 rc: `${latestRc?.snapshotFile}`\n")
            report.append("적용 명령: coreEval.applyCandidate(name=\"$name\", snapshotFile=\"${latestRc?.snapshotFile}\")\n")
        } else if (latestRc != null) {
            report.append("---\n✅ rc 후보 1건 확인됨\n")
            report.append("적용 명령: coreEval.applyCandidate(name=\"$name\", snapshotFile=\"${latestRc.snapshotFile}\")\n")
        }

        return report.toString().trim()
    }

    /**
     * 선택한 스냅샷을 현재 소스 파일에 반영
     *
     * 적용 후 컴파일 확인 → 런타임 레이어 교체 순으로 진행하세요.
     *
     * @param name         대상 이름 (예: "DefaultReActStrategy", "GreeterLayer")
     * @param snapshotFile 반영할 스냅샷 파일 경로 (.hana/candidates/... 경로)
     * @return 성공 메시지 또는 에러
     */
    @LayerFunction
    suspend fun applyCandidate(name: String, snapshotFile: String): String {
        val snapshot = File(snapshotFile)
        if (!snapshot.exists()) return "ERROR: 스냅샷 파일이 없습니다: $snapshotFile"

        val targetFile = findSourceFile(name)
            ?: return "ERROR: '$name' 소스 파일을 찾을 수 없습니다. 레이어면 '${name}Layer.kt', 전략이면 '${name}.kt'를 확인하세요."

        // 현재 소스를 백업
        val backupDir = File(".hana/backups")
        backupDir.mkdirs()
        val backup = File(backupDir, "${System.currentTimeMillis()}_${targetFile.name}")
        targetFile.copyTo(backup, overwrite = true)

        // 스냅샷을 소스에 반영
        snapshot.copyTo(targetFile, overwrite = true)

        val normalized = name.removeSuffix("Layer")
        return """SUCCESS: '$name' rc 후보 적용 완료
원본 백업: ${backup.path}
적용 파일: ${targetFile.path}

[필수후속] 소스 반영 완료 — 컴파일 후 레이어 교체 필수. finish 불가."""
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private fun findSourceFile(name: String): File? {
        val normalized = name.removeSuffix("Layer")
        val layerDir = File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer")
        val layerFile = File(layerDir, "${normalized}Layer.kt")
        if (layerFile.exists()) return layerFile
        val plainFile = File(layerDir, "$name.kt")
        if (plainFile.exists()) return plainFile
        return projectRoot.walkTopDown()
            .firstOrNull { it.isFile && (it.name == "${normalized}Layer.kt" || it.name == "$name.kt") }
    }

    /**
     * 두 텍스트의 간단한 줄 단위 diff (추가/삭제 라인만 표시, 최대 30줄)
     */
    private fun simpleDiff(before: String, after: String): String {
        val beforeLines = before.lines()
        val afterLines = after.lines()
        val removed = beforeLines.filter { it !in afterLines }.take(15)
        val added = afterLines.filter { it !in beforeLines }.take(15)
        val result = StringBuilder()
        removed.forEach { result.append("- $it\n") }
        added.forEach { result.append("+ $it\n") }
        return result.toString().trim()
    }

    override suspend fun describe(): LayerDescription {
        return CoreEvaluationLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "runScenario" -> {
                val query = args["query"] as? String ?: return "ERROR: query 필수"
                val label = args["label"] as? String ?: ""
                runScenario(query, label)
            }
            "listScenarioResults" -> listScenarioResults()
            "compareScenarioResults" -> {
                val labelA = args["labelA"] as? String ?: return "ERROR: labelA 필수"
                val labelB = args["labelB"] as? String ?: return "ERROR: labelB 필수"
                compareScenarioResults(labelA, labelB)
            }
            "listRcCandidates" -> listRcCandidates()
            "evaluateReport" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                evaluateReport(name)
            }
            "applyCandidate" -> {
                val name = args["name"] as? String ?: return "ERROR: name 필수"
                val snapshotFile = args["snapshotFile"] as? String ?: return "ERROR: snapshotFile 필수"
                applyCandidate(name, snapshotFile)
            }
            else -> "Unknown function: $function. Available: runScenario, listScenarioResults, compareScenarioResults, listRcCandidates, evaluateReport, applyCandidate"
        }
    }
}
