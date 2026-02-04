package com.hana.orchestrator.context

import java.io.File

/**
 * projectRoot 기준으로 프로젝트 규칙 파일 로드.
 * .cursor/rules 디렉터리 또는 AGENTS.md 등.
 */
class ProjectRulesLoader {

    companion object {
        private const val RULES_DIR = ".cursor/rules"
        private const val AGENTS_FILE = "AGENTS.md"
    }

    /**
     * root 경로에서 규칙 텍스트 수집.
     * 없으면 빈 문자열.
     */
    fun load(root: String): String {
        if (root.isBlank()) return ""
        val dir = File(root)
        if (!dir.isDirectory) return ""

        val parts = mutableListOf<String>()

        // .cursor/rules/*.md
        val rulesDir = File(dir, RULES_DIR)
        if (rulesDir.isDirectory) {
            rulesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }
                ?.sortedBy { it.name }
                ?.forEach { parts.add("[${it.name}]\n${it.readText().trim()}") }
        }

        // AGENTS.md
        val agentsFile = File(dir, AGENTS_FILE)
        if (agentsFile.isFile) {
            parts.add("[$AGENTS_FILE]\n${agentsFile.readText().trim()}")
        }

        return parts.joinToString("\n\n")
    }
}
