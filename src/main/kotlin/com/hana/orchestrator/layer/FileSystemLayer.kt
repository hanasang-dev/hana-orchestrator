package com.hana.orchestrator.layer

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 파일 시스템 레이어
 *
 * 목적: 소스 코드·설정 등 파일을 읽고, 내용을 수정·추가할 수 있는 기능 제공.
 * (예: 변수·코드 추가, 기존 라인 수정, 새 파일 쓰기. readFile → 내용 생성/변경 → writeFile)
 *
 * ⭐ 파일 경로를 정확히 알고 있으면 바로 readFile(path="상대경로") 사용.
 *    kotlinFileIndex 컨텍스트에 "파일명:경로" 형태로 경로가 있으면 그 경로를 그대로 사용하세요.
 *    예: kotlinFileIndex에 "EchoLayer:src/main/kotlin/.../EchoLayer.kt" 가 있으면
 *        readFile(path="src/main/kotlin/.../EchoLayer.kt") 로 바로 읽으세요.
 *
 * ⭐ 경로를 모를 때: findFiles(pattern="**\/EchoLayer.kt") 로 먼저 경로를 찾은 후 readFile.
 *
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
                // 파일명만 주어진 경우 또는 절대 경로로 존재하지 않는 경우 → 파일명으로 자동 탐색
                val filename = file.name
                val found = findFiles("**/$filename")
                if (!found.startsWith("ERROR") && !found.startsWith("INFO")) {
                    val resolvedPath = found.lines().first().trim()
                    return readFile(resolvedPath)
                }
                return "ERROR: 파일이 존재하지 않습니다: $path. findFiles(pattern=\"**/$filename\")로 정확한 경로를 먼저 확인하세요."
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
     * 파일 쓰기 (자동 백업 포함).
     * ⚠️ content는 이미 처리·생성이 완료된 최종 내용이어야 합니다.
     * 코드를 수정해야 한다면 먼저 코드를 생성·변환한 뒤 그 결과를 content로 전달하세요.
     * 기존 파일 내용을 그대로 전달하면 변경 없음이 됩니다.
     *
     * @param path 쓸 파일 경로 (상대 경로 사용 필수, 절대 경로 금지)
     * @param content 파일에 쓸 최종 완성된 내용
     * @return 실행 결과 메시지
     */
    @LayerFunction
    suspend fun writeFile(path: String, content: String): String {
        return try {
            // 1. 경로 자동 해석: 파일이 현재 경로에 없으면 기존 파일 위치 탐색
            val resolvedPath = resolveWritePath(path)

            // 2. 보호된 파일 확인
            if (!validateChanges(resolvedPath, content)) {
                return "ERROR: 파일 수정 실패 - 보호된 파일입니다: $resolvedPath"
            }

            val file = File(resolvedPath)

            // 3. 기존 파일이 있을 때만 백업 (신규 파일이면 백업 생략)
            val backupPath = if (file.exists()) backupFile(resolvedPath) else "신규 파일"

            // 4. 파일 쓰기 (마크다운 코드펜스 자동 제거)
            val sanitized = content
                .trimStart()
                .let { if (it.startsWith("```")) it.lines().drop(1).joinToString("\n") else it }
                .trimEnd()
                .let { if (it.endsWith("```")) it.dropLast(3).trimEnd() else it }

            // 5. Kotlin 파일 유효성 검증: package 선언으로 시작해야 함
            if (resolvedPath.endsWith(".kt") && !sanitized.trimStart().startsWith("package ")) {
                return "ERROR: .kt 파일에 유효하지 않은 내용 — 'package' 선언으로 시작해야 합니다. Kotlin 소스 코드만 쓸 수 있습니다."
            }

            file.parentFile?.mkdirs()
            file.writeText(sanitized)

            "SUCCESS: 파일 수정 완료\n경로: $resolvedPath\n백업: $backupPath"
        } catch (e: Exception) {
            "ERROR: 파일 쓰기 실패: ${e.message}"
        }
    }

    /**
     * writeFile은 파일 diff를 미리보기로 제공
     */
    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview {
        if (function == "writeFile") {
            val path = args["path"] as? String ?: ""
            val content = args["content"] as? String ?: ""
            val resolvedPath = resolveWritePath(path)
            val oldContent = try { File(resolvedPath).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
            return ApprovalPreview(path = resolvedPath, oldContent = oldContent, newContent = content, kind = ApprovalKind.FILE)
        }
        return super.approvalPreview(function, args)
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
     * 파일 검색 (glob 패턴 지원, ** 재귀 포함)
     *
     * @param pattern glob 패턴 (예: "*.kt", "**∕*.kt", "src∕**∕*Test.kt")
     * @param rootPath 검색 시작 경로 (기본값: ".", 상대 경로 사용 필수)
     *   - 올바른 예: ".", "src", "src/main", "./docs"
     *   - 잘못된 예: "/", "/src", "/path/to/dir" (절대 경로 사용 금지)
     *   - 현재 작업 디렉토리 기준으로 상대 경로를 해석합니다
     * @return 찾은 파일 경로 목록 (줄바꿈으로 구분, rootPath 기준 상대 경로)
     */
    @LayerFunction
    suspend fun findFiles(pattern: String, rootPath: String = "."): String {
        return try {
            val root = Paths.get(rootPath).toAbsolutePath().normalize()
            if (!Files.exists(root)) {
                return "ERROR: 검색 경로가 존재하지 않습니다: $rootPath"
            }
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matches = Files.walk(root)
                .filter { Files.isRegularFile(it) }
                .filter { matcher.matches(root.relativize(it)) }
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()

            if (matches.isEmpty()) {
                "INFO: 패턴 '$pattern'에 맞는 파일을 찾지 못했습니다"
            } else {
                matches.joinToString("\n")
            }
        } catch (e: Exception) {
            "ERROR: 파일 검색 실패: ${e.message}"
        }
    }
    
    /**
     * 파일 내용 검색 (grep)
     *
     * @param pattern 검색할 텍스트 또는 정규식 (예: "fun ", "TODO", "class.*Layer")
     * @param path 검색 대상 파일 또는 디렉토리 경로 (기본값: ".", 상대 경로 사용 필수)
     * @param glob 검색할 파일 필터 glob 패턴 (기본값: "*" — 모든 파일, 예: "*.kt", "*.json")
     * @return 매칭된 결과 목록 (파일경로:라인번호: 내용 형식)
     */
    @LayerFunction
    suspend fun searchContent(pattern: String, path: String = ".", glob: String = "*"): String {
        return try {
            val root = Paths.get(path).toAbsolutePath().normalize()
            if (!Files.exists(root)) return "ERROR: 경로가 존재하지 않습니다: $path"

            val regex = Regex(pattern)
            val globMatcher = FileSystems.getDefault().getPathMatcher("glob:$glob")

            val targets = if (Files.isRegularFile(root)) {
                listOf(root)
            } else {
                Files.walk(root)
                    .filter { Files.isRegularFile(it) && globMatcher.matches(it.fileName) }
                    .toList()
            }

            val results = mutableListOf<String>()
            for (file in targets) {
                try {
                    file.toFile().readLines().forEachIndexed { idx, line ->
                        if (regex.containsMatchIn(line)) {
                            val rel = root.relativize(file).toString().ifEmpty { file.fileName.toString() }
                            results.add("$rel:${idx + 1}: $line")
                        }
                    }
                } catch (_: Exception) { /* 바이너리 등 읽기 불가 파일 스킵 */ }
            }

            if (results.isEmpty()) {
                "INFO: '$pattern' 패턴에 매칭되는 내용이 없습니다 (경로: $path, 파일필터: $glob)"
            } else {
                "검색 결과 (${results.size}건):\n" + results.joinToString("\n")
            }
        } catch (e: Exception) {
            "ERROR: 내용 검색 실패: ${e.message}"
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
     * writeFile용 경로 해석: 파일이 현재 경로에 없으면 프로젝트 내 동일 파일명을 탐색.
     * 신규 파일(탐색 결과 없음)은 원래 path 그대로 반환.
     */
    private suspend fun resolveWritePath(path: String): String {
        if (File(path).exists()) return path
        val filename = File(path).name
        val found = findFiles("**/$filename")
        if (!found.startsWith("ERROR") && !found.startsWith("INFO")) {
            return found.lines().first().trim()
        }
        return path
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
     * 보호된 파일·디렉토리 목록 확인
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

        // 보호된 디렉토리 — 하위 경로 전체 차단
        val protectedDirs = listOf(
            "orchestrator/core"
        )

        // 보호된 파일명이 경로에 포함되어 있는지 확인
        if (protectedFiles.any { path.contains(it) }) return false

        // 보호된 디렉토리 하위 경로 차단
        val normalized = path.replace("\\", "/")
        if (protectedDirs.any { normalized.contains(it) }) return false

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
            "searchContent" -> {
                val pattern = (args["pattern"] as? String) ?: return "ERROR: pattern 파라미터 필수"
                val path = (args["path"] as? String) ?: "."
                val glob = (args["glob"] as? String) ?: "*"
                searchContent(pattern, path, glob)
            }
            else -> "Unknown function: $function. Available: readFile, writeFile, listDirectory, backupFile, findFiles, searchContent, deleteFile, moveFile"
        }
    }
}
