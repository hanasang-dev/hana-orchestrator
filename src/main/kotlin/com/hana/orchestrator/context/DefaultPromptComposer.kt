package com.hana.orchestrator.context

/**
 * 영구 블록 + 휘발 블록 + 본문 순서로 결합.
 * taskType은 현재 미사용; 추후 태스크별 블록 선택 시 사용.
 */
class DefaultPromptComposer : PromptComposer {

    override fun compose(
        taskType: LLMTaskType,
        appContext: AppContextService,
        body: String
    ): String {
        val (persistent, volatile) = appContext.getAppContextForPrompt()
        val parts = mutableListOf<String>()
        if (persistent.isNotEmpty()) parts.add(formatBlock("영구 컨텍스트", persistent))
        if (volatile.isNotEmpty()) parts.add(formatBlock("휘발성 컨텍스트", volatile))
        parts.add(body)
        return parts.joinToString("\n\n")
    }

    private fun formatBlock(title: String, snapshot: ContextSnapshot): String {
        val lines = snapshot.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        return "## $title\n$lines"
    }
}
