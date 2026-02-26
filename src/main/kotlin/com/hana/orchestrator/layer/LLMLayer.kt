package com.hana.orchestrator.layer

import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend

/**
 * LLM 텍스트 생성·분석 레이어
 *
 * 목적: LLM을 이용한 텍스트 생성, 요약, 번역, 분석, 질문 답변 등 모든 언어 처리 작업
 *
 * 언제 사용해야 하는가:
 * - 텍스트를 요약해야 할 때 → analyze(context=파일내용, query="요약해줘")
 * - 텍스트를 번역해야 할 때 → analyze(context=원문, query="한국어로 번역해줘")
 * - 파일/커밋 내용을 분석해야 할 때 → analyze(context={{parent}}, query="분석해줘")
 * - 일반 지식 질문에 답할 때 → answerDirectly(query=질문)
 * - 창작(시, 글 등)이 필요할 때 → answerDirectly(query="시를 써줘")
 *
 * 다른 레이어의 결과를 LLM으로 처리할 때:
 * 예: file-system.readFile → llm.analyze(context={{parent}}, query="핵심 2줄 요약")
 * 예: git.log → llm.analyze(context={{parent}}, query="마지막 커밋을 한 줄로 요약")
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
