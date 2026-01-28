package com.hana.orchestrator.llm

/**
 * LLMClient 확장 함수
 * 
 * suspend 함수용 use 패턴 지원
 * 작업 완료 후 자동으로 리소스 정리
 */

/**
 * suspend 함수용 use 패턴
 * 
 * 리소스를 사용한 후 자동으로 close()를 호출하여 정리
 * 
 * @param block 리소스를 사용하는 suspend 블록
 * @return 블록의 실행 결과
 * 
 * @example
 * ```kotlin
 * val result = client.useSuspend { client ->
 *     client.extractParameters(...)
 * }  // 자동으로 close() 호출됨
 * ```
 */
suspend inline fun <T : LLMClient, R> T.useSuspend(
    crossinline block: suspend (T) -> R
): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        try {
            close()
        } catch (closeException: Throwable) {
            // close() 실패 시 원래 예외를 유지하되, close 예외도 로깅
            if (exception == null) {
                throw closeException
            } else {
                // 원래 예외를 던지되, close 예외는 suppressed로 추가
                exception.addSuppressed(closeException)
                throw exception
            }
        }
    }
}
