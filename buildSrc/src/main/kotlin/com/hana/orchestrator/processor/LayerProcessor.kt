package com.hana.orchestrator.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import java.io.OutputStream
import java.io.OutputStreamWriter
import com.google.devtools.ksp.symbol.Modifier

/**
 * KSP 프로세서: @Layer 어노테이션이 붙은 클래스를 스캔하여
 * LayerDescription을 자동 생성하는 코드를 만듦
 */
class LayerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {
    
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val layerPackage = options["layer.package"] ?: "com.hana.orchestrator.layer"
        
        logger.info("KSP LayerProcessor started, looking for @Layer in package: $layerPackage")
        
        // @Layer 어노테이션이 붙은 클래스 찾기
        val layerAnnotationName = "com.hana.orchestrator.layer.Layer"
        val layerSymbols = resolver
            .getSymbolsWithAnnotation(layerAnnotationName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.validate() }
            .toList()
        
        logger.info("Found ${layerSymbols.size} @Layer classes")
        
        layerSymbols.forEach { classDecl ->
            processLayerClass(classDecl, layerPackage)
        }
        
        return emptyList()
    }
    
    private fun processLayerClass(classDecl: KSClassDeclaration, packageName: String) {
        // @Layer 어노테이션이 있는지 확인 (마커 역할)
        val hasLayerAnnotation = classDecl.annotations.any { 
            it.shortName.asString() == "Layer" 
        }
        if (!hasLayerAnnotation) return
        
        // 클래스명에서 레이어 이름 자동 생성 (예: EchoLayer -> "echo-layer")
        val className = classDecl.simpleName.asString()
        val layerName = className
            .removeSuffix("Layer")
            .replace(Regex("([A-Z])")) { "-${it.value.lowercase()}" }
            .removePrefix("-")
            .ifEmpty { className.lowercase() }
        
        // 레이어 설명: KDoc 전체 추출 (구조화된 형식 유지)
        val docString = classDecl.docString
        val layerDescription = if (docString != null && docString.isNotBlank()) {
            // KDoc 전체를 추출하되, 구조를 유지하면서 불필요한 부분만 제거
            val trimmedDoc = docString.trim()
            trimmedDoc
                .lines()
                .map { it.trim() }
                .filter { 
                    it.isNotEmpty() && 
                    !it.startsWith("@") && // 어노테이션 태그 제외
                    !it.startsWith("*") && // 마크다운 리스트 마커 제외 (내용은 유지)
                    !it.matches(Regex("^\\s*-\\s*$")) // 빈 리스트 항목 제외
                }
                .joinToString("\n") // 줄바꿈 유지로 가독성 향상
                .replace(Regex("\\n{3,}"), "\n\n") // 연속된 줄바꿈을 2개로 제한
                .take(800) // 적절한 길이로 제한
                .trim()
                .ifEmpty { "${className.removeSuffix("Layer")} 레이어" }
        } else {
            "${className.removeSuffix("Layer")} 레이어"
        }
        
        // 클래스에 선언된 함수만 가져오기 (상속 제외)
        // getAllFunctions()는 상속된 함수도 포함하므로, containingFile로 필터링
        val classFile = classDecl.containingFile
        val allFunctions = classDecl.getAllFunctions()
            .filter { func -> 
                val funcFile = func.containingFile
                funcFile != null && funcFile == classFile
            }
        
        logger.info("Found ${allFunctions.count()} functions in ${className} (excluding inherited)")
        
        // @LayerFunction 어노테이션이 붙은 함수만 레이어 API로 노출
        val functions = allFunctions
            .filter { func ->
                val name = func.simpleName.asString()
                val hasLayerFunctionAnnotation = func.annotations.any { 
                    it.shortName.asString() == "LayerFunction" 
                }
                
                name != "execute" && 
                name != "describe" &&
                hasLayerFunctionAnnotation
            }
            .mapNotNull { func -> processFunction(func) }
            .toList()
        
        logger.info("Processed ${functions.count()} layer functions")
        
        // LayerDescription 객체 생성 코드 작성
        generateLayerDescription(
            classDecl = classDecl,
            packageName = packageName,
            layerName = layerName,
            layerDescription = layerDescription,
            functions = functions
        )
    }
    
    private fun processFunction(func: KSFunctionDeclaration): FunctionInfo? {
        // 함수명 자동 추출
        val funcName = func.simpleName.asString()
        
        // 함수 설명: KDoc 전체 추출 (키워드에 의존하지 않고 전체 내용 포함)
        val funcDocString = func.docString
        val funcDescription = if (funcDocString != null && funcDocString.isNotBlank()) {
            // KDoc 전체를 추출하되, 불필요한 공백과 마크다운 포맷팅 정리
            val trimmedDoc = funcDocString.trim()
            trimmedDoc
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("param") && !it.startsWith("return") } // 어노테이션 태그 제외
                .joinToString(" ")
                .replace(Regex("\\s+"), " ") // 연속된 공백을 하나로
                .take(200) // 함수 설명은 적절한 길이로 제한
                .ifEmpty { funcName }
        } else {
            val paramNames = func.parameters.mapNotNull { it.name?.asString() }
            when {
                paramNames.isEmpty() -> funcName
                paramNames.size == 1 -> "${funcName}(${paramNames.first()})"
                else -> "${funcName}(${paramNames.joinToString(", ")})"
            }
        }
        
        // 반환 타입 자동 추출
        val returnTypeResolved = func.returnType?.resolve()
        val returnTypeName = returnTypeResolved?.declaration?.qualifiedName?.asString() ?: "Any"
        val returnType = when {
            returnTypeName == "kotlin.String" -> "string"
            returnTypeName == "kotlin.Int" || returnTypeName == "kotlin.Long" || returnTypeName == "kotlin.Double" -> "number"
            returnTypeName == "kotlin.Boolean" -> "boolean"
            returnTypeName.contains("List") || returnTypeName.contains("Array") -> "array"
            returnTypeName.contains("Map") -> "object"
            else -> "string" // 기본값
        }
        
        // 파라미터 정보 자동 추출
        val parameters = func.parameters.map { param ->
            val paramName = param.name?.asString() ?: ""
            val paramTypeResolved = param.type.resolve()
            val paramTypeName = paramTypeResolved.declaration.qualifiedName?.asString() ?: "Any"
            
            // 기본값 추출 시도
            val defaultValue: String? = if (param.hasDefault) {
                // KSP에서 기본값을 직접 추출하기 어려우므로, 파라미터 타입에 따라 추론
                when {
                    paramTypeName == "kotlin.Int" -> "0"
                    paramTypeName == "kotlin.String" -> "\"\""
                    paramTypeName == "kotlin.Boolean" -> "false"
                    else -> null
                }
            } else null
            
            ParameterInfo(
                name = paramName,
                type = paramTypeName.replace("kotlin.", ""),
                required = !param.hasDefault,
                defaultValue = defaultValue
            )
        }
        
        return FunctionInfo(
            name = funcName,
            description = funcDescription,
            returnType = returnType,
            parameters = parameters
        )
    }
    
    
    private fun generateLayerDescription(
        classDecl: KSClassDeclaration,
        packageName: String,
        layerName: String,
        layerDescription: String,
        functions: List<FunctionInfo>
    ) {
        val className = classDecl.simpleName.asString()
        val objectName = "${className}_Description"
        
        val layerDescClass = ClassName(packageName, "LayerDescription")
        val funcDescClass = ClassName(packageName, "FunctionDescription")
        val paramInfoClass = ClassName(packageName, "ParameterInfo")
        
        // functionDetails 맵 생성 (CodeBlock 사용)
        val functionDetailsBuilder = CodeBlock.builder()
        functions.forEachIndexed { funcIndex, func ->
            if (funcIndex > 0) functionDetailsBuilder.add(",\n")
            functionDetailsBuilder.add("%S to FunctionDescription(\n", func.name)
            functionDetailsBuilder.indent()
            functionDetailsBuilder.add("name = %S,\n", func.name)
            functionDetailsBuilder.add("description = %S,\n", func.description)
            functionDetailsBuilder.add("parameters = mapOf(\n")
            functionDetailsBuilder.indent()
            func.parameters.forEachIndexed { paramIndex, param ->
                if (paramIndex > 0) functionDetailsBuilder.add(",\n")
                functionDetailsBuilder.add("%S to ParameterInfo(\n", param.name)
                functionDetailsBuilder.indent()
                functionDetailsBuilder.add("type = %S,\n", param.type)
                functionDetailsBuilder.add("description = %S,\n", "")
                functionDetailsBuilder.add("required = %L,\n", param.required)
                if (param.defaultValue != null) {
                    functionDetailsBuilder.add("defaultValue = %S\n", param.defaultValue)
                } else {
                    functionDetailsBuilder.add("defaultValue = null\n")
                }
                functionDetailsBuilder.unindent()
                functionDetailsBuilder.add(")")
            }
            functionDetailsBuilder.unindent()
            functionDetailsBuilder.add("\n),\n")
            functionDetailsBuilder.add("returnType = %S\n", func.returnType)
            functionDetailsBuilder.unindent()
            functionDetailsBuilder.add(")")
        }
        val functionDetailsCode = functionDetailsBuilder.build()
        
        // LayerDescription 초기화 코드
        val layerDescInit = CodeBlock.builder()
            .add("LayerDescription(\n")
            .indent()
            .add("name = %S,\n", layerName)
            .add("description = %S,\n", layerDescription)
            .add("functions = listOf(%L),\n", 
                functions.joinToString(", ") { "\"${it.name}\"" })
            .add("functionDetails = mapOf(\n")
            .indent()
            .add(functionDetailsCode)
            .unindent()
            .add("\n)\n")
            .unindent()
            .add(")")
            .build()
        
        val fileSpec = FileSpec.builder(packageName, objectName)
            .addFileComment("Auto-generated by KSP Layer Processor")
            .addType(
                TypeSpec.objectBuilder(objectName)
                    .addProperty(
                        PropertySpec.builder(
                            "layerDescription",
                            layerDescClass
                        )
                        .initializer(layerDescInit)
                        .build()
                    )
                    .build()
            )
            .build()
        
        val file = codeGenerator.createNewFile(
            Dependencies(false),
            packageName,
            objectName
        )
        
        OutputStreamWriter(file).use { writer ->
            fileSpec.writeTo(writer)
            writer.flush()
        }
    }
    
    data class FunctionInfo(
        val name: String,
        val description: String,
        val returnType: String,
        val parameters: List<ParameterInfo>
    )
    
    data class ParameterInfo(
        val name: String,
        val type: String,
        val required: Boolean,
        val defaultValue: String?
    )
}

class LayerProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment
    ): SymbolProcessor {
        return LayerProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
