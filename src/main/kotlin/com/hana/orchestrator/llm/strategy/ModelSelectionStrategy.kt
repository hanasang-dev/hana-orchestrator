package com.hana.orchestrator.llm.strategy

import com.hana.orchestrator.llm.LLMClient

/**
 * 모델 선택 전략 인터페이스
 * 작업 복잡도에 따라 적절한 LLM 클라이언트를 선택
 * 
 * KSP 프로세서가 LLMClient 인터페이스의 @LLMTask 어노테이션을 읽어서
 * 이 인터페이스의 구현체를 자동 생성함
 * 
 * 설계 변경:
 * - Factory 패턴 도입으로 병렬 처리 및 확장성 지원
 * - 필요할 때마다 새로운 클라이언트 인스턴스 생성 가능
 */
interface ModelSelectionStrategy {
    /**
     * createExecutionTree 작업용 클라이언트 선택
     */
    fun selectClientForTreeCreation(): LLMClient
    
    /**
     * evaluateResult 작업용 클라이언트 선택
     */
    fun selectClientForEvaluation(): LLMClient
    
    /**
     * suggestRetryStrategy 작업용 클라이언트 선택
     */
    fun selectClientForRetryStrategy(): LLMClient
    
    /**
     * compareExecutions 작업용 클라이언트 선택
     */
    fun selectClientForComparison(): LLMClient
    
    /**
     * extractParameters 작업용 클라이언트 선택
     * 병렬 자식 노드 실행 시 각각 독립적인 클라이언트 인스턴스 반환
     */
    fun selectClientForParameterExtraction(): LLMClient
    
    /**
     * checkIfLLMCanAnswerDirectly 작업용 클라이언트 선택
     */
    fun selectClientForCheckIfLLMCanAnswerDirectly(): LLMClient
    
    /**
     * generateDirectAnswer 작업용 클라이언트 선택
     */
    fun selectClientForGenerateDirectAnswer(): LLMClient
}
