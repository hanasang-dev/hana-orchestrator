package com.hana.orchestrator.domain.entity

/**
 * ExecutionNode = PrimitiveTask (HTN typealias).
 *
 * 과거: 자체 data class. 현재: HTN 의 PrimitiveTask 와 동일 타입.
 * 호환을 위해 이름 유지. 점진 deprecation 후 ExecutionNode 호출처들이 직접
 * PrimitiveTask 로 마이그레이션 끝나면 typealias 제거.
 *
 * 필드 호환:
 *  - layerName, function, args, children, parallel, id (PrimitiveTask 시그니처 동일 순서)
 *  - PrimitiveTask 의 추가 필드 (description, verifier) 는 default 값 — 기존 6-param
 *    생성자 호출 그대로 동작
 */
typealias ExecutionNode = com.hana.orchestrator.orchestrator.core.task.PrimitiveTask
