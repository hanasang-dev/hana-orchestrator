# runBlocking 사용 설명 및 대안

## 현재 runBlocking 사용 위치 (2곳만 남음!)

### 1. gracefulShutdown() - shutdown hook용 (Application.kt:322)
```kotlin
private fun gracefulShutdown(...) {
    val shutdownThread = Thread {
        kotlinx.coroutines.runBlocking {
            gracefulShutdownAsync(...)
        }
    }
    shutdownThread.start()
    shutdownThread.join(10000)
}
```

**왜 runBlocking이 필요한가?**
- `Runtime.getRuntime().addShutdownHook()`는 일반 `Thread`를 받습니다
- `Thread`의 `run()` 메서드는 일반 함수이므로 suspend 함수를 직접 호출할 수 없습니다
- shutdown hook 스레드는 JVM이 관리하는 특수 스레드입니다

**대안이 있는가?**
- ❌ 없습니다. JVM의 shutdown hook 메커니즘 자체가 일반 Thread를 요구합니다
- ✅ 하지만 `gracefulShutdownAsync()` suspend 함수 버전을 제공하여 일반적인 경우에는 runBlocking 없이 사용 가능

### 2. ServiceDiscovery.close() - shutdown hook용 (ServiceDiscovery.kt:185)
```kotlin
fun close() {
    val thread = Thread {
        kotlinx.coroutines.runBlocking {
            httpClient.close()
        }
    }
    thread.start()
    thread.join(5000)
}
```

**왜 runBlocking이 필요한가?**
- `close()`는 shutdown hook에서 호출될 수 있습니다
- shutdown hook 스레드에서는 suspend 함수를 직접 호출할 수 없습니다

**대안이 있는가?**
- ✅ `closeAsync()` suspend 함수 버전 제공 (일반적인 경우 사용)
- ❌ shutdown hook에서만 runBlocking 필요 (JVM 제약)

## 개선 사항

### ✅ main() 함수에서 runBlocking 제거!
**이전:**
```kotlin
fun main(args: Array<String>) {
    runBlocking {
        // 작업
    }
}
```

**개선 후:**
```kotlin
suspend fun main(args: Array<String>) {
    // runBlocking 없이 직접 suspend 함수 호출 가능!
    // 컴파일러가 내부적으로 처리합니다
}
```

**왜 가능한가?**
- Kotlin 1.3+부터 `suspend fun main()` 지원
- 컴파일러가 내부적으로 `runBlocking`을 생성하여 처리
- 하지만 우리 코드에서는 직접 `runBlocking`을 사용하지 않습니다!

### ✅ 에러 처리에서 runBlocking 제거!
**이전:**
```kotlin
catch (e: Exception) {
    runBlocking {
        gracefulShutdownAsync(...)
    }
}
```

**개선 후:**
```kotlin
catch (e: Exception) {
    // startApplication이 suspend 함수이므로 직접 호출 가능!
    gracefulShutdownAsync(...)
}
```

## 남은 runBlocking (2곳) - 정말 불가피한 이유

### JVM Shutdown Hook의 제약사항

```java
// JVM의 shutdown hook API
Runtime.getRuntime().addShutdownHook(Thread hook)
```

이 API는:
1. **일반 Thread만 받습니다** - suspend 함수를 지원하지 않습니다
2. **JVM 표준입니다** - 변경할 수 없습니다
3. **특수한 실행 컨텍스트입니다** - 코루틴 스코프가 없습니다

### 왜 별도 스레드에서 실행하는가?

shutdown hook 스레드에서 직접 `runBlocking`을 사용하면:
- 이미 종료 중인 스레드 풀과 충돌 가능
- 데드락 위험
- 타임아웃 없이 무한 대기 가능

따라서:
- ✅ 별도 스레드에서 실행
- ✅ 타임아웃 추가 (`join(10000)`)
- ✅ 에러 처리

## 결론

### runBlocking 사용 현황
- **main() 함수**: ✅ 제거됨 (`suspend fun main()` 사용)
- **에러 처리**: ✅ 제거됨 (suspend 함수로 변경)
- **shutdown hook (2곳)**: ⚠️ **정말 불가피함** (JVM 제약)

### 최종 runBlocking 사용: 2곳
1. `gracefulShutdown()` - shutdown hook용 래퍼
2. `ServiceDiscovery.close()` - shutdown hook용 래퍼

**이 두 곳은 JVM의 shutdown hook 메커니즘 때문에 불가피합니다.**

### 대안 제공
- `gracefulShutdownAsync()` - 일반적인 경우 사용 (runBlocking 없음)
- `ServiceDiscovery.closeAsync()` - 일반적인 경우 사용 (runBlocking 없음)

**일반적인 실행 경로에서는 runBlocking이 전혀 사용되지 않습니다!**
