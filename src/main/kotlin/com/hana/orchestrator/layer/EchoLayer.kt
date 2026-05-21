package com.hana.orchestrator.layer

/**
 * 문자열 그대로 반환 레이어 (테스트·디버그 전용)
 *
 * ⛔ 이 레이어는 입력 문자열을 그대로 반환할 뿐입니다. 사이드이펙트 없음.
 *    - 파일을 읽거나 쓰지 않습니다 → file-system 레이어를 사용하세요
 *    - 코드를 생성하거나 분석하지 않습니다 → llm 레이어를 사용하세요
 *    - 명령을 실행하지 않습니다 → build 레이어를 사용하세요
 *
 * 올바른 사용: "Hello"처럼 고정 문자열을 결과로 출력하고 싶을 때만 사용
 * 잘못된 사용: 작업 안내 메시지 출력, 파일 읽기, 코드 실행 등
 */
@Layer
class EchoLayer : CommonLayerInterface {

    /**
     * message 문자열을 그대로 반환합니다.
     * ⛔ 파일을 읽거나 실행하지 않습니다. message에 넣은 문자열이 결과로 그대로 나옵니다.
     * 올바른 예: echo(message="Hello") → "Hello" 반환
     * 잘못된 예: echo(message="파일을 읽어서 분석합니다") → 분석 안 함, 그 문장이 그대로 반환될 뿐
     */
    @LayerFunction
    suspend fun echo(message: String): String = message

    /**
     * message 문자열을 times회 반복하여 반환합니다.
     * times가 0 이하인 경우 빈 문자열을 반환합니다.
     */
    @LayerFunction
    suspend fun repeat(message: String, times: Int = 1): String {
        if (times <= 0) return ""
        return message.repeat(times)
    }

    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview =
        ApprovalPreview(path = "echo.$function", oldContent = null, newContent = "", kind = ApprovalKind.READ_ONLY)

    override suspend fun describe(): LayerDescription = EchoLayer_Description.layerDescription

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val message = when (val msg = args["message"] as? String ?: args["query"] as? String) {
            null -> ""
            else -> msg
        }
        return when (function) {
            "echo" -> echo(message)
            "repeat" -> {
                val times = when (val arg = args["times"]) {
                    is Number -> arg.toInt()
                    is String -> arg.toIntOrNull() ?: 1
                    else -> 1
                }
                repeat(message, times)
            }
            else -> throw IllegalArgumentException("Unknown function: $function. Available: echo, repeat")
        }
    }
}