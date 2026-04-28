package com.hana.orchestrator.layer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 빌드 레이어
 *
 * 목적: 코드 수정 후 컴파일·빌드 결과를 확인할 수 있게 함 (자가 코드 수정 검증용).
 *
 * - Gradle 전용: compileKotlin, build, clean (gradlew 사용)
 * - 그 외 툴: runBuild(executable, args) 로 임의 빌드 명령 실행 (Maven, npm, cargo 등)
 * 실행은 항상 projectRoot 를 작업 디렉터리로 하며, 실행 파일은 projectRoot 아래 또는 PATH 로만 허용합니다.
 * 빌드 툴 설치(npm, mvn, cargo, gradle wrapper 등)는 이 레이어 범위 밖이며, 필요 시 파일 시스템·스크립트 레이어나 사전 설정으로 처리합니다.
 */
@Layer
class BuildLayer(
    /** 빌드를 실행할 프로젝트 루트 디렉터리 (기본값: 현재 작업 디렉터리) */
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private val defaultTimeoutMinutes = 5L

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    private fun gradlewName(): String = if (isWindows) "gradlew.bat" else "gradlew"

    /**
     * Kotlin 소스를 컴파일합니다 (gradlew compileKotlin).
     *
     * 소스 파일(.kt)을 변경한 후, 그 변경이 런타임에 반영되려면 반드시 이 단계가 선행되어야 합니다.
     * 컴파일 성공 후에야 런타임 등록·교체가 가능합니다.
     */
    @LayerFunction
    suspend fun compileKotlin(): String = runGradle("compileKotlin")

    /** Gradle: 전체 빌드 (build) */
    @LayerFunction
    suspend fun build(): String = runGradle("build")

    /** Gradle: 빌드 산출물 삭제 (clean) */
    @LayerFunction
    suspend fun clean(): String = runGradle("clean")

    /**
     * 빌드 후 서버 재시작 (자기 개선 루프용)
     * build() 완료 후 호출하면 새 JAR로 프로세스를 교체함.
     * - shadow JAR(build/libs/ *-all.jar 패턴) 를 찾아 새 JVM 으로 기동
     * - 현재 프로세스는 포트 해제 후 즉시 종료 (새 프로세스가 동일 포트 재취득)
     * - rollbackBranch 지정 시 재시작 후 검증 실패하면 해당 브랜치로 자동 롤백
     *
     * @param rollbackBranch 검증 실패 시 롤백할 git 브랜치 (예: "main"). 빈 문자열이면 롤백 없음.
     */
    @LayerFunction
    suspend fun restart(rollbackBranch: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val jar = File(projectRoot, "build/libs")
                .listFiles { f -> f.name.endsWith("-all.jar") }
                ?.maxByOrNull { it.lastModified() }
                ?: return@withContext "ERROR: shadow JAR를 찾을 수 없습니다 (먼저 build 실행 필요)"

            val javaExe = ProcessHandle.current().info().command().orElse("java")

            // 재시작 전 pending 상태 기록 (재시작 후 복구 검증용)
            if (rollbackBranch.isNotBlank()) {
                val pendingFile = File(projectRoot, ".hana/pending.jsonl")
                pendingFile.parentFile.mkdirs()
                pendingFile.appendText(
                    """{"rollbackBranch":"$rollbackBranch","ts":${System.currentTimeMillis()}}""" + "\n"
                )
            }

            // 재시작 스크립트: 현재 프로세스 종료 후 2초 뒤 새 프로세스 시작
            val restartLog = File(projectRoot, ".hana/restart.log")
            restartLog.parentFile.mkdirs()
            val script = File(projectRoot, ".hana/restart.sh").apply {
                writeText("""
                    #!/bin/bash
                    sleep 2
                    exec "$javaExe" -jar "${jar.absolutePath}" --skip-cleanup >> "${restartLog.absolutePath}" 2>&1
                """.trimIndent())
                setExecutable(true)
            }

            // 백그라운드에서 스크립트 실행
            ProcessBuilder("bash", script.absolutePath)
                .directory(projectRoot)
                .redirectOutput(restartLog)
                .redirectError(restartLog)
                .start()

            // 300ms 후 현재 프로세스 종료 (포트 해제 → 새 프로세스가 포트 재취득)
            Thread {
                Thread.sleep(300)
                Runtime.getRuntime().halt(0)
            }.also { it.isDaemon = true }.start()

            "SUCCESS: 재시작 예약됨 (${jar.name}). 약 3초 후 서버가 다시 시작됩니다."
        } catch (e: Exception) {
            "ERROR: 재시작 실패: ${e.message}"
        }
    }

    /**
     * 임의 빌드 명령 실행 (Gradle 외 Maven, npm, cargo 등).
     *
     * @param executable 실행할 명령 (예: "gradlew", "mvn", "npm", "cargo"). 경로 포함 시 projectRoot 기준 상대 경로만 허용.
     * @param args 인자 목록 (예: ["compile"], ["run", "build"], ["build"])
     * @return 성공 시 "SUCCESS: ...", 실패 시 "ERROR: exitCode=...\n\nOutput:\n..."
     */
    @LayerFunction
    suspend fun runBuild(executable: String, args: List<String> = emptyList()): String =
        runCommand(executable, args, defaultTimeoutMinutes)

    private suspend fun runGradle(task: String, timeoutMinutes: Long = defaultTimeoutMinutes): String {
        val scriptName = gradlewName()
        var gradlew = File(projectRoot, scriptName)
        if (!gradlew.exists()) {
            val found = findGradleProjectRoot(projectRoot)
            if (found != null) gradlew = File(found, scriptName)
        }
        if (!gradlew.exists()) {
            return "ERROR: gradlew를 찾을 수 없습니다: ${gradlew.absolutePath}"
        }
        return runCommand(gradlew.absolutePath, listOf(task), timeoutMinutes)
    }

    /** user.dir이 build/run 등일 때 상위로 올라가며 gradlew가 있는 디렉터리 찾기 */
    private fun findGradleProjectRoot(from: File): File? {
        var dir = from.canonicalFile
        val scriptName = gradlewName()
        for (i in 0..10) {
            val script = File(dir, scriptName)
            if (script.exists()) return dir
            dir = dir.parentFile ?: break
        }
        return null
    }

    /**
     * projectRoot 에서 executable + args 실행.
     * executable 이 경로(/, \ 포함)이면 projectRoot 기준으로만 해석하고, 그 외는 PATH 에서 찾음.
     * "gradle"은 gradlew로 자동 치환 (시스템에 gradle 미설치 환경 대응).
     */
    private suspend fun runCommand(
        executable: String,
        args: List<String>,
        timeoutMinutes: Long
    ): String {
        val effectiveExecutable = if (executable == "gradle") {
            if (File(projectRoot, gradlewName()).exists()) gradlewName() else executable
        } else executable
        return withContext(Dispatchers.IO) {
            val (resolvedExecutable, workDir) = resolveExecutable(effectiveExecutable)
                ?: return@withContext "ERROR: 허용되지 않는 실행 경로입니다: $executable (projectRoot 내 또는 PATH만 허용)"

            val command = listOf(resolvedExecutable) + args
            val builder = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)

            try {
                val process = builder.start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

                if (!finished) {
                    process.destroyForcibly()
                    return@withContext "ERROR: 빌드가 ${timeoutMinutes}분 내에 완료되지 않아 중단되었습니다.\n\nOutput:\n${output.take(8000)}"
                }

                val exitValue = process.exitValue()
                if (exitValue != 0) {
                    return@withContext "ERROR: exitCode=$exitValue\n\nOutput:\n${output.take(12000)}"
                }

                "SUCCESS: ${command.joinToString(" ")} 완료\n\nOutput:\n${output.take(4000)}"
            } catch (e: Exception) {
                "ERROR: 실행 실패: ${e.message}"
            }
        }
    }

    /** executable 을 projectRoot 기준 경로 또는 PATH 명령으로 해석. (projectRoot 밖 절대경로만 금지) */
    private fun resolveExecutable(executable: String): Pair<String, File>? {
        val rootCanonical = projectRoot.canonicalFile
        if (executable.contains("/") || executable.contains("\\")) {
            val resolved = if (File(executable).isAbsolute) {
                File(executable).canonicalFile
            } else {
                File(projectRoot, executable).canonicalFile
            }
            // 절대경로가 projectRoot 밖이면 금지 (단, gradlew 절대경로는 그 부모를 작업 디렉터리로 허용)
            if (resolved.isFile && (resolved.name == "gradlew" || resolved.name == "gradlew.bat")) {
                return resolved.absolutePath to resolved.parentFile
            }
            if (!resolved.startsWith(rootCanonical)) return null
            return if (resolved.isFile || resolved.name.contains(".")) resolved.absolutePath to projectRoot
            else null
        }
        if (executable == "gradlew" || executable == "gradlew.bat") {
            val script = File(projectRoot, gradlewName())
            return if (script.exists()) script.canonicalPath to projectRoot else null
        }
        return executable to projectRoot
    }

    private fun File.startsWith(other: File): Boolean =
        canonicalPath == other.canonicalPath || canonicalPath.startsWith(other.canonicalPath + File.separator)

    override suspend fun describe(): LayerDescription {
        return BuildLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "compileKotlin" -> compileKotlin()
            "build" -> build()
            "clean" -> clean()
            "runBuild" -> {
                val exe = (args["executable"] as? String) ?: ""
                val raw = args["args"]
                val list = when (raw) {
                    is List<*> -> raw.mapNotNull { it?.toString() }
                    is String -> raw.split(" ").filter { it.isNotBlank() }
                    else -> emptyList()
                }
                runBuild(exe, list)
            }
            "restart" -> restart()
            else -> "Unknown function: $function. Available: compileKotlin, build, clean, runBuild, restart"
        }
    }
}
