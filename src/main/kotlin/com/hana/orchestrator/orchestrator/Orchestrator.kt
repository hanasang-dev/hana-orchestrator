package com.hana.orchestrator.orchestrator

import com.hana.orchestrator.layer.LayerFactory
import com.hana.orchestrator.layer.CommonLayerInterface
import com.hana.orchestrator.llm.OllamaLLMClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class ExecutionTree(
    val rootNode: ExecutionNode,
    val name: String = "execution_plan"
)

data class ExecutionNode(
    val layerName: String,
    val function: String,
    val args: Map<String, Any>,
    val children: List<ExecutionNode> = emptyList(),
    val parallel: Boolean = false
)



class Orchestrator : CommonLayerInterface {
    
    private val layers = mutableListOf<CommonLayerInterface>()
    private val llmClient = OllamaLLMClient()
    
    init {
        initializeDefaultLayers()
    }
    
    private fun initializeDefaultLayers() {
        val defaultLayers = LayerFactory.createDefaultLayers()
        layers.addAll(defaultLayers)
    }
    
    fun registerLayer(layer: CommonLayerInterface) {
        layers.add(layer)
    }
    
    private val cachedDescriptions = mutableSetOf<com.hana.orchestrator.layer.LayerDescription>()
    
    suspend fun getAllLayerDescriptions(): List<com.hana.orchestrator.layer.LayerDescription> {
        if (cachedDescriptions.isEmpty()) {
            cachedDescriptions.addAll(layers.map { it.describe() })
        }
        return cachedDescriptions.toList()
    }
    
    suspend fun executeOnLayer(layerName: String, function: String, args: Map<String, Any> = emptyMap()): String {
        val targetLayer = layers.find { layer ->
            layer.describe().name == layerName
        }
        
        if (targetLayer == null) {
            val availableLayers = layers.map { it.describe().name }
            return "Layer '$layerName' not found. Available layers: $availableLayers"
        }
        
        return targetLayer.execute(function, args)
    }
    
    suspend fun executeOnAllLayers(function: String, args: Map<String, Any> = emptyMap()): List<String> {
        val layerDescriptions = layers.map { it.describe() }
        val sortedLayers = layerDescriptions.map { description ->
            val layer = layers.find { it.describe().name == description.name }!!
            description to layer
        }
        
        return sortedLayers.map { (description, layer) ->
            try {
                val result = layer.execute(function, args)
                "[${description.name}] $result"
            } catch (e: Exception) {
                "[${description.name}] Error: ${e.message}"
            }
        }
    }
    
    override suspend fun describe(): com.hana.orchestrator.layer.LayerDescription {
        val allDescriptions = getAllLayerDescriptions()
        return com.hana.orchestrator.layer.LayerDescription(
            name = "orchestrator",
            description = "등록된 레이어들을 관리하고 실행: ${allDescriptions.map { it.name }}",
            functions = allDescriptions.flatMap { it.functions }
        )
    }
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        val allDescriptions = getAllLayerDescriptions()
        
        // Orchestrator는 사용자 요청(query)을 받아 LLM으로 트리 생성 후 실행
        // function 파라미터는 자식 레이어의 함수명으로 위임할 때 사용
        val query = args["query"] as? String
        
        return if (query != null) {
            // 사용자 요청이 있으면 LLM으로 트리 생성 후 검증 및 실행
            println("🔍 [Orchestrator] 사용자 쿼리 수신: $query")
            val rawTree = llmClient.createExecutionTree(query, allDescriptions)
            println("🌳 [Orchestrator] LLM 트리 생성 완료: rootNode=${rawTree.rootNode.layerName}.${rawTree.rootNode.function}, children=${rawTree.rootNode.children.size}")
            
            // 트리 검증 및 자동 수정
            val validator = ExecutionTreeValidator(allDescriptions)
            val validationResult = validator.validateAndFix(rawTree, query)
            
            // 검증된 트리 실행 (에러가 있으면 수정된 트리 사용)
            val treeToExecute = validationResult.fixedTree ?: rawTree
            
            // 경고가 있으면 로그 출력
            if (validationResult.warnings.isNotEmpty()) {
                println("⚠️ [Orchestrator] 트리 검증 경고:")
                validationResult.warnings.forEach { println("  - $it") }
            }
            
            // 에러가 있으면 로그 출력
            if (validationResult.errors.isNotEmpty()) {
                println("❌ [Orchestrator] 트리 검증 에러:")
                validationResult.errors.forEach { println("  - $it") }
                println("📝 [Orchestrator] 수정된 트리로 실행합니다.")
            } else {
                println("✅ [Orchestrator] 트리 검증 통과")
            }
            
            println("🚀 [Orchestrator] 트리 실행 시작...")
            val result = executeTree(treeToExecute)
            println("✅ [Orchestrator] 트리 실행 완료")
            result
        } else {
            // query가 없으면 자식 레이어의 함수명으로 위임
            val targetLayer = layers.find { it.describe().name == function }
            if (targetLayer != null) {
                executeOnLayer(function, "process", args)
            } else {
                val allFunctions = allDescriptions.flatMap { it.functions }
                "Unknown function: $function. Available: ${allFunctions.joinToString(", ")}"
            }
        }
    }
    
    /**
     * ExecutionTree를 재귀적으로 실행
     */
    private suspend fun executeTree(tree: ExecutionTree): String {
        return executeNode(tree.rootNode)
    }
    
    /**
     * ExecutionNode를 재귀적으로 실행
     */
    private suspend fun executeNode(node: ExecutionNode, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        println("${indent}🎯 [executeNode] 실행: ${node.layerName}.${node.function} (children=${node.children.size}, parallel=${node.parallel})")
        
        val layer = layers.find { it.describe().name == node.layerName }
        
        if (layer == null) {
            println("${indent}❌ [executeNode] 레이어를 찾을 수 없음: ${node.layerName}")
            return "Layer '${node.layerName}' not found"
        }
        
        // 현재 노드 실행
        val result = try {
            println("${indent}▶️ [executeNode] ${node.layerName}.${node.function} 실행 중...")
            val execResult = layer.execute(node.function, node.args)
            println("${indent}✅ [executeNode] ${node.layerName}.${node.function} 완료: ${execResult.take(50)}...")
            execResult
        } catch (e: Exception) {
            println("${indent}❌ [executeNode] ${node.layerName}.${node.function} 에러: ${e.message}")
            "Error executing ${node.layerName}.${node.function}: ${e.message}"
        }
        
        // 자식 노드 실행
        if (node.children.isEmpty()) {
            return result
        }
        
        println("${indent}📦 [executeNode] 자식 노드 ${node.children.size}개 실행 (parallel=${node.parallel})")
        val childResults = if (node.parallel) {
            // 병렬 실행
            coroutineScope {
                node.children.map { child ->
                    async {
                        executeNode(child, depth + 1)
                    }
                }.awaitAll()
            }
        } else {
            // 순차 실행
            node.children.map { executeNode(it, depth + 1) }
        }
        
        // 결과 결합
        val finalResult = (listOf(result) + childResults)
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        println("${indent}🏁 [executeNode] ${node.layerName} 최종 결과: ${finalResult.take(50)}...")
        return finalResult
    }
    
    /**
     * 리소스 정리 (메모리 누수 방지)
     */
    suspend fun close() {
        llmClient.close()
    }
    
}