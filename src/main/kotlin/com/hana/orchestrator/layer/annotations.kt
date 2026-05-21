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

/**
 * 도메인 불변조건 보호용 — 이 함수가 성공한 직후 반드시 같은 레이어의 [function]을 이어서 실행해야 함.
 * 프레임워크(DefaultReActStrategy)가 런타임에 Java 리플렉션으로 감지하여 자동 실행.
 * LLM이 트리를 잘못 짜도 이 후속 실행은 건너뛸 수 없음.
 *
 * 제약:
 * - 같은 레이어 함수만 지정 가능 (레이어 간 참조 금지)
 * - 후속 함수는 인자 없이 호출 가능해야 함 (기본값으로 처리)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresSelfAction(val function: String)

/**
 * SharedLayer 노출 마커 — 이 함수가 SharedLayer를 통해 다른 레이어에 공유됨.
 * LayerManager가 런타임 스캔으로 감지하여 SharedLayer dispatch map에 등록.
 * 레이어 격리 원칙을 유지하면서 선택된 함수만 교차 접근 허용.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Shared

/** SelfAction 실행 시점 */
enum class SelfActionTiming { PRE, POST }

/**
 * 크로스레이어 SelfAction — 이 함수 실행 전(PRE) 또는 후(POST)에 [function]을 자동 실행.
 * PRE: LayerManager가 @Shared 태그로 구현 레이어를 탐색하여 실행, 결과를 args["context"]에 주입.
 * POST: 같은 레이어 한정 (layer="" 필수). PRE와 달리 args 주입 없음.
 *
 * 사용:
 * - PRE 크로스레이어: @SelfAction("findRelevantFiles", timing = SelfActionTiming.PRE)
 * - POST 같은레이어: @RequiresSelfAction("reviewLayerCandidate") (기존 어노테이션 사용 권장)
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SelfAction(
    val function: String,
    val timing: SelfActionTiming = SelfActionTiming.POST
)
