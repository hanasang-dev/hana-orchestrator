package com.hana.orchestrator.layer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Git 레이어
 *
 * 목적: 자기 개선 루프의 안전망. 코드 수정 전 브랜치 생성, 실패 시 롤백, 성공 시 커밋.
 *
 * - 표준 git 명령만 사용 (git이 PATH에 있어야 함)
 * - 모든 작업은 projectRoot 기준으로 실행
 * - 자기 개선 트리 패턴: createBranch → (수정) → build → commit 또는 stash/checkout으로 롤백
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
            else -> "Unknown function: $function. Available: status, diff, createBranch, checkout, commit, stash, stashPop, currentBranch, log"
        }
    }
}
