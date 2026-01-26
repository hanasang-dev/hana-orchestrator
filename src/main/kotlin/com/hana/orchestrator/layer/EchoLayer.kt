package com.hana.orchestrator.layer

/**
 * 사용자 입력을 그대로 echo하는 간단한 레이어
 * KSP 프로세서가 자동으로 LayerDescription 생성
 */
@Layer(
    name = "echo-layer",
    description = "사용자 입력을 그대로 반환하는 echo 기능"
)
class EchoLayer : CommonLayerInterface {
    
    @LayerFunction(
        name = "echo",
        description = "입력된 메시지를 그대로 반환",
        returnType = "string"
    )
    suspend fun echo(message: String): String {
        return "Echo: $message"
    }
    
    @LayerFunction(
        name = "repeat",
        description = "입력된 메시지를 지정된 횟수만큼 반복",
        returnType = "string"
    )
    suspend fun repeat(message: String, times: Int = 1): String {
        val repeated = message.repeat(times)
        return "Repeated: $repeated"
    }
    
    // KSP 프로세서가 자동 생성한 describe() 사용
    override suspend fun describe(): LayerDescription {
        return EchoLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "echo" -> {
                val message = (args["message"] as? String) ?: (args["query"] as? String) ?: ""
                echo(message)
            }
            "repeat" -> {
                val message = (args["message"] as? String) ?: (args["query"] as? String) ?: ""
                val times = (args["times"] as? String)?.toIntOrNull() ?: (args["times"] as? Int) ?: 1
                repeat(message, times)
            }
            else -> "Unknown function: $function. Available: echo, repeat"
        }
    }
}
