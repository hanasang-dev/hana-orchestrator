package com.hana.orchestrator.layer

import com.hana.orchestrator.llm.strategy.ModelSelectionStrategy
import com.hana.orchestrator.llm.useSuspend

/**
 * LLM 텍스트 생성·분석·코드 작성 레이어
 *
 * 목적: LLM을 이용한 모든 언어 처리 — 요약, 번역, 분석, 코드 생성·수정, 질문 답변 등
 *
 * 언제 사용해야 하는가:
 * - 텍스트를 요약·번역·분석해야 할 때 → analyze(context=내용, query="요약해줘")
 * - 소스코드에 함수를 추가·수정해야 할 때 → analyze(context=기존코드, query="uppercase() 함수를 추가한 전체 코드를 작성해줘")
 * - 새 코드를 생성해야 할 때 → analyze(context="", query="Kotlin으로 ... 클래스를 작성해줘")
 * - 일반 지식 질문이나 창작이 필요할 때 → answerDirectly(query=질문)
 *
 * 데이터 변환 후 저장이 필요하면, 저장 노드를 이 레이어 노드의 children에 연결하세요.
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
     * 컨텍스트를 바탕으로 LLM이 분석·생성·코드 작성을 수행합니다.
     * 다른 레이어의 결과(파일 내용, 커밋 로그 등)를 받아 처리하거나, 소스코드를 생성·수정할 때 사용합니다.
     * 코드 수정 시에는 query에 "전체 파일 코드를 작성해줘"처럼 완성된 코드 출력을 요청하세요.
     *
     * @param context 처리할 입력 내용 (파일 내용, 커밋 로그, 원문 등)
     * @param query 수행할 작업 (요약, 번역, 함수 추가, 코드 생성 등)
     * @return LLM이 생성한 결과 (텍스트 또는 소스코드)
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
