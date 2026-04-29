package com.hana.orchestrator.layer

import kotlinx.serialization.Serializable

/**
 * 승인 게이트의 작업 유형
 */
@Serializable
enum class ApprovalKind {
    FILE,       // 파일 읽기·쓰기 작업
    EXECUTION   // 셸·빌드 등 일반 실행 작업
}

/**
 * 승인 게이트에 표시할 미리보기 정보
 * @param path 작업 대상 경로 또는 "layerName.function" 형태의 식별자
 * @param oldContent 변경 전 내용 (diff 표시용, null이면 diff 없이 newContent만 표시)
 * @param newContent 변경 후 내용 또는 실행할 args 텍스트
 */
data class ApprovalPreview(
    val path: String,
    val oldContent: String?,
    val newContent: String,
    val kind: ApprovalKind = ApprovalKind.EXECUTION
)

/**
 * 오케스트레이션 시스템의 모든 레이어가 구현해야 할 기본 인터페이스
 *
 * 온톨로지적 성격:
 * - **공유 어휘**: Layer, Function, Parameter, ExecutionNode 등 개념이 시스템 전반에서 동일한 의미로 사용됨.
 * - **관계**: Layer-has-Function, Node-invokes-Layer/Function, Node-has-children 등 실행 구조가 명시적 관계로 정의됨.
 * - **자기기술**: 각 레이어가 describe()로 자신의 "타입"(역할)과 "능력"(함수·파라미터)을 선언 → 동적으로 확장 가능한 개념 공간.
 * - **단일 모델**: 레지스트리 + describe()가 "무엇이 존재하는지, 무엇을 할 수 있는지"에 대한 사실상의 온톨로지 역할을 함.
 * 이름은 오케스트레이터이지만, 설계는 공유 도메인 모델(어휘·관계·자기기술)에 기반한 온톨로지에 가깝다.
 *
 * 설계 원칙:
 * - 자기기술: 각 레이어가 자신의 기능을 스스로 설명
 * - 분산 가능: 로컬/원격 레이어 모두 지원
 * - 단순함: 복잡한 타입 구분 없이 문자열 기반 통신
 */
interface CommonLayerInterface {
    /**
     * 레이어의 기능과 속성을 설명
     * 오케스트레이터가 이 정보로 레이어를 등록하고 관리
     */
    suspend fun describe(): LayerDescription
    
    /**
     * 실제 작업 실행
     * @param function 실행할 함수명
     * @param args 함수 파라미터
     * @return 작업 결과 (문자열)
     */
    suspend fun execute(function: String, args: Map<String, Any> = emptyMap()): String

    /**
     * 승인 게이트에 표시할 미리보기 정보 반환
     * 기본 구현: args를 텍스트로 포맷. 파일 쓰기 등 diff가 필요한 레이어는 override.
     */
    suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview {
        val preview = args.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return ApprovalPreview(path = function, oldContent = null, newContent = preview)
    }
}

/**
 * 레이어의 전체 설명 정보
 */
@Serializable
data class LayerDescription(
    val name: String,                    // 레이어 고유 이름
    val description: String,             // 레이어 목적 설명
    val functions: List<String>,          // 사용 가능한 함수명 목록 (기존 호환성)
    val functionDetails: Map<String, FunctionDescription> = emptyMap()  // 함수 상세 정보 (KSP로 자동 생성)
)

/**
 * 함수 상세 정보
 * KSP 프로세서가 함수 시그니처를 분석하여 자동 생성
 */
@Serializable
data class FunctionDescription(
    val name: String,                    // 함수명
    val description: String,             // 함수 설명
    val parameters: Map<String, ParameterInfo>,  // 파라미터 정보
    val returnType: String                // 반환 타입 힌트 ("string", "file_path", "json" 등)
)

/**
 * 파라미터 정보
 */
@Serializable
data class ParameterInfo(
    val type: String,                    // 파라미터 타입 ("String", "Int", "Boolean" 등)
    val description: String = "",         // 파라미터 설명 (KDoc에서 추출)
    val required: Boolean = true,        // 필수 여부
    val defaultValue: String? = null    // 기본값 (있는 경우)
)

/**
 * 원격 레이어 통신을 위한 HTTP 데이터 모델
 */
@Serializable
data class LayerRequest(
    val function: String,
    val arguments: Map<String, String>  // 문자열 맵으로 단순화
)

@Serializable
data class LayerResponse(
    val success: Boolean,
    val result: String,
    val error: String? = null
)
