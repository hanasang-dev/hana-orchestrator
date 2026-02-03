package com.hana.orchestrator.layer

import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend

/**
 * LLM 직접 답변 레이어
 * 
 * 목적: 레이어로 실행할 수 없는 요청에 대해 LLM이 직접 답변을 제공
 * 
 * 이 레이어는 다른 레이어와 조합하여 사용할 수 있습니다.
 * 예: layer-info.listLayers → llm.answerDirectly (레이어 정보를 LLM이 분석하여 답변)
 */
@Layer
class LLMLayer(
    private val modelSelectionStrategy: ModelSelectionStrategy
) : CommonLayerInterface {
    
    /**
     * 사용자 요청에 대해 LLM이 직접 답변을 생성합니다.
     * 레이어로 실행할 수 없는 일반 지식 질문이나 설명 요청에 사용됩니다.
     * 
     * @param query 사용자 요청
     * @return LLM이 생성한 답변
     */
    @LayerFunction
    suspend fun answerDirectly(query: String): String {
        return modelSelectionStrategy.selectClientForGenerateDirectAnswer()
            .useSuspend { client ->
                client.generateDirectAnswer(query)
            }
    }
    
    /**
     * 컨텍스트를 포함하여 LLM이 분석하고 답변을 생성합니다.
     * 다른 레이어의 결과를 받아서 LLM이 분석하는 경우에 사용됩니다.
     * 
     * @param context 컨텍스트 정보 (예: 다른 레이어의 실행 결과)
     * @param query 분석할 요청
     * @return LLM이 생성한 답변
     */
    @LayerFunction
    suspend fun analyze(context: String, query: String): String {
        val combinedQuery = """
            컨텍스트:
            $context
            
            요청: $query
            
            위 컨텍스트를 바탕으로 요청에 답변해주세요.
        """.trimIndent()
        
        return modelSelectionStrategy.selectClientForGenerateDirectAnswer()
            .useSuspend { client ->
                client.generateDirectAnswer(combinedQuery)
            }
    }

    override suspend fun describe(): LayerDescription {
        return LLMLayer_Description.layerDescription
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "answerDirectly" -> {
                val query = (args["query"] as? String) ?: (args["message"] as? String) ?: ""
                answerDirectly(query)
            }
            "analyze" -> {
                val context = (args["context"] as? String) ?: ""
                val query = (args["query"] as? String) ?: (args["message"] as? String) ?: ""
                analyze(context, query)
            }
            else -> "Unknown function: $function. Available: answerDirectly, analyze"
        }
    }
}
