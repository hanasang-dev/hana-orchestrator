package com.hana.orchestrator.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON Schema 생성 유틸리티
 * Ollama Structured Outputs를 위한 JSON Schema 생성
 * 
 * 2026년 기준: 프롬프트만으로는 부족하므로 Ollama의 format 파라미터를 활용하여
 * 스키마를 강제하는 것이 가장 효과적입니다.
 * 
 * DRY 원칙: 공통 패턴을 헬퍼 함수로 추출하여 중복 제거
 */
internal object JsonSchemaBuilder {
    
    // ========== 헬퍼 함수 (DRY 원칙 적용) ==========
    
    /**
     * 객체 타입 JSON Schema 생성
     */
    private fun createObjectSchema(
        required: List<String>,
        properties: Map<String, JsonObject>
    ): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "required" to createRequiredArray(required),
                "properties" to JsonObject(properties)
            )
        )
    }
    
    /**
     * required 필드 배열 생성
     */
    private fun createRequiredArray(fields: List<String>): JsonArray {
        return JsonArray(fields.map { JsonPrimitive(it) })
    }
    
    /**
     * boolean 타입 프로퍼티 생성
     */
    private fun createBooleanProperty(description: String): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("boolean"),
                "description" to JsonPrimitive(description)
            )
        )
    }
    
    /**
     * boolean 타입 프로퍼티 생성 (기본값 포함)
     */
    private fun createBooleanProperty(description: String, defaultValue: Boolean): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("boolean"),
                "description" to JsonPrimitive(description),
                "default" to JsonPrimitive(defaultValue)
            )
        )
    }
    
    /**
     * string 타입 프로퍼티 생성
     */
    private fun createStringProperty(description: String): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive(description)
            )
        )
    }
    
    /**
     * string 타입 프로퍼티 생성 (enum 포함)
     */
    private fun createStringProperty(description: String, enum: List<String>): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("string"),
                "description" to JsonPrimitive(description),
                "enum" to JsonArray(enum.map { JsonPrimitive(it) } as List<kotlinx.serialization.json.JsonElement>)
            )
        )
    }
    
    /**
     * array 타입 프로퍼티 생성
     */
    private fun createArrayProperty(description: String, items: JsonObject): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive(description),
                "items" to items
            )
        )
    }
    
    /**
     * array 타입 프로퍼티 생성 (기본값 포함)
     */
    private fun createArrayProperty(description: String, items: JsonObject, defaultValue: JsonArray): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive(description),
                "items" to items,
                "default" to defaultValue
            )
        )
    }
    
    /**
     * object 타입 프로퍼티 생성 (additionalProperties 포함)
     */
    private fun createObjectProperty(description: String, additionalProperties: Boolean = true): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "description" to JsonPrimitive(description),
                "additionalProperties" to JsonPrimitive(additionalProperties)
            )
        )
    }
    
    // ========== 공개 API ==========
    
    /**
     * ResultEvaluation에 대한 JSON Schema
     */
    fun buildResultEvaluationSchema(): JsonObject {
        return createObjectSchema(
            required = listOf("isSatisfactory", "reason", "needsRetry"),
            properties = mapOf(
                "isSatisfactory" to createBooleanProperty("요구사항 충족 여부"),
                "reason" to createStringProperty("평가 이유"),
                "needsRetry" to createBooleanProperty("재처리 필요 여부")
            )
        )
    }
    
    /**
     * RetryStrategyResponse에 대한 JSON Schema
     */
    fun buildRetryStrategySchema(availableLayerNames: List<String> = emptyList()): JsonObject {
        // newTree는 ExecutionTreeResponse와 동일한 구조이므로 재사용
        val executionTreeSchema = if (availableLayerNames.isNotEmpty()) {
            buildExecutionTreeSchema(availableLayerNames)
        } else {
            // availableLayerNames가 없으면 간단한 스키마만 제공
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "rootNodes" to createArrayProperty(
                                description = "루트 노드 배열",
                                items = JsonObject(mapOf("type" to JsonPrimitive("object")))
                            )
                        )
                    )
                )
            )
        }
        
        return createObjectSchema(
            required = listOf("shouldStop", "reason"),
            properties = mapOf(
                "shouldStop" to createBooleanProperty("재처리 중단 여부"),
                "reason" to createStringProperty("중단/재처리 이유"),
                "newTree" to executionTreeSchema
            )
        )
    }
    
    /**
     * ExecutionTreeResponse에 대한 JSON Schema
     * (동적으로 레이어 이름 목록을 받아서 생성)
     */
    fun buildExecutionTreeSchema(availableLayerNames: List<String>): JsonObject {
        return createObjectSchema(
            required = listOf("rootNodes"),
            properties = mapOf(
                "rootNodes" to createArrayProperty(
                    description = "루트 노드 배열",
                    items = buildExecutionNodeSchema(availableLayerNames)
                )
            )
        )
    }
    
    /**
     * ExecutionNode에 대한 JSON Schema
     * 재귀적 참조를 피하기 위해 children의 items는 간단한 객체 스키마로 정의
     */
    private fun buildExecutionNodeSchema(availableLayerNames: List<String>): JsonObject {
        val layerNameSchema = if (availableLayerNames.isNotEmpty()) {
            createStringProperty(
                description = "레이어 이름: ${availableLayerNames.joinToString(", ")}",
                enum = availableLayerNames
            )
        } else {
            createStringProperty("레이어 이름")
        }
        
        return createObjectSchema(
            required = listOf("layerName", "function", "args", "parallel", "children"),
            properties = mapOf(
                "layerName" to layerNameSchema,
                "function" to createStringProperty("함수 이름"),
                "args" to createObjectProperty("파라미터 맵"),
                "parallel" to createBooleanProperty("병렬 실행 여부", defaultValue = false),
                "children" to createArrayProperty(
                    description = "자식 노드 배열 (동일한 구조)",
                    items = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "description" to JsonPrimitive("자식 노드는 부모 노드와 동일한 구조를 가집니다")
                        )
                    ),
                    defaultValue = JsonArray(emptyList())
                )
            )
        )
    }
    
    /**
     * ComparisonResult에 대한 JSON Schema
     */
    fun buildComparisonResultSchema(): JsonObject {
        return createObjectSchema(
            required = listOf("isSignificantlyDifferent", "reason"),
            properties = mapOf(
                "isSignificantlyDifferent" to createBooleanProperty("유의미한 차이 여부"),
                "reason" to createStringProperty("차이 이유")
            )
        )
    }
    
    /**
     * LLMDirectAnswerCapability에 대한 JSON Schema
     */
    fun buildLLMDirectAnswerCapabilitySchema(): JsonObject {
        return createObjectSchema(
            required = listOf("canAnswer", "reason"),
            properties = mapOf(
                "canAnswer" to createBooleanProperty("직접 답변 가능 여부"),
                "reason" to createStringProperty("이유")
            )
        )
    }
}
