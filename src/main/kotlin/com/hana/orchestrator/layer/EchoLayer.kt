package com.hana.orchestrator.layer

/**
 * 사용자 입력을 그대로 echo하는 간단한 레이어
 */
class EchoLayer : CommonLayerInterface {
    
    override suspend fun describe(): LayerDescription {
        return LayerDescription(
            name = "echo-layer",
            description = "사용자 입력을 그대로 반환하는 echo 기능",
            layerDepth = 0,
            functions = listOf("echo", "repeat")
        )
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "echo" -> {
                val message = args["message"] as? String ?: ""
                "Echo: $message"
            }
            "repeat" -> {
                val message = args["message"] as? String ?: ""
                val times = (args["times"] as? String)?.toIntOrNull() ?: 1
                val repeated = message.repeat(times)
                "Repeated: $repeated"
            }
            else -> "Unknown function: $function. Available: echo, repeat"
        }
    }
}