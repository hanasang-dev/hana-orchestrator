package com.hana.orchestrator.layer

import java.io.File

/**
 * 파일 시스템 레이어
 *
 * 목적: 소스 코드·설정 등 파일을 읽고, 내용을 수정·추가할 수 있는 기능 제공.
 * (예: 변수·코드 추가, 기존 라인 수정, 새 파일 쓰기. readFile → 내용 생성/변경 → writeFile)
 *
 * 실행 트리의 노드로 사용 가능하여 반복적으로 사용할 수 있습니다.
 * 예: 파일 읽기 → 분석 → 수정 → 다시 읽기 → 검증
 */
@Layer
class FileSystemLayer : CommonLayerInterface {
    
    private val backupDir = File(".hana/backups")
    
    init {
        backupDir.mkdirs()
    }
    
    /**
     * 파일 읽기
     * 
     * @param path 읽을 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "test_file.txt", "src/main/kotlin/App.kt", "./config.json"
     *   - 잘못된 예: "/test_file.txt", "/path/to/file.txt" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 파일 내용
     */
    @LayerFunction
    suspend fun readFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return "ERROR: 파일이 존재하지 않습니다: $path"
            }
            if (!file.isFile) {
                return "ERROR: 파일이 아닙니다: $path"
            }
            file.readText()
        } catch (e: Exception) {
            "ERROR: 파일 읽기 실패: ${e.message}"
        }
    }
    
    /**
     * 디렉토리 목록 조회
     * 
     * @param path 조회할 디렉토리 경로 (기본값: ".", 상대 경로 사용 필수)
     *   - 올바른 예: ".", "src", "src/main", "./docs"
     *   - 잘못된 예: "/", "/src", "/current/directory" (절대 경로 사용 금지)
     *   - "현재 디렉토리"는 "."로 표현하세요
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 디렉토리 내 파일/폴더 목록 (줄바꿈으로 구분)
     */
    @LayerFunction
    suspend fun listDirectory(path: String = "."): String {
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return "ERROR: 디렉토리가 존재하지 않습니다: $path"
            }
            if (!dir.isDirectory) {
                return "ERROR: 디렉토리가 아닙니다: $path"
            }
            dir.listFiles()?.map { 
                val type = if (it.isDirectory) "[DIR]" else "[FILE]"
                "$type ${it.name}"
            }?.joinToString("\n") ?: "ERROR: 디렉토리 읽기 실패"
        } catch (e: Exception) {
            "ERROR: 디렉토리 목록 조회 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 쓰기 (자동 백업 포함)
     * 
     * @param path 쓸 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "test_file.txt", "src/main/kotlin/App.kt", "./output.txt"
     *   - 잘못된 예: "/test_file.txt", "/path/to/file.txt" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @param content 파일 내용
     * @return 실행 결과 메시지
     */
    @LayerFunction
    suspend fun writeFile(path: String, content: String): String {
        return try {
            // 1. 보호된 파일 확인
            if (!validateChanges(path, content)) {
                return "ERROR: 파일 수정 실패 - 보호된 파일입니다: $path"
            }
            
            val file = File(path)
            // 2. 기존 파일이 있을 때만 백업 (신규 파일이면 백업 생략 → 결과에 ERROR 안 넣어서 평가 LLM이 성공으로 인식)
            val backupPath = if (file.exists()) backupFile(path) else "신규 파일"
            
            // 3. 파일 쓰기
            file.parentFile?.mkdirs()
            file.writeText(content)
            
            "SUCCESS: 파일 수정 완료\n경로: $path\n백업: $backupPath"
        } catch (e: Exception) {
            "ERROR: 파일 쓰기 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 백업
     * 
     * @param path 백업할 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "test_file.txt", "src/main/kotlin/App.kt", "./config.json"
     *   - 잘못된 예: "/test_file.txt", "/path/to/file.txt" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 백업 파일 경로
     */
    @LayerFunction
    suspend fun backupFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) {
                return "ERROR: 백업할 파일이 존재하지 않습니다: $path"
            }
            
            val timestamp = System.currentTimeMillis()
            val fileName = file.name
            val backupPath = backupDir.resolve("${timestamp}_$fileName")
            file.copyTo(backupPath, overwrite = true)
            
            backupPath.absolutePath
        } catch (e: Exception) {
            "ERROR: 백업 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 검색 (glob 패턴 지원)
     * 
     * @param pattern 검색 패턴 (예: "*.kt", "*.txt")
     * @param rootPath 검색 시작 경로 (기본값: ".", 상대 경로 사용 필수)
     *   - 올바른 예: ".", "src", "src/main", "./docs"
     *   - 잘못된 예: "/", "/src", "/path/to/dir" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 찾은 파일 경로 목록 (줄바꿈으로 구분)
     */
    @LayerFunction
    suspend fun findFiles(pattern: String, rootPath: String = "."): String {
        return try {
            val root = File(rootPath)
            if (!root.exists()) {
                return "ERROR: 검색 경로가 존재하지 않습니다: $rootPath"
            }
            
            // 간단한 glob 패턴 매칭 (예: "*.kt" -> ".*\\.kt")
            val regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            
            val regex = Regex(regexPattern)
            
            val files = root.walkTopDown()
                .filter { it.isFile && it.name.matches(regex) }
                .map { it.absolutePath }
                .toList()
            
            if (files.isEmpty()) {
                "INFO: 패턴 '$pattern'에 맞는 파일을 찾지 못했습니다"
            } else {
                files.joinToString("\n")
            }
        } catch (e: Exception) {
            "ERROR: 파일 검색 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 삭제
     * 
     * @param path 삭제할 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "test_file.txt", "src/main/kotlin/App.kt", "./temp.txt"
     *   - 잘못된 예: "/test_file.txt", "/path/to/file.txt" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 실행 결과 메시지
     */
    @LayerFunction
    suspend fun deleteFile(path: String): String {
        return try {
            checkFileExistsAndNotProtected(path, "")?.let { return it }
            val file = File(path)
            val backupPath = backupFile(path)
            val deleted = file.delete()
            if (deleted) {
                "SUCCESS: 파일 삭제 완료\n경로: $path\n백업: $backupPath"
            } else {
                "ERROR: 파일 삭제 실패: $path"
            }
        } catch (e: Exception) {
            "ERROR: 파일 삭제 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 이동/이름 변경
     * 
     * @param sourcePath 원본 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "old_file.txt", "src/main/kotlin/OldApp.kt"
     *   - 잘못된 예: "/old_file.txt" (절대 경로 사용 금지)
     * @param targetPath 대상 파일 경로 (상대 경로 사용 필수)
     *   - 올바른 예: "new_file.txt", "src/main/kotlin/NewApp.kt"
     *   - 잘못된 예: "/new_file.txt" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 실행 결과 메시지
     */
    @LayerFunction
    suspend fun moveFile(sourcePath: String, targetPath: String): String {
        return try {
            checkFileExistsAndNotProtected(
                sourcePath, "",
                existsLabel = "원본 파일",
                notFileLabel = "원본"
            )?.let { return "ERROR: 파일 이동 실패 - ${it.removePrefix("ERROR: ")}" }
            val sourceFile = File(sourcePath)
            val targetFile = File(targetPath)
            if (targetFile.exists() && !validateChanges(targetPath, "")) {
                return "ERROR: 파일 이동 실패 - 대상 경로가 보호된 파일입니다: $targetPath"
            }
            val backupPath = backupFile(sourcePath)
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()
            "SUCCESS: 파일 이동 완료\n원본: $sourcePath\n대상: $targetPath\n백업: $backupPath"
        } catch (e: Exception) {
            "ERROR: 파일 이동 실패: ${e.message}"
        }
    }
    
    /**
     * path가 존재하는 파일인지 검사. 보호된 파일이면 실패.
     * @param existsLabel 존재하지 않을 때 메시지용 (예: "파일", "원본 파일")
     * @param notFileLabel 파일이 아닐 때 메시지용 (예: "파일", "원본")
     * @return null이면 통과, 아니면 반환할 에러 메시지
     */
    private suspend fun checkFileExistsAndNotProtected(
        path: String,
        content: String = "",
        existsLabel: String = "파일",
        notFileLabel: String = existsLabel
    ): String? {
        val file = File(path)
        if (!file.exists()) return "ERROR: ${existsLabel}이 존재하지 않습니다: $path"
        if (!file.isFile) return "ERROR: ${notFileLabel}이 파일이 아닙니다: $path"
        if (!validateChanges(path, content)) return "ERROR: 보호된 파일입니다: $path"
        return null
    }

    /**
     * 변경사항 검증
     * 보호된 파일 목록 확인
     * @param content 향후 내용 기반 검증용. 현재는 경로만 검사.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun validateChanges(path: String, content: String): Boolean {
        val protectedFiles = listOf(
            "build.gradle.kts",
            "Application.kt",
            "ApplicationBootstrap.kt",
            "ApplicationLifecycleManager.kt"
        )
        
        // 보호된 파일명이 경로에 포함되어 있는지 확인
        if (protectedFiles.any { path.contains(it) }) {
            return false
        }
        
        return true
    }
    
    override suspend fun describe(): LayerDescription {
        return FileSystemLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "readFile" -> {
                val path = (args["path"] as? String) ?: ""
                readFile(path)
            }
            "writeFile" -> {
                val path = (args["path"] as? String) ?: ""
                val content = (args["content"] as? String) ?: ""
                writeFile(path, content)
            }
            "listDirectory" -> {
                val path = (args["path"] as? String) ?: "."
                listDirectory(path)
            }
            "backupFile" -> {
                val path = (args["path"] as? String) ?: ""
                backupFile(path)
            }
            "findFiles" -> {
                val pattern = (args["pattern"] as? String) ?: "*"
                val rootPath = (args["rootPath"] as? String) ?: "."
                findFiles(pattern, rootPath)
            }
            "deleteFile" -> {
                val path = (args["path"] as? String) ?: ""
                deleteFile(path)
            }
            "moveFile" -> {
                val sourcePath = (args["sourcePath"] as? String) ?: ""
                val targetPath = (args["targetPath"] as? String) ?: ""
                moveFile(sourcePath, targetPath)
            }
            else -> "Unknown function: $function. Available: readFile, writeFile, listDirectory, backupFile, findFiles, deleteFile, moveFile"
        }
    }
}
