package com.hana.orchestrator.layer

/**
 * 파일 처리 레이어 구현 예시
 * 로컬 파일 시스템에서 파일 읽기/쓰기/삭제 기능 제공
 */
class FileProcessorLayer : CommonLayerInterface {
    
    override suspend fun describe(): LayerDescription {
        return LayerDescription(
            name = "file-processor",
            description = "파일 읽기, 쓰기, 삭제 등 파일 조작 기능",
            layerDepth = 1,
            functions = listOf(
                "create_file",
                "read_file", 
                "delete_file",
                "list_files",
                "file_exists"
            )
        )
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "create_file" -> {
                val path = args["path"] as String
                val content = args["content"] as? String ?: ""
                try {
                    java.io.File(path).writeText(content)
                    "File created: $path"
                } catch (e: Exception) {
                    "Error creating file: ${e.message}"
                }
            }
            
            "read_file" -> {
                val path = args["path"] as String
                try {
                    java.io.File(path).readText()
                } catch (e: Exception) {
                    "Error reading file: ${e.message}"
                }
            }
            
            "delete_file" -> {
                val path = args["path"] as String
                try {
                    val deleted = java.io.File(path).delete()
                    if (deleted) "File deleted: $path" 
                    else "Failed to delete file: $path"
                } catch (e: Exception) {
                    "Error deleting file: ${e.message}"
                }
            }
            
            "list_files" -> {
                val path = args["path"] as? String ?: "."
                try {
                    val files = java.io.File(path).listFiles() ?: emptyArray()
                    files.joinToString("\n") { it.name }
                } catch (e: Exception) {
                    "Error listing files: ${e.message}"
                }
            }
            
            "file_exists" -> {
                val path = args["path"] as String
                val exists = java.io.File(path).exists()
                exists.toString()
            }
            
            else -> "Unknown function: $function"
        }
    }
}