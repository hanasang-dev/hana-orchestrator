package com.hana.orchestrator.orchestrator.core.task

/**
 * Primitive Task (HTN) — 분해 불가능한 원자 단위. 단일 layer.function 호출.
 *
 * 현재 도메인의 `ExecutionNode` 와 대응. ExecutionNode 점진 대체.
 *
 * 두 종류 자식의 의미 구분:
 *  - `children`: **데이터 흐름** — 자식이 부모 결과를 args 의 `{{parent}}` placeholder 로 받음 (sequential composition)
 *  - [CompoundTask.subtasks]: **goal decomposition** — 서로 독립적인 sub-목표 (subgoal pursue 영역)
 *
 * @property description LLM·UI 표시용 한 줄 설명 (e.g. "git 브랜치 조회")
 * @property layerName 대상 레이어 namespace (e.g. "git")
 * @property function 호출할 함수 (e.g. "currentBranch")
 * @property args 함수 인자. `{{parent}}` / `{{step:N}}` / `{{nodeId:X}}` placeholder 지원
 * @property children 데이터 흐름 자식 — 부모 결과를 입력으로 받는 후속 호출들
 * @property parallel 형제 노드 간 병렬 실행 여부
 * @property verifier 호출 결과 검증기. null = LayerManager 의 default 검증 (성공/실패) 만
 */
data class PrimitiveTask(
    val layerName: String,
    val function: String,
    val args: Map<String, Any> = emptyMap(),
    val children: List<PrimitiveTask> = emptyList(),
    val parallel: Boolean = false,
    /**
     * 트리 내 고유 식별자. 트리 빌드 시점에 부여. 데이터 흐름 / context 추적에 사용.
     * 비어있으면 TreeExecutor 가 자동 부여.
     */
    val id: String = "",
    override val description: String = "$layerName.$function",
    override val verifier: TaskVerifier? = null
) : Task
