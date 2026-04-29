package com.hana.orchestrator.layer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 셸 명령 실행 레이어
 *
 * 목적: 현재 머신의 셸에서 명령 및 스크립트를 실행하고 stdout/stderr를 반환.
 * - macOS/Linux: `sh -c <command>`
 * - Windows: `cmd /c <command>`
 * - 타임아웃(기본 30초) 초과 시 프로세스 강제 종료
 * - 출력 5000자 초과 시 잘라냄
 * - exit code != 0 이면 `ERROR(exit=N):` 접두어로 반환
 */
@Layer
class ShellLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val maxOutputChars = 5000

    /**
     * 단일 셸 명령을 실행하고 stdout + stderr를 반환합니다.
     * @param command 실행할 명령 (예: "ls -la", "npm run build", "python3 script.py")
     * @param workDir 작업 디렉터리 경로 (비어있으면 프로젝트 루트). 상대 경로는 프로젝트 루트 기준.
     * @param timeoutSeconds 최대 실행 시간(초). 기본 30초. 초과 시 강제 종료 후 ERROR 반환.
     */
    @LayerFunction
    suspend fun run(
        command: String,
        workDir: String = "",
        timeoutSeconds: Int = 30
    ): String {
        val cmd = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        return runProcess(cmd, workDir, timeoutSeconds.toLong())
    }

    /**
     * 여러 줄 셸 스크립트를 임시 파일로 저장 후 실행합니다.
     * @param script 실행할 스크립트 내용 (여러 줄 가능)
     * @param workDir 작업 디렉터리 경로 (비어있으면 프로젝트 루트)
     * @param timeoutSeconds 최대 실행 시간(초). 기본 60초.
     */
    @LayerFunction
    suspend fun executeScript(
        script: String,
        workDir: String = "",
        timeoutSeconds: Int = 60
    ): String = withContext(Dispatchers.IO) {
        val ext = if (isWindows) ".bat" else ".sh"
        val tmpFile = File.createTempFile("hana-shell-", ext)
        try {
            tmpFile.writeText(script)
            if (!isWindows) tmpFile.setExecutable(true)
            val cmd = if (isWindows) listOf("cmd", "/c", tmpFile.absolutePath)
                      else listOf("sh", tmpFile.absolutePath)
            runProcess(cmd, workDir, timeoutSeconds.toLong())
        } finally {
            tmpFile.delete()
        }
    }

    private suspend fun runProcess(cmd: List<String>, workDir: String, timeoutSeconds: Long): String =
        withContext(Dispatchers.IO) {
            val dir = resolveWorkDir(workDir)
            try {
                val process = ProcessBuilder(cmd)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

                if (!finished) {
                    process.destroyForcibly()
                    return@withContext "ERROR: 명령이 ${timeoutSeconds}초 내 완료되지 않았습니다. (강제 종료)"
                }

                val exitCode = process.exitValue()
                val trimmed = output.trimEnd()
                val truncated = if (trimmed.length > maxOutputChars)
                    trimmed.take(maxOutputChars) + "\n...(출력 잘림: ${trimmed.length}자 중 ${maxOutputChars}자 표시)"
                else trimmed

                when {
                    exitCode != 0 -> "ERROR(exit=$exitCode):\n$truncated"
                    truncated.isBlank() -> "SUCCESS: 명령 완료 (출력 없음)"
                    else -> truncated
                }
            } catch (e: Exception) {
                "ERROR: 실행 실패 — ${e.message}"
            }
        }

    private fun resolveWorkDir(workDir: String): File {
        if (workDir.isBlank()) return projectRoot
        val f = File(workDir)
        return if (f.isAbsolute) f else File(projectRoot, workDir)
    }

    override suspend fun describe(): LayerDescription {
        return ShellLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "run" -> {
                val command = args["command"] as? String
                    ?: return "ERROR: 'command' 파라미터가 필요합니다."
                run(
                    command = command,
                    workDir = args["workDir"] as? String ?: "",
                    timeoutSeconds = (args["timeoutSeconds"] as? String)?.toIntOrNull()
                        ?: (args["timeoutSeconds"] as? Int) ?: 30
                )
            }
            "executeScript" -> {
                val script = args["script"] as? String
                    ?: return "ERROR: 'script' 파라미터가 필요합니다."
                executeScript(
                    script = script,
                    workDir = args["workDir"] as? String ?: "",
                    timeoutSeconds = (args["timeoutSeconds"] as? String)?.toIntOrNull()
                        ?: (args["timeoutSeconds"] as? Int) ?: 60
                )
            }
            else -> "Unknown function: $function. Available: run, executeScript"
        }
    }
}
