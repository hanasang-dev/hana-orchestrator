# 코드베이스 정제 기록

## 완료

| 날짜 | 파일 | 내용 |
|------|------|------|
| 2026-05-18 | `ApplicationBootstrap.kt` | `--skip-cleanup` → `--cleanup` 으로 반전. 기본값을 스킵으로 변경 |
| 2026-05-18 | `Orchestrator.kt` | `execute()` 내 `"process"` 하드코딩 제거 — dead branch 삭제 |
| 2026-05-18 | `Orchestrator.kt` | `@Volatile currentJob` → `ConcurrentHashMap<executionId, Deferred>` 교체 |
| 2026-05-18 | `ExecutionController.kt` | cancel 엔드포인트 executionId 파라미터 추가 |
| 2026-05-18 | `index.html / app.js / styles.css` | 단일 progress바 → executionId별 멀티 카드 (query 표시) |
| 2026-05-18 | `LayerManager.kt` | `ensureInitialized` double-checked locking (Mutex), `layers` → CopyOnWriteArrayList, `layerNameMap` → ConcurrentHashMap, `registerLayer`/`unregisterLayer`/`getAllLayerDescriptions` writeMutex 보호, 캐시 size 비교 버그 제거 |
| 2026-05-18 | `DefaultReActStrategy.kt` | `CancellationException` 삼킴 버그 수정 — `catch(Exception)` 앞에 `catch(CancellationException) { throw e }` 추가. `addLogToCurrent` → `addLogTo(executionId, ...)` 전환 (5곳). `getCurrentExecution()` → `getCurrentExecution(executionId)` |
| 2026-05-18 | `ExecutionHistoryManager.kt` | 단일 `currentExecution` 필드 → `ConcurrentHashMap<executionId, ExecutionHistory>`. `addLogTo(executionId, msg)` 신규 API, `getLogs(executionId)` 신규 API. 레거시 `addLogToCurrent`/`getCurrentLogs` no-op/compat 유지 |
| 2026-05-18 | `Orchestrator.kt` | 모든 `addLogToCurrent` → `addLogTo(executionId, ...)`, `getCurrentLogs()` → `getLogs(executionId)`, `clearCurrentExecution()` → `clearCurrentExecution(executionId)` 전환 |
| 2026-05-18 | `CoreEvaluationLayer.kt` | `clearCurrentExecution()` → `clearCurrentExecution(executionId)` — 전체 맵 초기화 버그 수정 |
| 2026-05-18 | `TreeExecutor.kt` | `executeNode` catch(Exception) — `CancellationException` 삼킴 버그 수정. `completeNodeDeferred` 후 re-throw |
| 2026-05-18 | `OllamaLLMClient.kt` | `generateDirectAnswer` catch(Exception) — CancellationException 래핑 버그 수정 |
| 2026-05-18 | `DefaultReActStrategy.kt` | `compressHistory` catch(Exception) — CancellationException 삼킴 버그 수정 |
| 2026-05-18 | `DefaultReActStrategy.kt` + `TreeExecutor.kt` | `storeStepResult` / `{{step:N}}` 키에 executionId 포함 — 동시 실행 시 context store 키 충돌 버그 수정 |
| 2026-05-18 | `DefaultReActStrategy.kt` | `storeStepResult` catch(Exception) — CancellationException 삼킴 버그 수정 |

| 2026-05-18 | `SharedLayer.kt` + `LayerManager.kt` | `findSourceLayer()` 제거(DRY) → `layerManager.findSharedFunctionLayer()` 위임. `LayerManager`에 `sharedFunctionIndex` 캐시 추가 — register/unregister 시 무효화. `SharedLayer.describeCache` 추가 + `invalidateCache()` 연동 |
| 2026-05-18 | `ContextLayer.kt` | `store` 단일맵 → `sessionStore`(layer:/rules:/interface:) + `execStore` 분리. `clear()` execStore만 초기화. `clearExecution(executionId)` 신규 추가 (step_*키 정리). `storeFor(key)` 라우팅 |
| 2026-05-18 | `Orchestrator.kt` | 실행 완료·취소·실패 5개 경로에 `ContextLayer.clearExecution(executionId)` 추가 — step_* 메모리 누수 해결 |

---

## TODO

### 낮음 (나중에)
- [ ] `ApplicationBootstrap.runServer` — `while(delay(1000))` 폴링 루프 → `CompletableDeferred`로 교체
- [ ] `checkPendingRecovery` — `/health` 200 체크는 항상 성공. 자가개선 루프 손볼 때 수정한 레이어 직접 호출 검증으로 교체
- [ ] `Orchestrator.describe()` / `execute()` — 페더레이션 구현 시 수정. 현재 dead code (registerLayer(orchestrator) 없음). describe()는 내부 레이어 함수 전부 노출 중 → 단일 `execute(query)` 함수만 노출하도록 단순화 필요. 오케스트레이터 ID 추적도 이 시점에 같이 설계.

---

## 진행 중 — 부트스트랩 → 전체 구조 훑기

### 체크된 영역
- [x] `Application.kt` — 진입점
- [x] `ApplicationBootstrap.kt` — 초기화 흐름
- [x] `Orchestrator.kt`
- [x] `LayerManager.kt`
- [x] `DefaultReActStrategy.kt`
- [x] `TreeExecutor.kt`
- [x] LLM 클라이언트 계층
- [x] Presentation / Controller

### 레이어 구현체
- [x] `EchoLayer` / `GreeterLayer` — 테스트·더미 레이어
- [x] `TextTransformerLayer` / `TextValidatorLayer` — 텍스트 유틸
- [x] `LayerInfoLayer` — 레이어 메타 조회 (setLayerManager 런타임 주입, READ_ONLY)
- [x] `ContextLayer` — 실행 컨텍스트 KV 저장소 (companion object 싱글턴, populate() 앱 시작 시 레이어 소스 로드)
- [x] `FileSystemLayer` — 파일 읽기/쓰기/탐색 (validateChanges 보호 게이트, resolveWritePath glob 자동해석, @Shared findRelevantFiles)
- [x] `GitLayer` — git 조작 (ProcessBuilder 30s, log/diff/status READ_ONLY 나머지 EXECUTION)
- [x] `ShellLayer` — 쉘 명령 실행 (sh -c / cmd /c, 5000자 truncation, 기본 EXECUTION)
- [x] `BuildLayer` — 빌드 실행 (gradlew, restart() 프로세스 자체 교체, pending.jsonl 롤백 지원)
- [x] `LLMLayer` — LLM 직접 호출 (@Shared answerDirectly/analyze, READ_ONLY)
- [x] `SharedLayer` — @Shared 함수 라우팅 (반사 기반 동적 탐색, 런타임 describe 구성)
- [x] `RemoteLayer` / `LayerRegistry` / `LayerFactory` — 원격 HTTP 프록시, child-first ClassLoader 핫로드, 팩토리
- [x] `DevelopLayer` — 자가개선 루프 (improveLayer→review→apply 게이트, 전략 후보 시스템, hotLoad/reloadLayer)
- [x] `CoreEvaluationLayer` — A/B 시나리오 비교 + RC 후보 관리 (runScenario, compareScenarioResults, applyCandidate)
