package com.hana.orchestrator.layer

import kotlinx.serialization.Serializable

/**
 * 오케스트레이션 시스템의 모든 레이어가 구현해야 할 기본 인터페이스
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
