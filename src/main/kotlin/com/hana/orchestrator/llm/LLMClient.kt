package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.layer.LayerDescription

/**
 * LLM 클라이언트 인터페이스
 * SRP: LLM 호출 책임만 정의
 *
 * 다양한 LLM 제공자(Ollama, OpenAI, Anthropic 등)를 통합하기 위한 추상화
 * 각 메서드는 @LLMTask 어노테이션으로 복잡도가 표시됨
 */
interface LLMClient {
    /**
     * LLM이 직접 답변 생성 (레이어 없이)
     * 중간 작업: 일반적인 질문에 대한 답변 생성
     */
    @LLMTask(complexity = LLMTaskComplexity.MEDIUM)
    suspend fun generateDirectAnswer(userQuery: String): String

    /**
     * 사용자가 수정한 실행 트리를 검토하여 원래 요구사항에 부합하는지 판단
     */
    @LLMTask(complexity = LLMTaskComplexity.SIMPLE)
    suspend fun reviewTree(
        userQuery: String,
        tree: ExecutionTree,
        layerDescriptions: List<LayerDescription>
    ): TreeReview

    /**
     * ReAct 루프에서 다음 스텝 결정 (관찰 → 사고 → 행동)
     * @LLMTask 미적용 — ModelSelectionStrategy의 default 메서드로 클라이언트 선택
     */
    suspend fun decideNextAction(
        query: String,
        stepHistory: List<ReActStep>,
        layerDescriptions: List<LayerDescription>,
        projectContext: Map<String, String> = emptyMap()
    ): ReActDecision

    /**
     * 리소스 정리
     */
    suspend fun close()
}
