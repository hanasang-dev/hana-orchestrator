package com.hana.orchestrator.domain.entity

enum class NodeStatus {
    PENDING,      // 대기 중
    RUNNING,      // 실행 중
    SUCCESS,      // 성공
    FAILED,       // 실패
    RETRYING,     // 재시도 중
    SKIPPED       // 건너뜀 (의존성 실패로)
}
