# runBlocking 사용 분석 및 개선

## 현재 runBlocking 사용 위치

### 1. Application.kt:48 - main() 함수 ✅ **안전함**
```kotlin
runBlocking {
    val cleanupResult = PortAllocator.cleanupHanaPorts()
    ...
}
```
**분석:**
- `main()` 함수는 진입점이므로 블로킹이 필요합니다.
- `cleanupHanaPorts()`가 suspend 함수이므로 `runBlocking`이 적절합니다.
- **문제 없음**: 진입점에서의 `runBlocking`은 정상적인 사용입니다.

### 2. Application.kt:264, 270 - gracefulShutdown() ⚠️ **개선됨**
```kotlin
// Application scope 취소
applicationScope.cancel()
// 별도 스레드에서 실행하여 데드락 방지
val orchestratorCloseThread = Thread {
    try {
        kotlinx.coroutines.runBlocking {
            orchestrator.close()
        }
    } catch (e: Exception) {
        // 무시
    }
}
orchestratorCloseThread.start()
orchestratorCloseThread.join(3000)
```

**분석:**
- shutdown hook 스레드에서 실행됩니다.
- 이미 취소된 scope와 상호작용할 때 데드락 위험이 있었습니다.
- **개선**: 별도 스레드에서 `runBlocking`을 실행하여 데드락 방지
- 타임아웃(3초) 추가로 무한 대기 방지

### 3. ServiceDiscovery.kt:173 - close() ⚠️ **개선됨**
```kotlin
fun close() {
    val thread = Thread {
        kotlinx.coroutines.runBlocking {
            httpClient.close()
        }
    }
    thread.start()
    thread.join(5000) // 최대 5초 대기
}
```

**분석:**
- shutdown hook에서 호출될 수 있습니다.
- `httpClient.close()`는 suspend 함수입니다.
- **개선**: 별도 스레드에서 실행하여 데드락 방지
- 타임아웃(5초) 추가로 무한 대기 방지
- `closeAsync()` suspend 함수 버전도 제공

## runBlocking 사용 가이드라인

### ✅ 안전한 사용
1. **진입점 (main 함수)**: 블로킹이 필요하므로 사용 가능
2. **테스트 코드**: 테스트 환경에서는 사용 가능
3. **별도 스레드**: shutdown hook 등에서 별도 스레드로 실행

### ⚠️ 주의가 필요한 사용
1. **suspend 함수 내부**: 데드락 위험
2. **이미 취소된 scope와 상호작용**: 데드락 위험
3. **메인 스레드에서 장시간 실행**: UI 블로킹

### ❌ 피해야 할 사용
1. **코루틴 컨텍스트 내부에서 직접 사용**: 데드락 위험
2. **이미 실행 중인 코루틴 내부**: 데드락 위험
3. **타임아웃 없는 장시간 작업**: 무한 대기

## 개선 사항 요약

1. **gracefulShutdown**: 별도 스레드에서 `runBlocking` 실행
2. **ServiceDiscovery.close()**: 별도 스레드에서 `runBlocking` 실행 + 타임아웃
3. **main() 함수**: 변경 없음 (안전한 사용)

## 추가 권장 사항

### 더 나은 대안 (향후 개선)
1. **gracefulShutdown을 suspend 함수로 변경**
   ```kotlin
   suspend fun gracefulShutdownAsync(...) {
       // suspend 함수로 구현
   }
   
   // shutdown hook에서만 runBlocking 사용
   Runtime.getRuntime().addShutdownHook(Thread {
       runBlocking {
           gracefulShutdownAsync(...)
       }
   })
   ```

2. **ServiceDiscovery.closeAsync() 사용 권장**
   - 일반적인 경우에는 suspend 함수 버전 사용
   - shutdown hook에서만 동기 버전 사용

3. **타임아웃 명시**
   - 모든 `runBlocking` 사용에 타임아웃 고려
   - `withTimeout` 또는 별도 스레드의 `join(timeout)` 사용
