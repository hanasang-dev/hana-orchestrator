package com.hana.orchestrator.llm.strategy

import com.hana.orchestrator.llm.LLMClient

/**
 * 모델 선택 전략 인터페이스
 * 작업 복잡도에 따라 적절한 LLM 클라이언트를 선택
 *
 * KSP 프로세서가 LLMClient 인터페이스의 @LLMTask 어노테이션을 읽어서
 * 이 인터페이스의 구현체를 자동 생성함
 */
interface ModelSelectionStrategy {
    /**
     * generateDirectAnswer 작업용 클라이언트 선택
     */
    fun selectClientForGenerateDirectAnswer(): LLMClient

    /**
     * reviewTree 작업용 클라이언트 선택
     */
    fun selectClientForReviewTree(): LLMClient

    /**
     * decideNextAction (ReAct) 작업용 클라이언트 선택
     * KSP 자동 생성 대상 아님 — 기본 구현 제공 (generateDirectAnswer 클라이언트 재사용)
     */
    fun selectClientForReActDecision(): LLMClient = selectClientForGenerateDirectAnswer()
}
