package com.hana.orchestrator.layer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git 레이어
 *
 * 목적: git 저장소 조회·관리. 상태 확인, 브랜치 정보, 커밋, 로그 조회 등 모든 git 작업.
 *
 * 사용 예시:
 * - "현재 브랜치 알려줘", "git 상태 확인해줘" → status(), currentBranch()
 * - "최근 커밋 보여줘", "변경 이력 확인" → log()
 * - "변경된 내용 diff 보여줘" → diff()
 * - 새 브랜치 생성, 체크아웃, 커밋, stash 저장/복원
 *
 * - 표준 git 명령만 사용 (git이 PATH에 있어야 함)
 * - 모든 작업은 projectRoot 기준으로 실행
 */
@Layer
class GitLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private val timeoutSeconds = 30L

    /** 현재 작업 디렉터리의 git 상태 확인 (변경된 파일 목록) */
    @LayerFunction
    suspend fun status(): String = runGit("status", "--short")

    /**
     * 변경 내용 diff 표시
     * @param path 특정 파일/디렉터리 경로 (비어있으면 전체)
     */
    @LayerFunction
    suspend fun diff(path: String = ""): String {
        val args = if (path.isBlank()) listOf("diff") else listOf("diff", "--", path)
        return runGit(*args.toTypedArray())
    }

    /**
     * 새 브랜치 생성 및 체크아웃
     * @param branchName 생성할 브랜치 이름 (예: "hana/fix-bug-001")
     */
    @LayerFunction
    suspend fun createBranch(branchName: String): String =
        runGit("checkout", "-b", branchName)

    /**
     * 현재 브랜치로 체크아웃
     * @param branch 체크아웃할 브랜치 또는 커밋 해시
     */
    @LayerFunction
    suspend fun checkout(branch: String): String =
        runGit("checkout", branch)

    /**
     * 모든 변경사항 스테이징 후 커밋
     * @param message 커밋 메시지
     */
    @LayerFunction
    suspend fun commit(message: String): String {
        val addResult = runGit("add", "-A")
        if (addResult.startsWith("ERROR")) return addResult
        return runGit("commit", "-m", message)
    }

    /**
     * 변경사항을 임시 저장소(stash)에 저장
     * @param message stash 설명 (비어있으면 기본 메시지 사용)
     */
    @LayerFunction
    suspend fun stash(message: String = ""): String {
        return if (message.isBlank()) runGit("stash", "push")
        else runGit("stash", "push", "-m", message)
    }

    /** stash에서 가장 최근 변경사항 복원 */
    @LayerFunction
    suspend fun stashPop(): String = runGit("stash", "pop")

    /** 현재 브랜치 이름 반환 */
    @LayerFunction
    suspend fun currentBranch(): String =
        runGit("rev-parse", "--abbrev-ref", "HEAD")

    /** 최근 커밋 로그 (기본 5개) */
    @LayerFunction
    suspend fun log(count: Int = 5): String =
        runGit("log", "--oneline", "-$count")

    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview {
        val kind = when (function) {
            "log", "diff", "status", "currentBranch" -> ApprovalKind.READ_ONLY
            else -> ApprovalKind.EXECUTION  // commit, push, createBranch, checkout, stash, stashPop
        }
        val preview = args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return ApprovalPreview(path = "git.$function", oldContent = null, newContent = preview, kind = kind)
    }

    private suspend fun runGit(vararg args: String): String = withContext(Dispatchers.IO) {
        val command = listOf("git") + args.toList()
        try {
            val process = ProcessBuilder(command)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext "ERROR: git 명령이 ${timeoutSeconds}초 내 완료되지 않았습니다."
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) "ERROR: exitCode=$exitCode\n$output"
            else if (output.isEmpty()) "SUCCESS: ${args.joinToString(" ")} 완료"
            else output
        } catch (e: Exception) {
            "ERROR: git 실행 실패: ${e.message}"
        }
    }

    override suspend fun describe(): LayerDescription {
        return GitLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "status" -> status()
            "diff" -> diff(args["path"] as? String ?: "")
            "createBranch" -> createBranch(args["branchName"] as? String ?: "")
            "checkout" -> checkout(args["branch"] as? String ?: "")
            "commit" -> commit(args["message"] as? String ?: "auto commit")
            "stash" -> stash(args["message"] as? String ?: "")
            "stashPop" -> stashPop()
            "currentBranch" -> currentBranch()
            "log" -> log((args["count"] as? String)?.toIntOrNull() ?: (args["count"] as? Int) ?: 5)
            else -> throw IllegalArgumentException("Unknown function: $function. Available: status, diff, createBranch, checkout, commit, stash, stashPop, currentBranch, log")
        }
    }
}
