# 코드 리뷰 보고서 - DRY, KISS, YAGNI, OOP 관점

## 🔴 심각한 문제점

### 1. DRY 위반: 중복된 패턴들

#### 1.1 실패 이력 저장 패턴 중복 (8회 이상)
**위치**: `OrchestrationCoordinator.kt`
- 100-107줄, 122-129줄, 142-147줄, 163-170줄, 179-184줄, 436-443줄, 505-512줄, 525-532줄

**해결 방안**: 헬퍼 메서드 추출
```kotlin
private suspend fun saveAndEmitFailedHistory(
    executionId: String,
    query: String,
    error: String,
    startTime: Long
): ExecutionHistory {
    val failedHistory = ExecutionHistory.createFailed(
        executionId, query, error, startTime,
        logs = historyManager.getCurrentLogs()
    )
    historyManager.addHistory(failedHistory)
    statePublisher.emitExecutionUpdate(failedHistory)
    return failedHistory
}
```

#### 1.2 재시도 시작 패턴 중복 (3회)
**위치**: `OrchestrationCoordinator.kt` (111-114줄, 133-136줄, 472-475줄)

**해결 방안**: 헬퍼 메서드 추출
```kotlin
private suspend fun prepareRetry(
    executionId: String,
    query: String
): ExecutionHistory {
    val newRunningHistory = ExecutionHistory.createRunning(
        executionId, query, System.currentTimeMillis()
    )
    newRunningHistory.logs.addAll(historyManager.getCurrentLogs())
    historyManager.setCurrentExecution(newRunningHistory)
    statePublisher.emitExecutionUpdate(newRunningHistory)
    return newRunningHistory
}
```

#### 1.3 로그 emit 패턴 중복
**위치**: 여러 곳에서 반복

**해결 방안**: `ExecutionHistoryManager`에 통합
```kotlin
fun emitCurrentExecution() {
    currentExecution?.let { statePublisher.emitExecutionUpdateAsync(it) }
}
```

### 2. KISS 위반: 복잡한 로직

#### 2.1 `executeOrchestration` 메서드가 너무 복잡함 (200줄 이상)
**문제점**:
- 중첩된 조건문
- 재시도 로직이 메인 로직과 혼재
- 평가 결과 처리 로직이 복잡함

**해결 방안**: 메서드 분리
```kotlin
suspend fun executeOrchestration(query: String): ExecutionResult {
    // 초기화
    val context = initializeExecution(query)
    
    // 재시도 루프
    while (context.shouldRetry()) {
        try {
            val result = executeAttempt(context)
            if (shouldComplete(result, context)) {
                return completeExecution(result, context)
            }
            prepareRetry(context, result)
        } catch (e: Exception) {
            if (!handleException(e, context)) {
                return failExecution(context, e)
            }
        }
    }
    
    return failExecution(context, Exception("최대 재시도 횟수 도달"))
}
```

#### 2.2 평가 결과 처리 로직 중복 및 복잡
**문제점**: 
- 93-157줄: `needsRetry` 체크가 두 번 나타남
- 로직이 중복되어 혼란

**해결 방안**: 단순화
```kotlin
private suspend fun handleEvaluationResult(
    evaluation: ResultEvaluation,
    result: ExecutionResult,
    context: ExecutionContext
): ExecutionResult? {
    if (evaluation.isSatisfactory && !evaluation.needsRetry) {
        return result // 성공
    }
    
    if (evaluation.needsRetry && context.canRetry()) {
        prepareRetry(context, result)
        return null // 재시도 계속
    }
    
    return result // 최종 실패
}
```

### 3. YAGNI 위반: 사용되지 않는 코드

#### 3.1 `buildFeasibilityCheckPrompt` 미사용
**위치**: `LLMPromptBuilder.kt` (73-100줄)
- Feasibility 체크가 제거되어 더 이상 사용되지 않음
- `OllamaLLMClient.kt`에서 호출되지만 실제로는 호출되지 않음

**해결 방안**: 제거
- `LLMPromptBuilder.buildFeasibilityCheckPrompt()` 제거
- `OllamaLLMClient.validateQueryFeasibility()` 제거 (또는 사용처 확인)

#### 3.2 `FallbackTreeFactory` 미사용
**위치**: `FallbackTreeFactory.kt`
- 정의되어 있으나 사용되지 않음

**해결 방안**: 제거 또는 사용처 추가

### 4. OOP 위반: 책임 분산

#### 4.1 ExecutionHistory 생성/저장/emit 패턴이 여러 곳에 분산
**문제점**: 상태 전이 로직이 `OrchestrationCoordinator`에 집중

**해결 방안**: `ExecutionHistoryManager`에 통합
```kotlin
class ExecutionHistoryManager {
    suspend fun saveAndEmitFailed(
        executionId: String,
        query: String,
        error: String,
        startTime: Long,
        statePublisher: ExecutionStatePublisher
    ): ExecutionHistory {
        val failedHistory = ExecutionHistory.createFailed(
            executionId, query, error, startTime,
            logs = getCurrentLogs()
        )
        addHistory(failedHistory)
        statePublisher.emitExecutionUpdate(failedHistory)
        return failedHistory
    }
    
    suspend fun prepareRetry(
        executionId: String,
        query: String,
        statePublisher: ExecutionStatePublisher
    ): ExecutionHistory {
        val newRunningHistory = ExecutionHistory.createRunning(
            executionId, query, System.currentTimeMillis()
        )
        newRunningHistory.logs.addAll(getCurrentLogs())
        setCurrentExecution(newRunningHistory)
        statePublisher.emitExecutionUpdate(newRunningHistory)
        return newRunningHistory
    }
}
```

## 📋 우선순위별 개선 사항

### 높은 우선순위 (즉시 수정 권장)
1. ✅ 실패 이력 저장 패턴 중복 제거 (DRY)
2. ✅ 재시도 시작 패턴 중복 제거 (DRY)
3. ✅ `buildFeasibilityCheckPrompt` 제거 (YAGNI)
4. ✅ `FallbackTreeFactory` 제거 또는 사용처 추가 (YAGNI)

### 중간 우선순위 (점진적 개선)
5. ⚠️ `executeOrchestration` 메서드 분리 (KISS)
6. ⚠️ 평가 결과 처리 로직 단순화 (KISS)
7. ⚠️ ExecutionHistory 관리 로직 통합 (OOP)

### 낮은 우선순위 (리팩토링 시 고려)
8. 💡 로그 emit 패턴 통합 (DRY)
9. 💡 상태 전이 로직 캡슐화 (OOP)
