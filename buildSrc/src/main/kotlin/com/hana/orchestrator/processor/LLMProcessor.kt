package com.hana.orchestrator.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import java.io.OutputStreamWriter

/**
 * KSP 프로세서: LLMClient 인터페이스의 @LLMTask 어노테이션을 읽어서
 * ModelSelectionStrategy 구현체를 자동 생성
 */
class LLMProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {
    
    // 프로세서가 이미 실행되었는지 추적 (중복 실행 방지)
    private var hasProcessed = false
    
    override fun process(resolver: Resolver): List<KSAnnotated> {
        // 중복 실행 방지: 이미 처리했으면 스킵
        if (hasProcessed) {
            logger.info("KSP LLMProcessor already processed, skipping")
            return emptyList()
        }
        
        logger.info("KSP LLMProcessor started, looking for @LLMTask annotations")
        
        // LLMClient 인터페이스를 직접 찾기 (효율적)
        val llmClientClassName = "com.hana.orchestrator.llm.LLMClient"
        val llmClientClass = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(llmClientClassName)
        )
        
        if (llmClientClass == null) {
            logger.warn("LLMClient interface not found")
            return emptyList()
        }
        
        // LLMClient 파일에 대한 의존성 설정 (파일 생성 전에 설정)
        val llmClientFile = llmClientClass.containingFile
        val dependencies = if (llmClientFile != null) {
            Dependencies(aggregating = false, llmClientFile)
        } else {
            Dependencies(false)
        }
        
        // LLMClient 인터페이스의 선언된 메서드만 가져오기 (상속 제외)
        val annotatedSymbols = llmClientClass.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { func ->
                func.annotations.any { annotation ->
                    annotation.shortName.asString() == "LLMTask"
                }
            }
            .filter { it.validate() }
            .toList()
        
        logger.info("Found ${annotatedSymbols.size} methods with @LLMTask annotation in LLMClient")
        
        if (annotatedSymbols.isEmpty()) {
            logger.warn("No methods with @LLMTask annotation found in LLMClient")
            return emptyList()
        }
        
        // 각 메서드의 복잡도 추출
        val methodComplexities = annotatedSymbols.mapNotNull { func ->
            val annotation = func.annotations.firstOrNull { 
                it.shortName.asString() == "LLMTask" 
            } ?: return@mapNotNull null
            
            // complexity 인자 추출
            val complexityArg = annotation.arguments.firstOrNull { 
                it.name?.asString() == "complexity" 
            }
            
            // enum 값 추출
            val complexityValue = complexityArg?.value
            val complexityName = when {
                complexityValue is KSClassDeclaration -> complexityValue.simpleName.asString()
                complexityValue is String -> complexityValue
                complexityValue?.toString()?.contains("SIMPLE") == true -> "SIMPLE"
                complexityValue?.toString()?.contains("MEDIUM") == true -> "MEDIUM"
                complexityValue?.toString()?.contains("COMPLEX") == true -> "COMPLEX"
                else -> {
                    logger.warn("Could not extract complexity for ${func.simpleName.asString()}")
                    null
                }
            } ?: return@mapNotNull null
            
            val methodName = func.simpleName.asString()
            
            MethodComplexity(methodName, complexityName)
        }
        
        logger.info("Extracted complexities: ${methodComplexities.joinToString { "${it.methodName}=${it.complexity}" }}")
        
        // 전략 클래스 생성
        try {
            generateModelSelectionStrategy(methodComplexities, dependencies)
            hasProcessed = true
        } catch (e: Exception) {
            // 파일이 이미 존재하는 경우 무시 (다른 프로세서가 이미 생성했을 수 있음)
            if (e.message?.contains("FileAlreadyExistsException") == true || 
                e is java.nio.file.FileAlreadyExistsException) {
                logger.warn("File already exists, skipping generation: ${e.message}")
                hasProcessed = true
            } else {
                logger.error("Failed to generate ModelSelectionStrategy: ${e.message}")
                throw e
            }
        }
        
        return emptyList()
    }
    
    private fun generateModelSelectionStrategy(methodComplexities: List<MethodComplexity>, dependencies: Dependencies) {
        val packageName = "com.hana.orchestrator.llm.strategy"
        val className = "GeneratedModelSelectionStrategy"
        
        logger.info("Generating $className with ${methodComplexities.size} method mappings")
        
        // 전략 클래스 생성 (Factory 기반)
        val strategyClass = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(
                ClassName("com.hana.orchestrator.llm.strategy", "ModelSelectionStrategy")
            )
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("clientFactory", ClassName("com.hana.orchestrator.llm.factory", "LLMClientFactory"))
                    .build()
            )
            .addProperty(
                PropertySpec.builder("clientFactory", ClassName("com.hana.orchestrator.llm.factory", "LLMClientFactory"))
                    .initializer("clientFactory")
                    .addModifiers(KModifier.PRIVATE)
                    .build()
            )
            .addFunctions(generateStrategyMethods(methodComplexities))
            .build()
        
        val fileSpec = FileSpec.builder(packageName, className)
            .addFileComment("Auto-generated by KSP LLM Processor\nDo not edit manually")
            .addType(strategyClass)
            .build()
        
        // 파일 생성 (의존성 정보 포함)
        // FileAlreadyExistsException이 발생할 수 있으므로 try-catch로 처리
        try {
            val file = codeGenerator.createNewFile(
                dependencies,
                packageName,
                className
            )
            
            OutputStreamWriter(file).use { writer ->
                fileSpec.writeTo(writer)
                writer.flush()
            }
            
            logger.info("Generated $className successfully")
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            // 파일이 이미 존재하는 경우 (다른 프로세서나 이전 실행에서 생성됨)
            logger.warn("File $className already exists, skipping generation")
            // 파일이 올바르게 생성되었는지 확인하기 위해 내용을 읽어볼 수도 있음
        } catch (e: Exception) {
            if (e.message?.contains("FileAlreadyExistsException") == true) {
                logger.warn("File $className already exists (wrapped exception), skipping generation")
            } else {
                throw e
            }
        }
    }
    
    private fun generateStrategyMethods(methodComplexities: List<MethodComplexity>): List<FunSpec> {
        val methods = mutableListOf<FunSpec>()
        
        // 메서드명 -> 복잡도 매핑
        val methodToComplexity = methodComplexities.associate { 
            it.methodName to it.complexity 
        }
        
        // 각 메서드에 대한 전략 메서드 생성
        methodComplexities.forEach { methodComplexity ->
            val methodName = methodComplexity.methodName
            val complexity = methodComplexity.complexity
            
            // 메서드명을 전략 메서드명으로 변환
            // 예: validateQueryFeasibility -> selectClientForFeasibilityCheck
            val strategyMethodName = when (methodName) {
                "validateQueryFeasibility" -> "selectClientForFeasibilityCheck"
                "createExecutionTree" -> "selectClientForTreeCreation"
                "evaluateResult" -> "selectClientForEvaluation"
                "suggestRetryStrategy" -> "selectClientForRetryStrategy"
                "compareExecutions" -> "selectClientForComparison"
                "extractParameters" -> "selectClientForParameterExtraction"
                else -> {
                    // 기본 변환: camelCase -> selectClientFor + PascalCase
                    val pascalCase = methodName.replaceFirstChar { it.uppercaseChar() }
                    "selectClientFor$pascalCase"
                }
            }
            
            // 복잡도에 따라 Factory 메서드 호출
            val factoryMethod = when (complexity) {
                "SIMPLE" -> "createSimpleClient"
                "MEDIUM" -> "createMediumClient"
                "COMPLEX" -> "createComplexClient"
                else -> "createMediumClient" // 기본값
            }
            
            val method = FunSpec.builder(strategyMethodName)
                .addModifiers(KModifier.OVERRIDE)
                .returns(ClassName("com.hana.orchestrator.llm", "LLMClient"))
                .addStatement("return clientFactory.$factoryMethod()")
                .build()
            
            methods.add(method)
        }
        
        return methods
    }
    
    data class MethodComplexity(
        val methodName: String,
        val complexity: String
    )
}

class LLMProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment
    ): SymbolProcessor {
        return LLMProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
