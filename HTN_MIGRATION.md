# HTN Migration Plan

> Mission: 14B 모델 한계를 구조로 보강 — Claude 급 자율 에이전트
> 본 문서: ReAct + Workflow DAG → ReAct + HTN 전환 단계 계획

## 0. 배경

### 현재 모델
```
Strategy.execute(query: String)
  └── ReAct loop:
       └── step N: LLM → ExecutionTree (mini DAG)
             └── TreeExecutor.execute(tree)
                   └── ExecutionNode (atomic = layer.fn 호출)
```

문제:
- "Goal" 개념이 string 으로만 흘러다님 — 일급 객체 아님
- ExecutionNode (atomic) 과 Goal (high-level) 이 다른 추상 레벨로 분리됨
- subgoal (pursue) / verifier / retry budget 박을 자리 없음
- 매 후속 PR 마다 ad-hoc 결정 누적

### 목표 모델 (HTN)
```
Task (sealed)
├── LayerCall   = atomic (= 현재 ExecutionNode)
└── CompoundTask = subtasks 보유 (= Goal + subgoal 재귀)
```

장점:
- 단일 추상. 분해 자연 재귀
- verifier·retryBudget = Task 의 공통 슬롯
- F (Plan-Execute-Verify) / H (pursue) 자연 흡수
- 외부 API 영향 0 — Orchestrator 가 String → CompoundTask wrap

## 1. 단계 분할 — 3 phase

각 phase = 독립 커밋. 빌드·회귀 검증 후 다음 단계.

### B1 — Task 타입 정의 (신규 코드만)

**시간**: ~1.5h
**목표**: Task sealed + 구현체 정의. 사용 X. 빌드만 통과.

**파일**:
- 신규 `src/main/kotlin/com/hana/orchestrator/orchestrator/core/task/Task.kt`
- 신규 `src/main/kotlin/com/hana/orchestrator/orchestrator/core/task/LayerCall.kt`
- 신규 `src/main/kotlin/com/hana/orchestrator/orchestrator/core/task/CompoundTask.kt`

**스케치**:
```kotlin
sealed interface Task {
    val description: String
    val verifier: TaskVerifier?
}

data class LayerCall(
    override val description: String,
    val layerName: String,
    val function: String,
    val args: Map<String, Any>,
    override val verifier: TaskVerifier? = null
) : Task

data class CompoundTask(
    override val description: String,
    val query: String,
    val subtasks: List<Task> = emptyList(),
    override val verifier: TaskVerifier? = null,
    val retryBudget: Int = Int.MAX_VALUE
) : Task

fun interface TaskVerifier {
    suspend fun verify(result: String): VerifyOutcome
}
```

**검증**: 컴파일 통과. unit smoketest 로 생성·equals 확인.

**위험**: 0 (신규 파일만, 기존 코드 영향 X)

**롤백**: 파일 삭제

---

### B2 — Strategy entry point 가 Task 받음 (호환 계층)

**시간**: ~2h
**목표**: `Strategy.execute(task: Task)`. 내부에선 여전히 ExecutionTree 사용 (어댑터). 외부 API 영향 0.

**파일**:
- `ReActStrategy.kt` — interface 시그니처
- `DefaultReActStrategy.kt` — execute(task) → 내부 `task.query` 추출해서 기존 로직
- `ReactiveExecutor.kt` — execute(task) 받음
- `Orchestrator.kt` — String query → CompoundTask wrap
- `LLMPromptBuilder.kt` — 변경 X (query: String 그대로 받음)

**스케치**:
```kotlin
// Orchestrator
fun chat(query: String) = chatWithTask(
    CompoundTask(description = "user query", query = query)
)

// Strategy
override suspend fun execute(task: Task, ...) {
    val query = when (task) {
        is CompoundTask -> task.query
        is LayerCall -> task.description
    }
    // 기존 로직 그대로
}
```

**검증**:
- 합성 smoketest: CompoundTask 만들어 strategy 호출 → 기존 동작 동일
- 회귀 curl: `/chat` happy path

**위험**: 중 — 호출 체인 시그니처 변경. ServiceController 등 caller 일관 갱신 필요.

**롤백**: git revert. 인터페이스만 변경이라 부분 rollback 가능.

---

### B3 — TreeExecutor 가 Task 직접 처리 (ExecutionNode 점진 deprecation)

**시간**: ~2h
**목표**: ExecutionTree/ExecutionNode 가 LayerCall 트리로 표현. TreeExecutor 가 Task 받아 처리. 외부 호환 wrapper 유지.

**파일**:
- `TreeExecutor.kt` — execute(task: Task) 진입점 추가
- `ExecutionTreeMapper.kt` — LLM JSON → Task tree 변환
- `DefaultReActStrategy.kt` — TreeExecutor 호출부 갱신

**핵심 매핑**:
- LLM 의 ExecutionTreeResponse JSON → CompoundTask(subtasks = LayerCall list)
- `{{parent}}` 데이터 흐름 = subtask args 의 placeholder (기존 그대로)
- 트리 깊이 = CompoundTask 중첩

**검증**:
- 합성 smoketest: LLM JSON 문자열 → Task tree 변환 검증
- 회귀: 자가개선 시나리오 (LLM 안정 시) — improveLayer 호출이 정상 진행

**위험**: 중-상 — TreeExecutor 핫패스. 회귀 가능.

**롤백**: B3 단독 revert 가능 (B1/B2 유지).

---

## 2. 검증 전략 (LLM stuck 회피)

각 phase 끝에:
1. **빌드 통과** — gradle shadowJar
2. **합성 smoketest** — 임시 main() 으로 핵심 동작 검증 (LedgerSmoketest 패턴)
3. **회귀 curl** — `/chat` 가벼운 query (e.g. "git 브랜치") happy path
4. (선택) MCP `chat_async` happy path — 단 큰 LLM 호출은 stuck 가능성

LLM stuck 우회: smoketest 우선. LLM 검증은 happy path 만.

## 3. 미래 PR 들의 합류 지점

B3 완료 후:
- **B5 (회귀 verifier)**: `LayerCall.verifier = CompileVerifier()` 또는 `CompoundTask.verifier`
- **H (pursue 메타액션)**: LLM action 어휘에 `pursue` 추가 → `parent.subtasks += CompoundTask(...)` 또는 ExecutionContext.goalStack push
- **F (Plan-Execute-Verify)**: LLM 이 사전 plan = CompoundTask 트리 미리 생성. step-by-step 대체.
- **D2 (멀티모델)**: `Task.complexity` 필드 추가 → `selectClientFor(task.complexity)`
- **C (RAG)**: 과거 successful Task tree 임베딩 인덱스 → 비슷한 task 검색

## 4. 진행 체크리스트

- [ ] B1 — Task sealed 정의 (신규 파일)
- [ ] B2 — Strategy entry 가 Task 받음
- [ ] B3 — TreeExecutor 가 Task 처리

각 완료 시 커밋. HANDOFF.md 업데이트.

## 5. 위험 관리

**큰 위험**: B2/B3 의 시그니처 변경으로 빌드 깨짐
- 대응: phase 별 작은 단위 커밋. 깨지면 즉시 revert.

**작은 위험**: 합성 smoketest 만으로 회귀 못 잡음
- 대응: LLM 안정 시점에 자가개선 run 한 번 돌려 end-to-end 확인

**rollback 정책**: 각 phase 가 독립 revertable 하도록 분리

---

_HTN 분할 베팅의 정당화: 모델 교체 시 무거운 부담 없음 (Compound 자식 비워두면 free-form). 모델 강해질수록 구조도 같이 강력해짐._
