package com.hana.orchestrator

import com.hana.orchestrator.application.bootstrap.ApplicationBootstrap

/**
 * 애플리케이션 진입점
 * SRP: 진입점만 담당, 모든 로직은 ApplicationBootstrap으로 위임
 * 
 * 설정 관리:
 * - 환경변수 우선 사용 (LLM_SIMPLE_MODEL, LLM_COMPLEX_MODEL 등)
 * - application.conf는 향후 추가 예정
 */
suspend fun main(args: Array<String>) {
    // 현재는 환경변수만 사용 (application.conf는 Phase 2에서 추가)
    ApplicationBootstrap().start(args)
}
