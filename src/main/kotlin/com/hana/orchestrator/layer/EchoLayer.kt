package com.hana.orchestrator.layer

@Layer
class EchoLayer : CommonLayerInterface {
    
    @LayerFunction
    suspend fun echo(message: String): String {
        return message
    }
    
    @LayerFunction
    suspend fun repeat(message: String, times: Int = 1): String {
        return message.repeat(times)
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
