package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.domain.entity.ExecutionTree
import com.hana.orchestrator.domain.entity.ExecutionHistory
import com.hana.orchestrator.domain.entity.ExecutionResult
import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend
import com.hana.orchestrator.llm.QueryFeasibility

/**
 * 재처리 방안 요청 실패 예외 (무한 루프 방지용)
 */
class RetryStrategyRequestFailedException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
    private val logger = createOrchestratorLogger(OrchestrationCoordinator::class.java, historyManager)
    
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
                logger.info("🔄 [OrchestrationCoordinator] 실행 시도 #$attemptCount")
                
                try {
                    // 매번 feasibility check 수행 (LLM 레이어 포함하여 모든 레이어 고려)
                    val feasibility = validateFeasibility(query, allDescriptions)
                    
                    // LLM으로 트리 생성 (모든 레이어 포함, LLM이 자동으로 선택)
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
                        logger.info("✅ [OrchestrationCoordinator] 실행 성공: 요구사항 부합")
                        
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
                    
                    // 평가 실패 또는 기타 경우
                    // needsRetry가 true면 재처리 시도, false면 종료
                    if (evaluation.needsRetry && attemptCount < maxAttempts) {
                        // 재처리 필요: 실패 이력 저장 후 재시도
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
                    
                    // 재처리 불필요 또는 최대 재시도 도달: 최종 이력 저장 후 종료
                    val finalHistory = if (!evaluation.isSatisfactory) {
                        ExecutionHistory.createFailed(
                            executionId, query,
                            "요구사항 미부합: ${evaluation.reason}",
                            startTime,
                            logs = historyManager.getCurrentLogs()
                        )
                    } else {
                        ExecutionHistory.createCompleted(
                            executionId, query, result, startTime,
                            logs = historyManager.getCurrentLogs()
                        )
                    }
                    historyManager.addHistory(finalHistory)
                    statePublisher.emitExecutionUpdate(finalHistory)
                    historyManager.clearCurrentExecution()
                    return result
                    
                } catch (e: Exception) {
                    // 재처리 방안 요청 실패 시 더 이상 재시도하지 않음
                    if (e is RetryStrategyRequestFailedException) {
                        logger.error("🛑 [OrchestrationCoordinator] 재처리 방안 요청 실패로 인한 중단: ${e.message}")
                        val finalFailedHistory = ExecutionHistory.createFailed(
                            executionId, query,
                            e.message ?: "재처리 방안 요청 실패",
                            startTime,
                            logs = historyManager.getCurrentLogs()
                        )
                        historyManager.addHistory(finalFailedHistory)
                        statePublisher.emitExecutionUpdate(finalFailedHistory)
                        historyManager.clearCurrentExecution()
                        return ExecutionResult(result = "", error = e.message)
                    }
                    
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
            logger.warn("🛑 [OrchestrationCoordinator] 최대 재시도 횟수 도달")
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
     * 요구사항 실행 가능성 검증
     * 참고용으로만 사용 (트리 생성에는 영향 없음, LLM이 모든 레이어를 보고 선택)
     */
    private suspend fun validateFeasibility(
        query: String,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>
    ): QueryFeasibility {
        logger.info("🔎 [OrchestrationCoordinator] 요구사항 실행 가능성 검증 중...")
        
        val feasibilityStartTime = System.currentTimeMillis()
        val feasibility = modelSelectionStrategy.selectClientForFeasibilityCheck()
            .useSuspend { client ->
                client.validateQueryFeasibility(query, allDescriptions)
            }
        
        val feasibilityDuration = System.currentTimeMillis() - feasibilityStartTime
        logger.perf("⏱️ [PERF] 요구사항 검증 완료: ${feasibilityDuration}ms")
        
        // 로그 타이밍 문제 해결: perf 로그 후 즉시 UI 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
        if (feasibility.feasible) {
            logger.info("✅ [OrchestrationCoordinator] 요구사항 실행 가능: ${feasibility.reason}")
        } else {
            logger.info("ℹ️ [OrchestrationCoordinator] 레이어로 실행 불가능 (LLM 레이어 사용 가능): ${feasibility.reason}")
        }
        
        return feasibility
    }
    
    /**
     * 초기 트리 생성
     * LLM이 모든 레이어(LLM 레이어 포함)를 보고 자동으로 선택
     */
    private suspend fun createInitialTree(
        query: String,
        allDescriptions: List<com.hana.orchestrator.layer.LayerDescription>,
        executionId: String,
        startTime: Long
    ): ExecutionTree {
        logger.info("🌳 [OrchestrationCoordinator] 실행 트리 생성 시작...")
        
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
        logger.perf("⏱️ [PERF] 트리 생성 완료: ${treeDuration}ms")
        
        // 로그 타이밍 문제 해결: perf 로그 후 즉시 UI 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
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
        logger.info("🔧 [OrchestrationCoordinator] 재처리 방안 요청 중...")
        // 로그 emit을 즉시 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
        val retryStartTime = System.currentTimeMillis()
        val retryStrategy = try {
            modelSelectionStrategy.selectClientForRetryStrategy()
                .useSuspend { client ->
                    client.suggestRetryStrategy(query, previousHistory, allDescriptions)
                }
        } catch (retryException: Exception) {
            handleRetryStrategyFailure(retryException, query, executionId, startTime)
            // 재처리 방안 요청 실패 시 더 이상 재시도하지 않도록 특별한 예외로 변환
            throw RetryStrategyRequestFailedException("재처리 방안 요청 실패: ${retryException.message}", retryException)
        }
        
        val retryDuration = System.currentTimeMillis() - retryStartTime
        logger.perf("⏱️ [PERF] 재처리 방안 생성: ${retryDuration}ms")
        
        if (retryStrategy.shouldStop) {
            handleRetryStop(retryStrategy.reason, query, executionId, startTime)
            throw Exception("재처리 중단: ${retryStrategy.reason}")
        }
        
        logger.info("✅ [OrchestrationCoordinator] 재처리 방안 수신: ${retryStrategy.reason}")
        
        return retryStrategy.newTree ?: throw Exception("재처리 트리가 null입니다")
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
        val rootNodesInfo = rawTree.rootNodes.joinToString(", ") { "${it.layerName}.${it.function}" }
        logger.info("🌳 [OrchestrationCoordinator] 실행 트리: 루트 노드 ${rawTree.rootNodes.size}개 [$rootNodesInfo]")
        
        // 트리 검증 및 자동 수정
        val validationStartTime = System.currentTimeMillis()
        val validator = ExecutionTreeValidator(allDescriptions)
        val validationResult = validator.validateAndFix(rawTree, query)
        val validationDuration = System.currentTimeMillis() - validationStartTime
        logger.perf("⏱️ [PERF] 트리 검증 완료: ${validationDuration}ms")
        
        // 로그 타이밍 문제 해결: perf 로그 후 즉시 UI 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
        if (validationResult.errors.isNotEmpty()) {
            val errorMsg = "❌ [OrchestrationCoordinator] 트리 검증 실패: ${validationResult.errors.joinToString(", ")}"
            logger.error(errorMsg)
            throw Exception("트리 검증 실패: ${validationResult.errors.joinToString(", ")}")
        }
        
        val treeToExecute = validationResult.fixedTree ?: rawTree
        
        if (validationResult.warnings.isNotEmpty()) {
            // 경고가 많으면 요약해서 표시
            val warningsText = if (validationResult.warnings.size > 3) {
                validationResult.warnings.take(3).joinToString(", ") + " 외 ${validationResult.warnings.size - 3}개"
            } else {
                validationResult.warnings.joinToString(", ")
            }
            logger.warn("⚠️ [OrchestrationCoordinator] 트리 검증 경고: $warningsText")
        }
        
        // 트리 실행
        logger.info("🚀 [OrchestrationCoordinator] 트리 실행 시작...")
        
        val executionStartTime = System.currentTimeMillis()
        val executionContext = historyManager.getCurrentExecution()!!
        val result = treeExecutor.executeTree(treeToExecute, executionContext)
        
        val executionDuration = System.currentTimeMillis() - executionStartTime
        logger.perf("⏱️ [PERF] 트리 실행 완료: ${executionDuration}ms")
        
        // 로그 타이밍 문제 해결: perf 로그 후 즉시 UI 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
        logger.info("✅ [OrchestrationCoordinator] 트리 실행 완료")
        
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
        logger.info("🤔 [OrchestrationCoordinator] 실행 결과 평가 중...")
        
        val evaluationStartTime = System.currentTimeMillis()
        val evaluation = modelSelectionStrategy.selectClientForEvaluation()
            .useSuspend { client ->
                client.evaluateResult(query, result.result, result.context)
            }
        
        val evaluationDuration = System.currentTimeMillis() - evaluationStartTime
        logger.perf("⏱️ [PERF] 결과 평가 완료: ${evaluationDuration}ms")
        
        // 로그 타이밍 문제 해결: perf 로그 후 즉시 UI 업데이트
        historyManager.getCurrentExecution()?.let { currentExecution ->
            statePublisher.emitExecutionUpdateAsync(currentExecution)
        }
        
        val statusText = if (evaluation.isSatisfactory) "✅ 요구사항 부합" else "⚠️ 요구사항 미부합"
        val reasonText = evaluation.reason.take(100) // 너무 긴 이유는 자름
        logger.info("📊 [OrchestrationCoordinator] 평가 결과: $statusText - $reasonText")
        
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
        
        logger.info("🔍 [OrchestrationCoordinator] 이전 실행과 비교 중...")
        
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
        logger.perf("⏱️ [PERF] 실행 비교 완료: ${comparisonDuration}ms")
        
        if (!comparison.isSignificantlyDifferent) {
            logger.warn("⚠️ [OrchestrationCoordinator] 유의미한 변경 없음: ${comparison.reason}")
            logger.warn("🛑 [OrchestrationCoordinator] 무한 루프 방지: 재처리 중단")
            return false
        }
        
        logger.info("✅ [OrchestrationCoordinator] 유의미한 차이 확인: ${comparison.reason}")
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
        logger.error("❌ [OrchestrationCoordinator] 실행 실패: ${e.message}", e)
        
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
            logger.warn("🛑 [OrchestrationCoordinator] 최대 재시도 횟수 도달: 중단")
            return false
        }
        
        // 재처리 방안 요청
        try {
            val prevHistory = previousHistory ?: failedHistory
            logger.info("🔧 [OrchestrationCoordinator] 실패 분석 및 재처리 방안 요청 중...")
            // 로그 emit을 즉시 업데이트
            historyManager.getCurrentExecution()?.let { currentExecution ->
                statePublisher.emitExecutionUpdateAsync(currentExecution)
            }
            
            val retryStrategy = modelSelectionStrategy.selectClientForRetryStrategy()
                .useSuspend { client ->
                    client.suggestRetryStrategy(query, prevHistory, allDescriptions)
                }
            
            if (retryStrategy.shouldStop) {
                handleRetryStop(retryStrategy.reason, query, executionId, startTime)
                return false
            }
            
            logger.info("✅ [OrchestrationCoordinator] 재처리 방안 수신: ${retryStrategy.reason}")
            
            val newRunningHistory = ExecutionHistory.createRunning(executionId, query, System.currentTimeMillis())
            newRunningHistory.logs.addAll(historyManager.getCurrentLogs())
            historyManager.setCurrentExecution(newRunningHistory)
            statePublisher.emitExecutionUpdate(newRunningHistory)
            return true
        } catch (retryException: Exception) {
            logger.error("❌ [OrchestrationCoordinator] 재처리 방안 요청 실패: ${retryException.message}", retryException)
            
            // 재처리 방안 요청 실패 시 기존 failedHistory 업데이트 (에러 메시지와 로그 추가)
            val updatedLogs = historyManager.getCurrentLogs().toMutableList()
            val updatedFailedHistory = failedHistory.copy(
                result = failedHistory.result.copy(
                    error = "${failedHistory.result.error}\n재처리 방안 요청 실패: ${retryException.message}"
                ),
                logs = updatedLogs
            )
            // 기존 이력을 업데이트된 것으로 교체
            historyManager.updateHistory(updatedFailedHistory)
            statePublisher.emitExecutionUpdate(updatedFailedHistory)
            return false
        }
    }
    
    // Helper methods for error handling
    
    private suspend fun handleTreeCreationFailure(
        e: Exception,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        logger.error("❌ [OrchestrationCoordinator] 트리 생성 실패: ${e.message}", e)
        
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
        val errorMessage = e.message ?: "알 수 없는 오류"
        logger.error("❌ [OrchestrationCoordinator] 재처리 방안 요청 실패: $errorMessage", e)
        
        // 재처리 방안 요청 실패 이력 저장 (중복 제거)
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query,
            "재처리 방안 요청 실패: $errorMessage",
            startTime,
            logs = historyManager.getCurrentLogs()
        )
        historyManager.addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
        // 로그 emit을 즉시 업데이트
        statePublisher.emitExecutionUpdateAsync(failedHistory)
    }
    
    private suspend fun handleRetryStop(
        reason: String,
        query: String,
        executionId: String,
        startTime: Long
    ) {
        logger.warn("🛑 [OrchestrationCoordinator] LLM 판단: 근본 해결 불가능 - $reason")
        
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
