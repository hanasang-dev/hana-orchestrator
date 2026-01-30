package com.hana.orchestrator.service

import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Docker Compose 관리자
 * SRP: Docker Compose 서비스 시작/종료만 담당
 */
class DockerComposeManager(
    private val composeFile: File = File("docker/docker-compose.yml"),
    private val timeoutSeconds: Long = 60
) {
    private val logger = createOrchestratorLogger(DockerComposeManager::class.java, null)
    
    /**
     * Docker Compose 서비스 시작
     * @param services 시작할 서비스 목록 (비어있으면 모든 서비스)
     * @return 성공 여부
     */
    suspend fun startServices(services: List<String> = emptyList(), requireDocker: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!ensureDockerAvailable(required = requireDocker, autoInstall = false)) {
                    if (requireDocker) {
                        logger.error("❌ Docker가 필수이지만 사용할 수 없습니다.")
                        return@withContext false
                    } else {
                        logger.warn("⚠️ Docker가 없지만 필수가 아니므로 Docker Compose를 건너뜁니다.")
                        return@withContext false
                    }
                }
                
                if (!composeFile.exists()) {
                    logger.warn("⚠️ docker-compose.yml 파일을 찾을 수 없습니다: ${composeFile.absolutePath}")
                    return@withContext false
                }
                
                val serviceArgs = if (services.isEmpty()) {
                    emptyList()
                } else {
                    listOf(*services.toTypedArray())
                }
                
                logger.info("🐳 Docker Compose 서비스 시작 중: ${if (services.isEmpty()) "모든 서비스" else services.joinToString(", ")}")
                
                val command = mutableListOf("docker", "compose", "-f", composeFile.absolutePath, "up", "-d")
                command.addAll(serviceArgs)
                
                val process = ProcessBuilder(command)
                    .directory(composeFile.parentFile)
                    .redirectErrorStream(true)
                    .start()
                
                val exitCode = withTimeout(timeoutSeconds * 1000) {
                    process.waitFor()
                }
                
                if (exitCode == 0) {
                    logger.info("✅ Docker Compose 서비스 시작 완료")
                    true
                } else {
                    logger.error("❌ Docker Compose 시작 실패 (exit code: $exitCode)")
                    false
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("❌ Docker Compose 시작 타임아웃 (${timeoutSeconds}초 초과)")
                false
            } catch (e: Exception) {
                logger.error("❌ Docker Compose 시작 실패: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Docker Compose 서비스 종료
     * @param services 종료할 서비스 목록 (비어있으면 모든 서비스)
     * @return 성공 여부
     */
    suspend fun stopServices(services: List<String> = emptyList()): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isDockerAvailable()) {
                    return@withContext false
                }
                
                if (!composeFile.exists()) {
                    return@withContext false
                }
                
                val serviceArgs = if (services.isEmpty()) {
                    emptyList()
                } else {
                    listOf(*services.toTypedArray())
                }
                
                logger.info("🛑 Docker Compose 서비스 종료 중: ${if (services.isEmpty()) "모든 서비스" else services.joinToString(", ")}")
                
                val command = mutableListOf("docker", "compose", "-f", composeFile.absolutePath, "down")
                if (services.isEmpty()) {
                    // 모든 서비스 종료 시 볼륨도 제거하지 않음 (데이터 보존)
                } else {
                    command.addAll(serviceArgs)
                }
                
                val process = ProcessBuilder(command)
                    .directory(composeFile.parentFile)
                    .redirectErrorStream(true)
                    .start()
                
                val exitCode = withTimeout(timeoutSeconds * 1000) {
                    process.waitFor()
                }
                
                if (exitCode == 0) {
                    logger.info("✅ Docker Compose 서비스 종료 완료")
                    true
                } else {
                    logger.error("❌ Docker Compose 종료 실패 (exit code: $exitCode)")
                    false
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("❌ Docker Compose 종료 타임아웃 (${timeoutSeconds}초 초과)")
                false
            } catch (e: Exception) {
                logger.error("❌ Docker Compose 종료 실패: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Docker가 사용 가능한지 확인 (public)
     */
    suspend fun isDockerAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start()
                val exitCode = withTimeout(5000) {
                    process.waitFor()
                }
                exitCode == 0
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Docker 설치 시도 (macOS: Homebrew, Linux: 배포판별)
     * @return 설치 성공 여부
     */
    suspend fun tryInstallDocker(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val os = System.getProperty("os.name").lowercase()
                val command = when {
                    os.contains("mac") -> {
                        // Homebrew로 Docker Desktop 설치
                        logger.info("🍺 Homebrew를 통해 Docker Desktop 설치 시도 중...")
                        listOf("brew", "install", "--cask", "docker")
                    }
                    os.contains("linux") -> {
                        // Linux 배포판 감지
                        val distro = try {
                            File("/etc/os-release").readText().lowercase()
                        } catch (e: Exception) {
                            ""
                        }
                        
                        when {
                            distro.contains("ubuntu") || distro.contains("debian") -> {
                                logger.info("🐧 Ubuntu/Debian용 Docker 설치 시도 중...")
                                listOf("sh", "-c", "curl -fsSL https://get.docker.com | sh")
                            }
                            distro.contains("fedora") || distro.contains("centos") || distro.contains("rhel") -> {
                                logger.info("🐧 Fedora/CentOS용 Docker 설치 시도 중...")
                                listOf("sh", "-c", "curl -fsSL https://get.docker.com | sh")
                            }
                            else -> {
                                logger.warn("⚠️ 자동 Docker 설치는 Ubuntu/Debian/Fedora/CentOS에서만 지원됩니다.")
                                return@withContext false
                            }
                        }
                    }
                    else -> {
                        logger.warn("⚠️ 자동 Docker 설치는 macOS/Linux에서만 지원됩니다.")
                        return@withContext false
                    }
                }
                
                logger.info("📦 Docker 설치 중... (시간이 걸릴 수 있습니다)")
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                
                // 설치 프로세스 출력을 로그로 표시
                val output = StringBuilder()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        logger.debug("   $line")
                    }
                }
                
                val exitCode = withTimeout(300_000) { // 5분 타임아웃
                    process.waitFor()
                }
                
                if (exitCode == 0) {
                    logger.info("✅ Docker 설치 완료")
                    true
                } else {
                    logger.error("❌ Docker 설치 실패 (exit code: $exitCode)")
                    logger.debug("출력: ${output.toString()}")
                    false
                }
            } catch (e: TimeoutCancellationException) {
                logger.error("❌ Docker 설치 타임아웃 (5분 초과)")
                false
            } catch (e: Exception) {
                logger.error("❌ Docker 설치 실패: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Docker Desktop 자동 실행 시도 (macOS/Windows)
     * @return 실행 성공 여부
     */
    suspend fun tryStartDockerDesktop(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val os = System.getProperty("os.name").lowercase()
                val command = when {
                    os.contains("mac") -> listOf("open", "-a", "Docker")
                    os.contains("win") -> listOf("cmd", "/c", "start", "Docker Desktop")
                    else -> {
                        logger.warn("⚠️ Docker Desktop 자동 실행은 macOS/Windows에서만 지원됩니다.")
                        return@withContext false
                    }
                }
                
                logger.info("🐳 Docker Desktop 실행 시도 중...")
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                
                // Docker Desktop이 시작될 때까지 대기 (최대 30초)
                var waited = 0
                while (waited < 30000) {
                    delay(2000)
                    if (isDockerAvailable()) {
                        logger.info("✅ Docker Desktop 실행 완료")
                        return@withContext true
                    }
                    waited += 2000
                }
                
                logger.warn("⚠️ Docker Desktop 실행 타임아웃 (30초 초과)")
                false
            } catch (e: Exception) {
                logger.error("❌ Docker Desktop 실행 실패: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Docker가 필수인지 확인하고, 없으면 설치/실행 시도
     * @param required Docker가 필수인지 여부
     * @param autoInstall 자동 설치 시도 여부
     * @return Docker 사용 가능 여부
     */
    suspend fun ensureDockerAvailable(required: Boolean = true, autoInstall: Boolean = true): Boolean {
        if (isDockerAvailable()) {
            return true
        }
        
        if (!required) {
            logger.warn("⚠️ Docker가 없지만 필수가 아니므로 계속 진행합니다.")
            return false
        }
        
        // Docker 명령어가 없는 경우 (설치되지 않음)
        val dockerCommandExists = try {
            val process = ProcessBuilder("which", "docker")
                .redirectErrorStream(true)
                .start()
            val exitCode = withTimeout(2000) {
                process.waitFor()
            }
            exitCode == 0
        } catch (e: Exception) {
            false
        }
        
        if (!dockerCommandExists && autoInstall) {
            logger.info("📦 Docker가 설치되어 있지 않습니다. 자동 설치 시도...")
            val installed = tryInstallDocker()
            if (!installed) {
                logger.error("""
                    |❌ Docker 자동 설치 실패
                    |   수동 설치 방법:
                    |   - macOS: brew install --cask docker
                    |   - Linux: curl -fsSL https://get.docker.com | sh
                    |   또는 --skip-docker 옵션으로 Docker 없이 실행하세요
                """.trimMargin())
                return false
            }
        }
        
        // Docker Desktop 실행 시도 (macOS/Windows)
        logger.info("🔍 Docker Desktop 자동 실행 시도...")
        val started = tryStartDockerDesktop()
        
        if (!started && !isDockerAvailable()) {
            logger.error("""
                |❌ Docker가 필수이지만 사용할 수 없습니다.
                |   해결 방법:
                |   1. Docker Desktop을 수동으로 실행하세요
                |   2. 또는 --skip-docker 옵션으로 Docker 없이 실행하세요 (Ollama 수동 실행 필요)
            """.trimMargin())
            return false
        }
        
        return isDockerAvailable()
    }
    
    /**
     * 특정 서비스가 실행 중인지 확인
     */
    suspend fun isServiceRunning(serviceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isDockerAvailable() || !composeFile.exists()) {
                    return@withContext false
                }
                
                val process = ProcessBuilder(
                    "docker", "compose", "-f", composeFile.absolutePath,
                    "ps", "-q", serviceName
                )
                    .directory(composeFile.parentFile)
                    .redirectErrorStream(true)
                    .start()
                
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = withTimeout(5000) {
                    process.waitFor()
                }
                
                // 경고 메시지 필터링 (docker compose의 warning 메시지 제거)
                val filteredOutput = output.lines()
                    .filterNot { it.contains("level=warning") || it.contains("time=") || it.trim().isEmpty() }
                    .joinToString("\n")
                    .trim()
                
                exitCode == 0 && filteredOutput.isNotEmpty()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Ollama 인스턴스가 준비될 때까지 대기
     * @param baseUrl Ollama 서버 URL (예: http://localhost:11434)
     * @param maxWaitSeconds 최대 대기 시간 (초)
     * @return 준비되었는지 여부
     */
    suspend fun waitForOllamaReady(baseUrl: String, maxWaitSeconds: Long = 30): Boolean {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val maxWaitMs = maxWaitSeconds * 1000
            
            logger.info("⏳ Ollama 인스턴스 준비 대기 중: $baseUrl")
            
            var serverReady = false
            var modelReady = false
            
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                try {
                    // 1. 서버가 시작되었는지 확인
                    if (!serverReady) {
                        val url = URL("$baseUrl/api/tags")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 2000
                        connection.readTimeout = 2000
                        connection.requestMethod = "GET"
                        
                        val responseCode = connection.responseCode
                        if (responseCode == 200) {
                            serverReady = true
                            logger.info("✅ Ollama 서버 시작 완료: $baseUrl")
                        }
                        connection.disconnect()
                    }
                    
                    // 2. 서버가 준비되었으면 모델이 로드되었는지 확인
                    if (serverReady && !modelReady) {
                        val url = URL("$baseUrl/api/tags")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 2000
                        connection.readTimeout = 5000
                        connection.requestMethod = "GET"
                        
                        if (connection.responseCode == 200) {
                            val response = connection.inputStream.bufferedReader().readText()
                            connection.disconnect()
                            
                            // 모델이 실제로 설치되어 있는지 확인 (빈 배열이 아닌지)
                            if (response.contains("\"models\"") && response.contains("\"name\"")) {
                                modelReady = true
                                logger.info("✅ Ollama 모델 로드 확인 완료: $baseUrl")
                                return@withContext true
                            }
                        } else {
                            connection.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    // 아직 준비되지 않음, 계속 대기
                }
                
                delay(1000) // 1초마다 체크
            }
            
            if (serverReady && !modelReady) {
                logger.warn("⚠️ Ollama 서버는 시작되었지만 모델이 로드되지 않았습니다: $baseUrl")
            } else {
                logger.warn("⚠️ Ollama 인스턴스 준비 타임아웃: $baseUrl (${maxWaitSeconds}초 초과)")
            }
            false
        }
    }
    
    /**
     * 모든 Ollama 인스턴스가 준비될 때까지 대기
     * @param baseUrls Ollama 서버 URL 목록
     * @param maxWaitSeconds 최대 대기 시간 (초)
     * @return 모든 인스턴스가 준비되었는지 여부
     */
    suspend fun waitForAllOllamaInstances(baseUrls: List<String>, maxWaitSeconds: Long = 60): Boolean {
        logger.info("⏳ ${baseUrls.size}개 Ollama 인스턴스 준비 대기 중...")
        
        val results = coroutineScope {
            baseUrls.map { baseUrl ->
                async {
                    waitForOllamaReady(baseUrl, maxWaitSeconds)
                }
            }.awaitAll()
        }
        
        val allReady = results.all { it }
        
        if (allReady) {
            logger.info("✅ 모든 Ollama 인스턴스 준비 완료")
        } else {
            logger.warn("⚠️ 일부 Ollama 인스턴스가 준비되지 않았습니다")
        }
        
        return allReady
    }
    
    /**
     * Ollama 모델 워밍업 (모델을 메모리에 미리 로드)
     * @param baseUrl Ollama 서버 URL
     * @param modelId 모델 ID (예: "smollm2:1.7b")
     * @param timeoutSeconds 타임아웃 (초)
     * @return 워밍업 성공 여부
     */
    suspend fun warmUpModel(baseUrl: String, modelId: String, timeoutSeconds: Long = 60): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                logger.info("🔥 모델 워밍업 시작: $modelId @ $baseUrl")
                
                val url = URL("$baseUrl/api/generate")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = (timeoutSeconds * 1000).toInt()
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                // 간단한 프롬프트로 모델 워밍업 (빠른 응답을 위해 짧은 프롬프트)
                // num_predict를 1로 설정하여 최소한의 토큰만 생성
                val warmupRequest = """
                    {
                        "model": "$modelId",
                        "prompt": "Hi",
                        "stream": false,
                        "options": {
                            "num_predict": 1,
                            "temperature": 0.1
                        }
                    }
                """.trimIndent()
                
                connection.outputStream.use { os ->
                    os.write(warmupRequest.toByteArray())
                }
                
                val responseCode = connection.responseCode
                connection.disconnect()
                
                if (responseCode == 200) {
                    logger.info("✅ 모델 워밍업 완료: $modelId @ $baseUrl")
                    true
                } else {
                    // 500 에러는 모델이 아직 로드 중일 수 있으므로 경고만 출력
                    if (responseCode == 500) {
                        logger.warn("⚠️ 모델 워밍업 실패 (모델 로드 중일 수 있음): $modelId @ $baseUrl (응답 코드: $responseCode)")
                    } else {
                        logger.warn("⚠️ 모델 워밍업 실패: $modelId @ $baseUrl (응답 코드: $responseCode)")
                    }
                    false
                }
            } catch (e: Exception) {
                logger.warn("⚠️ 모델 워밍업 실패: $modelId @ $baseUrl (${e.message})")
                false
            }
        }
    }
    
    /**
     * 모든 모델 워밍업 (병렬 실행)
     * @param modelConfigs 모델 설정 목록 (baseUrl, modelId 쌍)
     * @param timeoutSeconds 각 모델 워밍업 타임아웃 (초)
     */
    suspend fun warmUpAllModels(
        modelConfigs: List<Pair<String, String>>,
        timeoutSeconds: Long = 60
    ) {
        logger.info("🔥 ${modelConfigs.size}개 모델 워밍업 시작...")
        
        val results = coroutineScope {
            modelConfigs.map { (baseUrl, modelId) ->
                async {
                    warmUpModel(baseUrl, modelId, timeoutSeconds)
                }
            }.awaitAll()
        }
        
        val successCount = results.count { it }
        if (successCount == modelConfigs.size) {
            logger.info("✅ 모든 모델 워밍업 완료")
        } else {
            logger.warn("⚠️ 일부 모델 워밍업 실패: $successCount/${modelConfigs.size} 성공")
        }
    }
}
