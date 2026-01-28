package com.hana.orchestrator.llm

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionContext
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
     * 요구사항이 레이어 기능으로 수행 가능한지 사전 검증
     * 간단한 작업: 짧은 프롬프트, 빠른 응답
     */
    @LLMTask(complexity = LLMTaskComplexity.SIMPLE)
    suspend fun validateQueryFeasibility(
        userQuery: String,
        layerDescriptions: List<LayerDescription>
    ): QueryFeasibility
    
    /**
     * 사용자 질문과 레이어 정보를 바탕으로 ExecutionTree 구조의 실행 계획을 생성
     * 복잡한 작업: 긴 프롬프트, 많은 컨텍스트
     */
    @LLMTask(complexity = LLMTaskComplexity.COMPLEX)
    suspend fun createExecutionTree(
        userQuery: String,
        layerDescriptions: List<LayerDescription>
    ): ExecutionTree
    
    /**
     * 실행 결과가 사용자 요구사항에 부합하는지 LLM이 판단
     * 중간 작업: 중간 프롬프트
     */
    @LLMTask(complexity = LLMTaskComplexity.MEDIUM)
    suspend fun evaluateResult(
        userQuery: String,
        executionResult: String,
        executionContext: ExecutionContext?
    ): ResultEvaluation
    
    /**
     * 실패한 실행에 대한 재처리 방안을 LLM이 제시
     * 복잡한 작업: 긴 프롬프트, 히스토리 포함
     */
    @LLMTask(complexity = LLMTaskComplexity.COMPLEX)
    suspend fun suggestRetryStrategy(
        userQuery: String,
        previousHistory: ExecutionHistory,
        layerDescriptions: List<LayerDescription>
    ): RetryStrategy
    
    /**
     * 이전 실행과 현재 실행을 비교하여 유의미한 변경이 있는지 LLM이 판단
     * 중간 작업: 중간 프롬프트
     */
    @LLMTask(complexity = LLMTaskComplexity.MEDIUM)
    suspend fun compareExecutions(
        userQuery: String,
        previousTree: ExecutionTree?,
        previousResult: String,
        currentTree: ExecutionTree,
        currentResult: String
    ): ComparisonResult
    
    /**
     * 부모 레이어의 실행 결과를 받아서 자식 레이어 함수의 파라미터로 변환
     * 간단한 작업: 짧은 프롬프트, 빠른 응답
     */
    @LLMTask(complexity = LLMTaskComplexity.SIMPLE)
    suspend fun extractParameters(
        parentResult: String,
        childLayerName: String,
        childFunctionName: String,
        childFunctionDetails: com.hana.orchestrator.layer.FunctionDescription,
        layerDescriptions: List<LayerDescription>
    ): Map<String, Any>
    
    /**
     * 리소스 정리
     */
    suspend fun close()
}
