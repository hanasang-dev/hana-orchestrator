package com.hana.orchestrator.context

/**
 * 영구 + 휘발 + 본문을 합쳐 최종 프롬프트 문자열 생성.
 * taskType은 추후 태스크별 블록 선택(예: 평가 시 다른 조합)에 사용.
 */
interface PromptComposer {
    fun compose(
        taskType: LLMTaskType,
        appContext: AppContextService,
        body: String
    ): String
}
