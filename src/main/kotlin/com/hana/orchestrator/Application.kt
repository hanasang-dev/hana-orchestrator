package com.hana.orchestrator

import com.hana.orchestrator.application.bootstrap.ApplicationBootstrap

/**
 * 애플리케이션 진입점
 * SRP: 진입점만 담당, 모든 로직은 ApplicationBootstrap으로 위임
 * 
 * 설정 관리:
 * - application.conf에서 기본 설정 로드
 * - 환경변수로 오버라이드 가능 (환경변수 우선)
 * - application.conf 로드 실패 시 환경변수만 사용
 */
suspend fun main(args: Array<String>) {
    ApplicationBootstrap().start(args)
}
