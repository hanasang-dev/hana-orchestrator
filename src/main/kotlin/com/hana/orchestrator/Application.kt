package com.hana.orchestrator

import com.hana.orchestrator.application.bootstrap.ApplicationBootstrap

/**
 * 애플리케이션 진입점
 * SRP: 진입점만 담당, 모든 로직은 ApplicationBootstrap으로 위임
 */
suspend fun main(args: Array<String>) {
    ApplicationBootstrap().start(args)
}
