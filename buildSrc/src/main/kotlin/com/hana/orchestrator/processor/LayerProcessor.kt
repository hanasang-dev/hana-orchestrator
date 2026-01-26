package com.hana.orchestrator.processor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import java.io.OutputStream
import java.io.OutputStreamWriter

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
        val layerSymbols = resolver
            .getSymbolsWithAnnotation("$layerPackage.Layer")
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.validate() }
        
        logger.info("Found ${layerSymbols.count()} @Layer classes")
        
        layerSymbols.forEach { classDecl ->
            processLayerClass(classDecl, layerPackage)
        }
        
        return emptyList()
    }
    
    private fun processLayerClass(classDecl: KSClassDeclaration, packageName: String) {
        val layerAnnotation = classDecl.annotations
            .firstOrNull { it.shortName.asString() == "Layer" }
            ?: return
        
        val layerName = layerAnnotation.arguments
            .firstOrNull { it.name?.asString() == "name" }
            ?.value as? String ?: return
        
        val layerDescription = layerAnnotation.arguments
            .firstOrNull { it.name?.asString() == "description" }
            ?.value as? String ?: ""
        
        // @LayerFunction이 붙은 함수들 찾기
        val functions = classDecl.getAllFunctions()
            .filter { func ->
                func.annotations.any { it.shortName.asString() == "LayerFunction" }
            }
            .mapNotNull { func -> processFunction(func) }
            .toList()
        
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
        val annotation = func.annotations.firstOrNull { 
            it.shortName.asString() == "LayerFunction" 
        } ?: return null
        
        val funcName = annotation.arguments
            .firstOrNull { it.name?.asString() == "name" }
            ?.value as? String ?: func.simpleName.asString()
        
        val funcDescription = annotation.arguments
            .firstOrNull { it.name?.asString() == "description" }
            ?.value as? String ?: ""
        
        val returnType = annotation.arguments
            .firstOrNull { it.name?.asString() == "returnType" }
            ?.value as? String ?: "string"
        
        // 파라미터 정보 추출
        val parameters = func.parameters.map { param ->
            val paramType = param.type.resolve().declaration.qualifiedName?.asString() ?: "Any"
            val hasDefault = param.hasDefault
            // 기본값은 KDoc이나 어노테이션에서 추출 불가능하므로 null로 설정
            // 실제 기본값은 런타임에 함수 시그니처에서 확인 가능
            val defaultValue: String? = null
            
            ParameterInfo(
                name = param.name?.asString() ?: "",
                type = paramType,
                required = !hasDefault,
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
