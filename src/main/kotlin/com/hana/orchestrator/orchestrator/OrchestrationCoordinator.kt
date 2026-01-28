package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.llm.QueryFeasibility

/**
 * 오케스트레이션 조율 책임
 * SRP: 전체 오케스트레이션 흐름 조율만 담당
 */
class OrchestrationCoordinator(
    private val layerManager: LayerManager,
    private val treeExecutor: TreeExecutor,
    private val historyManager: ExecutionHistoryManager,
    private val statePublisher: ExecutionStatePublisher,
    private val modelSelectionStrategy: ModelSelectionStrategy
) {
    private val maxAttempts = 5 // 최대 재시도 횟수 (안전장치)
    
    /**
     * 오케스트레이션 실행 (도메인 모델 반환)
     * LLM 기반 자동 재처리 루프 포함
     */
    suspend fun executeOrchestration(query: String): ExecutionResult {
        val allDescriptions = layerManager.getAllLayerDescriptions()
        
        return if (query.isNotEmpty()) {
            val executionId = java.util.UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()
            
            // 실행 이력 생성 및 Flow에 emit
            val runningHistory = ExecutionHistory.createRunning(executionId, query, startTime)
            historyManager.setCurrentExecution(runningHistory)
            historyManager.addLogToCurrent("🚀 실행 시작: $query")
            statePublisher.emitExecutionUpdate(runningHistory)
            
            var previousHistory: ExecutionHistory? = null
            var previousTree: ExecutionTree? = null
            var attemptCount = 0
            
            while (attemptCount < maxAttempts) {
                attemptCount++
                val attemptMsg = "\n🔄 [OrchestrationCoordinator] 실행 시도 #$attemptCount"
                println(attemptMsg)
                historyManager.addLogToCurrent(attemptMsg)
                
                try {
                    // LLM으로 트리 생성
                    val rawTree = if (attemptCount == 1) {
                        createInitialTree(query, allDescriptions, executionId, startTime)
                    } else {
                        createRetryTree(query, previousHistory!!, allDescriptions, executionId, startTime)
                    }
                    
                    // 트리 검증 및 실행
                    val result = validateAndExecuteTree(rawTree, query, allDescriptions, executionId, startTime)
                    
                    // 실행 결과 평가
                    val evaluation = evaluateResult(query, result, executionId, startTime)
                    
                    // 요구사항 부합 여부 확인
                    if (evaluation.isSatisfactory && !evaluation.needsRetry) {
                        // 성공: 요구사항 부합하고 재처리 불필요
                        val successMsg = "✅ [OrchestrationCoordinator] 실행 성공: 요구사항 부합"
                        println(successMsg)
                        historyManager.addLogToCurrent(successMsg)
                        
                        // 실행 완료 이력 저장
                        val history = ExecutionHistory.createCompleted(
                            executionId, query, result, startTime,
                            logs = historyManager.getCurrentLogs()
                        )
                        historyManager.addHistory(history)
                        statePublisher.emitExecutionUpdate(history)
                        historyManager.clearCurrentExecution()
                        return result
                    }
                    
                    // 재처리 필요 또는 요구사항 미부합
                    if (evaluation.needsRetry) {
                        if (!shouldContinueRetry(previousHistory, previousTree, rawTree, result, query, executionId, startTime)) {
                            historyManager.clearCurrentExecution()
                            return result
                        }
                        
                        // 재처리 루프 계속
                        val failedHistory = ExecutionHistory.createFailed(
                            executionId, query,
                            "요구사항 미부합: ${evaluation.reason}",
                            startTime,
                            logs = historyManager.getCurrentLogs()
                        )
                        historyManager.addHistory(failedHistory)
                        statePublisher.emitExecutionUpdate(failedHistory)
                        
                        previousHistory = failedHistory
                        previousTree = rawTree
                        val newRunningHistory = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
                        newRunningHistory.logs.addAll(historyManager.getCurrentLogs())
                        historyManager.setCurrentExecution(newRunningHistory)
                        statePublisher.emitExecutionUpdate(newRunningHistory)
                        continue
                    }
                    
                    // 평가 실패 또는 기타 경우: 현재 결과 반환
                    historyManager.clearCurrentExecution()
                    return result
                    
                } catch (e: Exception) {
                    if (!handleExecutionException(e, attemptCount, query, executionId, startTime, previousHistory, allDescriptions)) {
                        historyManager.clearCurrentExecution()
                        return ExecutionResult(result = "", error = "최대 재시도 횟수 도달: ${e.message}")
                    }
                    previousHistory = ExecutionHistory.createFailed(
                        executionId, query,
                        e.message ?: "알 수 없는 오류",
                        startTime,
                        logs = historyManager.getCurrentLogs()
                    )
                    continue
                }
            }
            
            // 최대 재시도 횟수 도달
            val maxAttemptsMsg = "🛑 [OrchestrationCoordinator] 최대 재시도 횟수 도달"
            println(maxAttemptsMsg)
            historyManager.addLogToCurrent(maxAttemptsMsg)
            val finalFailedHistory = ExecutionHistory.createFailed(
                executionId, query,
                "최대 재시도 횟수 도달",
                startTime,
                logs = historyManager.getCurrentLogs()
            )
            historyManager.addHistory(finalFailedHistory)
            statePublisher.emitExecutionUpdate(finalFailedHistory)
            historyManager.clearCurrentExecution()
            return ExecutionResult(result = "", error = "최대 재시도 횟수 도달")
            
        } else {
            // 빈 쿼리인 경우 기본 결과 반환
            ExecutionResult(result = "Empty query")
        }
    }
    
    /**
     * 초기 트리 생성
     */
    private suspend fun createInitialTree(
        query: String,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        executionId: String,
        startTime: Long
    ): ExecutionTree {
        val queryMsg = "🔍 [OrchestrationCoordinator] 사용자 쿼리 수신: $query"
        println(queryMsg)
        historyManager.addLogToCurrent(queryMsg)
        
        // 요구사항 실행 가능성 사전 검증
        val feasibility = validateFeasibility(query, allDescriptions)
        if (!feasibility.feasible) {
            handleFeasibilityFailure(feasibility, query, executionId, startTime)
            throw Exception(feasibility.reason)
        }
        
        val treeStartMsg = "🌳 [OrchestrationCoordinator] 실행 트리 생성 시작..."
        println(treeStartMsg)
        historyManager.addLogToCurrent(treeStartMsg)
        
        val treeStartTime = System.currentTimeMillis()
        val tree = try {
            modelSelectionStrategy.selectClientForTreeCreation()
                .useSuspend { client ->
                    client.createExecutionTree(query, allDescriptions)
                }
        } catch (treeException: Exception) {
            handleTreeCreationFailure(treeException, query, executionId, startTime)
            throw treeException
        }
        
        val treeDuration = System.currentTimeMillis() - treeStartTime
        val perfMsg = "⏱️ [PERF] 트리 생성 완료: ${treeDuration}ms"
        println(perfMsg)
        historyManager.addLogToCurrent(perfMsg)
        
        return tree
    }
    
    /**
     * 재시도 트리 생성
     */
    private suspend fun createRetryTree(
        query: String,
        previousHistory: ExecutionHistory,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        executionId: String,
        startTime: Long
    ): ExecutionTree {
        val retryMsg = "🔧 [OrchestrationCoordinator] 재처리 방안 요청 중..."
        println(retryMsg)
        historyManager.addLogToCurrent(retryMsg)
        
        val retryStartTime = System.currentTimeMillis()
        val retryStrategy = try {
            modelSelectionStrategy.selectClientForRetryStrategy()
                .useSuspend { client ->
                    client.suggestRetryStrategy(query, previousHistory, allDescriptions)
                }
        } catch (retryException: Exception) {
            handleRetryStrategyFailure(retryException, query, executionId, startTime)
            throw retryException
        }
        
        val retryDuration = System.currentTimeMillis() - retryStartTime
        val perfMsg = "⏱️ [PERF] 재처리 방안 생성: ${retryDuration}ms"
        println(perfMsg)
        historyManager.addLogToCurrent(perfMsg)
        
        if (retryStrategy.shouldStop) {
            handleRetryStop(retryStrategy.reason, query, executionId, startTime)
            throw Exception("재처리 중단: ${retryStrategy.reason}")
        }
        
        val successMsg = "✅ [OrchestrationCoordinator] 재처리 방안 수신: ${retryStrategy.reason}"
        println(successMsg)
        historyManager.addLogToCurrent(successMsg)
        
        return retryStrategy.newTree ?: throw Exception("재처리 트리가 null입니다")
    }
    
    /**
     * 요구사항 실행 가능성 검증
     */
    private suspend fun validateFeasibility(
        query: String,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): QueryFeasibility {
        val feasibilityCheckMsg = "🔎 [OrchestrationCoordinator] 요구사항 실행 가능성 검증 중..."
        println(feasibilityCheckMsg)
        historyManager.addLogToCurrent(feasibilityCheckMsg)
        
        val feasibilityStartTime = System.currentTimeMillis()
        val feasibility = try {
            modelSelectionStrategy.selectClientForFeasibilityCheck()
                .useSuspend { client ->
                    client.validateQueryFeasibility(query, allDescriptions)
                }
        } catch (feasibilityException: Exception) {
            val errorMsg = "⚠️ [OrchestrationCoordinator] 요구사항 검증 실패: ${feasibilityException.message}, 트리 생성 계속 진행"
            println(errorMsg)
            historyManager.addLogToCurrent(errorMsg)
            return QueryFeasibility(feasible = true, reason = "검증 실패로 인해 계속 진행")
        }
        
        val feasibilityDuration = System.currentTimeMillis() - feasibilityStartTime
        val feasibilityPerfMsg = "⏱️ [PERF] 요구사항 검증 완료: ${feasibilityDuration}ms"
        println(feasibilityPerfMsg)
        historyManager.addLogToCurrent(feasibilityPerfMsg)
        
        if (feasibility.feasible) {
            val feasibleMsg = "✅ [OrchestrationCoordinator] 요구사항 실행 가능: ${feasibility.reason}"
            println(feasibleMsg)
            historyManager.addLogToCurrent(feasibleMsg)
        }
        
        return feasibility
    }
    
    /**
     * 트리 검증 및 실행
     */
    private suspend fun validateAndExecuteTree(
        rawTree: ExecutionTree,
        query: String,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        executionId: String,
        startTime: Long
    ): ExecutionResult {
        val treeMsg = "🌳 [OrchestrationCoordinator] 실행 트리: rootNode=${rawTree.rootNode.layerName}.${rawTree.rootNode.function}, children=${rawTree.rootNode.children.size}"
        println(treeMsg)
        historyManager.addLogToCurrent(treeMsg)
        
        // 트리 검증 및 자동 수정
        val validationStartTime = System.currentTimeMillis()
        val validator = ExecutionTreeValidator(allDescriptions)
        val validationResult = validator.validateAndFix(rawTree, query)
        val validationDuration = System.currentTimeMillis() - validationStartTime
        println("⏱️ [PERF] 트리 검증 완료: ${validationDuration}ms")
        
        if (validationResult.errors.isNotEmpty()) {
            val errorMsg = "❌ [OrchestrationCoordinator] 트리 검증 실패: ${validationResult.errors.joinToString(", ")}"
            println(errorMsg)
            historyManager.addLogToCurrent(errorMsg)
            throw Exception("트리 검증 실패: ${validationResult.errors.joinToString(", ")}")
        }
        
        val treeToExecute = validationResult.fixedTree ?: rawTree
        
        if (validationResult.warnings.isNotEmpty()) {
            val warnMsg = "⚠️ [OrchestrationCoordinator] 트리 검증 경고: ${validationResult.warnings.joinToString(", ")}"
            println(warnMsg)
            historyManager.addLogToCurrent(warnMsg)
        }
        
        // 트리 실행
        val execStartMsg = "🚀 [OrchestrationCoordinator] 트리 실행 시작..."
        println(execStartMsg)
        historyManager.addLogToCurrent(execStartMsg)
        
        val executionStartTime = System.currentTimeMillis()
        val currentExecution = historyManager.getCurrentExecution()!!
        val result = treeExecutor.executeTree(treeToExecute, currentExecution)
        
        val executionDuration = System.currentTimeMillis() - executionStartTime
        val execPerfMsg = "⏱️ [PERF] 트리 실행 완료: ${executionDuration}ms"
        println(execPerfMsg)
        historyManager.addLogToCurrent(execPerfMsg)
        
        val execDoneMsg = "✅ [OrchestrationCoordinator] 트리 실행 완료"
        println(execDoneMsg)
        historyManager.addLogToCurrent(execDoneMsg)
        
        return result
    }
    
    /**
     * 실행 결과 평가
     */
    private suspend fun evaluateResult(
        query: String,
        result: ExecutionResult,
        executionId: String,
        startTime: Long
    ): com.hana.orchestrator.llm.ResultEvaluation {
        val evalStartMsg = "🤔 [OrchestrationCoordinator] 실행 결과 평가 중..."
        println(evalStartMsg)
        historyManager.addLogToCurrent(evalStartMsg)
        
        val evaluationStartTime = System.currentTimeMillis()
        val evaluation = modelSelectionStrategy.selectClientForEvaluation()
            .useSuspend { client ->
                client.evaluateResult(query, result.result, result.context)
            }
        
        val evaluationDuration = System.currentTimeMillis() - evaluationStartTime
        val evalPerfMsg = "⏱️ [PERF] 결과 평가 완료: ${evaluationDuration}ms"
        println(evalPerfMsg)
        historyManager.addLogToCurrent(evalPerfMsg)
        
        val evalResultMsg = "📊 [OrchestrationCoordinator] 평가 결과: ${if (evaluation.isSatisfactory) "요구사항 부합" else "요구사항 미부합"} - ${evaluation.reason}"
        println(evalResultMsg)
        historyManager.addLogToCurrent(evalResultMsg)
        
        return evaluation
    }
    
    /**
     * 재시도 계속 여부 확인
     */
    private suspend fun shouldContinueRetry(
        previousHistory: ExecutionHistory?,
        previousTree: ExecutionTree?,
        currentTree: ExecutionTree,
        currentResult: ExecutionResult,
        query: String,
        executionId: String,
        startTime: Long
    ): Boolean {
        if (previousHistory == null || previousTree == null) {
            return true
        }
        
        val compareMsg = "🔍 [OrchestrationCoordinator] 이전 실행과 비교 중..."
        println(compareMsg)
        historyManager.addLogToCurrent(compareMsg)
        
        val comparisonStartTime = System.currentTimeMillis()
        val comparison = modelSelectionStrategy.selectClientForComparison()
            .useSuspend { client ->
                client.compareExecutions(
                    query,
                    previousTree,
                    previousHistory.result.result,
                    currentTree,
                    currentResult.result
                )
            }
        
        val comparisonDuration = System.currentTimeMillis() - comparisonStartTime
        val comparePerfMsg = "⏱️ [PERF] 실행 비교 완료: ${comparisonDuration}ms"
        println(comparePerfMsg)
        historyManager.addLogToCurrent(comparePerfMsg)
        
        if (!comparison.isSignificantlyDifferent) {
            val noChangeMsg = "⚠️ [OrchestrationCoordinator] 유의미한 변경 없음: ${comparison.reason}"
            val stopMsg = "🛑 [OrchestrationCoordinator] 무한 루프 방지: 재처리 중단"
            println(noChangeMsg)
            println(stopMsg)
            historyManager.addLogToCurrent(noChangeMsg)
            historyManager.addLogToCurrent(stopMsg)
            return false
        }
        
        val diffMsg = "✅ [OrchestrationCoordinator] 유의미한 차이 확인: ${comparison.reason}"
        println(diffMsg)
        historyManager.addLogToCurrent(diffMsg)
        return true
    }
    
    /**
     * 실행 예외 처리
     */
    private suspend fun handleExecutionException(
        e: Exception,
        attemptCount: Int,
        query: String,
        executionId: String,
        startTime: Long,
        previousHistory: ExecutionHistory?,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): Boolean {
        val errorMsg = "❌ [OrchestrationCoordinator] 실행 실패: ${e.message}"
        val errorTypeMsg = "   예외 타입: ${e::class.simpleName}"
        println(errorMsg)
        println(errorTypeMsg)
        historyManager.addLogToCurrent(errorMsg)
        historyManager.addLogToCurrent(errorTypeMsg)
        e.printStackTrace()
        
        // 실패 이력 저장
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query,
            e.message ?: "알 수 없는 오류",
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
        
        // 재처리 가능 여부 확인
        if (attemptCount >= maxAttempts) {
            val maxAttemptsMsg = "🛑 [OrchestrationCoordinator] 최대 재시도 횟수 도달: 중단"
            println(maxAttemptsMsg)
            historyManager.addLogToCurrent(maxAttemptsMsg)
            return false
        }
        
        // 재처리 방안 요청
        try {
            val prevHistory = previousHistory ?: failedHistory
            val retryAnalysisMsg = "🔧 [OrchestrationCoordinator] 실패 분석 및 재처리 방안 요청 중..."
            println(retryAnalysisMsg)
            historyManager.addLogToCurrent(retryAnalysisMsg)
            
            val retryStrategy = modelSelectionStrategy.selectClientForRetryStrategy()
                .useSuspend { client ->
                    client.suggestRetryStrategy(query, prevHistory, allDescriptions)
                }
            
            if (retryStrategy.shouldStop) {
                handleRetryStop(retryStrategy.reason, query, executionId, startTime)
                return false
            }
            
            val retrySuccessMsg = "✅ [OrchestrationCoordinator] 재처리 방안 수신: ${retryStrategy.reason}"
            println(retrySuccessMsg)
            historyManager.addLogToCurrent(retrySuccessMsg)
            
            val newRunningHistory = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
            newRunningHistory.logs.addAll(historyManager.getCurrentLogs())
            historyManager.setCurrentExecution(newRunningHistory)
            statePublisher.emitExecutionUpdate(newRunningHistory)
            return true
        } catch (retryException: Exception) {
            val retryErrorMsg = "❌ [OrchestrationCoordinator] 재처리 방안 요청 실패: ${retryException.message}"
            println(retryErrorMsg)
            historyManager.addLogToCurrent(retryErrorMsg)
            return false
        }
    }
    
    // Helper methods for error handling
    private suspend fun handleFeasibilityFailure(
        feasibility: QueryFeasibility,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        val rejectionMsg = "❌ [OrchestrationCoordinator] 요구사항 실행 불가능: ${feasibility.reason}"
        println(rejectionMsg)
        historyManager.addLogToCurrent(rejectionMsg)
        
        val suggestionMsg = feasibility.suggestion?.let {
            "💡 [OrchestrationCoordinator] 제안: $it"
        }
        suggestionMsg?.let {
            println(it)
            historyManager.addLogToCurrent(it)
        }
        
        val errorMessage = if (feasibility.suggestion != null) {
            "${feasibility.reason}\n\n제안: ${feasibility.suggestion}"
        } else {
            feasibility.reason
        }
        
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query,
            errorMessage,
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
    }
    
    private suspend fun handleTreeCreationFailure(
        e: Exception,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        val errorMsg = "❌ [OrchestrationCoordinator] 트리 생성 실패: ${e.message}"
        println(errorMsg)
        historyManager.addLogToCurrent(errorMsg)
        
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query,
            "트리 생성 실패: ${e.message}",
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
    }
    
    private suspend fun handleRetryStrategyFailure(
        e: Exception,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        val errorMsg = "❌ [OrchestrationCoordinator] 재처리 방안 요청 실패: ${e.message}"
        println(errorMsg)
        historyManager.addLogToCurrent(errorMsg)
        
        val finalHistory = ExecutionHistory.createFailed(
            executionId, query,
            "재처리 방안 요청 실패: ${e.message}",
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(finalHistory)
        statePublisher.emitExecutionUpdate(finalHistory)
    }
    
    private suspend fun handleRetryStop(
        reason: String,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        val stopMsg = "🛑 [OrchestrationCoordinator] LLM 판단: 근본 해결 불가능 - $reason"
        println(stopMsg)
        historyManager.addLogToCurrent(stopMsg)
        
        val finalHistory = ExecutionHistory.createFailed(
            executionId, query,
            "재처리 중단: $reason",
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(finalHistory)
        statePublisher.emitExecutionUpdate(finalHistory)
    }
}
