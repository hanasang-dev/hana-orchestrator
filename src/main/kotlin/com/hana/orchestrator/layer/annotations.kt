package com.hana.orchestrator.layer

/**
 * 레이어 클래스에 대한 메타데이터
 * KSP 프로세서가 이 어노테이션을 읽어 LayerDescription을 자동 생성
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Layer(
    val name: String,
    val description: String
)

/**
 * 레이어 함수에 대한 메타데이터
 * KSP 프로세서가 함수 시그니처와 이 어노테이션을 읽어 FunctionDescription 생성
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LayerFunction(
    val name: String,
    val description: String,
    val returnType: String = "string"  // "string", "file_path", "json", "number" 등
)
