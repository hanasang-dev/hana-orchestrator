package com.hana.orchestrator.layer

/**
 * 레이어 클래스 마커 어노테이션
 * KSP 프로세서가 이 어노테이션이 붙은 클래스를 자동으로 스캔하여
 * 함수 시그니처와 KDoc에서 모든 메타데이터를 추출
 * 
 * 사용법:
 * ```
 * /**
 *  * 레이어 설명 (KDoc 첫 줄이 레이어 설명으로 사용됨)
 *  */
 * @Layer
 * class MyLayer : CommonLayerInterface {
 *     /**
 *      * 함수 설명 (KDoc 첫 줄이 함수 설명으로 사용됨)
 *      */
 *     suspend fun myFunction(param: String): String { ... }
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Layer

/**
 * 레이어 함수 마커 어노테이션 (선택사항)
 * 이 어노테이션이 없어도 public suspend 함수는 자동으로 레이어 함수로 인식됨
 * 특정 함수만 명시적으로 표시하고 싶을 때만 사용
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class LayerFunction
