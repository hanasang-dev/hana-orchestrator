package com.hana.orchestrator.layer

/**
 * 사용자를 이름으로 인사하는 레이어
 *
 * 목적: Greeting layer for users.
 */
@Layer
class GreeterLayer : CommonLayerInterface {

    /**
     * 이름을 받아 인사 메시지를 반환합니다.
     *
     * @param name 인사할 대상 이름
     */
    @LayerFunction
    suspend fun hello(name: String): String {
        return "안녕하세요, ${name}님! 반갑습니다 👋"
    }

    override suspend fun describe(): LayerDescription {
        return GreeterLayer_Description.layerDescription
    }

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "hello" -> hello(args["name"] as? String ?: args["input"] as? String ?: "")
            else -> "Unknown function: $function. Available: \"hello\""
        }
    }
}
