package com.hana.orchestrator.context

/** LLM 호출 종류. 현재 PromptComposer는 CREATE_TREE만 사용; 추후 평가/재시도 시 태스크별 블록 선택용. */
enum class LLMTaskType {
    CREATE_TREE,
    EVALUATE,
    RETRY_STRATEGY
}
