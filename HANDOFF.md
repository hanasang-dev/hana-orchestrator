# HANDOFF — hana-orchestrator (다음 세션용)

> 매 세션 종료 시 갱신. 새 세션은 이것부터 읽고 시작.

## 0. Mission

**14B 로컬 모델 + 구조 보강으로 Claude 급 자율 에이전트 달성**.
모자란 모델 성능을 구조로 메꾼다. 가드·게이트·롤백·verifier·메모리·plan-execute-verify 누적.
모델 교체(30B+ 또는 cloud) 시점에 인프라는 그대로 가치 — 신뢰도·복잡도 천장 함께 상승.

## 1. 즉시 확인 (30초 컷)

```bash
cd ~/StudioProjects/opencode/hana-orchestrator
git log --oneline -8
git status
lsof -iTCP:8080 -sTCP:LISTEN -n  # 서버 살아있나
ls .hana/candidates/             # 미반영 후보 있나
```

- **브랜치**: `main`
- **JAR**: `build/libs/hana-orchestrator-all.jar`
- **빌드**: `./gradlew shadowJar` (~6s incremental)
- **기동**: `nohup java -jar build/libs/hana-orchestrator-all.jar > /tmp/hana-server.log 2>&1 &`
- **포트**: 8080
- **모델 tier**: SIMPLE=exaone3.5:2.4b / MEDIUM=exaone3.5:7.8b / COMPLEX=qwen3:14b
- **LLM 호출**: 60~240s/call (프롬프트 크기·모델 tier 따라 편차 큼)

## 2. 누적 PR — 자율도 인프라 (시간순)

| 커밋 | 태그 | 효과 |
|---|---|---|
| PR1: applyLayerCandidate Two-Phase Commit | 안전 | 컴파일 실패 시 .bak 자동 복구 + 후보 보존 |
| PR2: ReAct finish 가드 | 안전 | judgeFinish(SIMPLE tier) verifier, maxFinishBlocks=2 |
| normalize: 레이어 이름 정규화 헬퍼 | DRY | LLM kebab/snake/PascalCase 혼용 흡수 |
| PR3a: layer .kt 보호 가드 | 안전 | PR1 우회 writeFile 차단, protectionReason DRY |
| A1: Failure ledger | 메모리 | 같은 함수 ≥2 실패 → 다른 접근 강제 hint |
| B1-B5: HTN 마이그레이션 | 구조 | PrimitiveTask + CompoundTask, TaskVerifier, CompileVerifier |
| D2: 멀티모델 tier | 성능 | SIMPLE/MEDIUM/COMPLEX 3단계 분업 |
| LLM stuck timeout | 안정 | callLLM watchdog — koog blocking IO 강제 종료 |
| E1: 후보 자가검토 | 품질 | improveLayer 후 SIMPLE LLM 독립 검토 게이트 |
| AgentLayer: runGoal | 구조 | GoalExecutor 인터페이스 + 서브 ReAct 루프 위임 |
| S1: SessionLayer | 메모리 | 세션 기반 실행 이력 — 시간순 append, 프롬프트 주입 |
| S1-UI: 세션 UI | 프론트 | 생성·활성화·초기화·삭제 패널, READ_ONLY 게이트 바이패스 |
| Exception-based errors | 구조 | ERROR 문자열 → throw 일괄 교체. DevelopLayer·FileSystemLayer·ReviewLayer |
| isActualSuccess 제거 | 구조 | 문자열 prefix 판별 삭제. consecutiveErrors·successfulFunctions → NodeStatus 기반 |
| normalizeLayerName camelCase | 버그 | "agentLayer" → "Agent" (replaceFirstChar 누락 수정) |
| autoReview 제거: SA 단일 경로 | 구조 | improveLayer autoReview 파라미터 삭제 → @RequiresSelfAction만 사용 |
| MCP hana 등록 | 인프라 | `claude mcp add --transport sse hana http://localhost:8080/mcp` (프로젝트 scope) |

## 3. Mission 로드맵 — 시간 추정

### A. ReAct 강화 (LLM 헛발 보완)
- [x] **A1. Failure ledger** — 같은 함수 ≥2 실패 → hint 주입
- [ ] **A2. Goal restate** ~15min → 매 step 프롬프트 상단에 원 query 박제
- [x] **A3. AgentLayer.runGoal** — GoalExecutor 인터페이스, 서브 ReAct 루프 위임 (테스트 완료)
- [ ] **A4. Prompt 정교화** ~30min → `{{parent}}` 데이터 흐름 강조

### B. 안전 가드 (자기수정 신뢰성)
- [x] B1. PR1 Two-Phase Commit
- [x] B2. PR2 finish verifier
- [x] B3. PR3a writeFile guard
- [x] B4-B5. HTN PrimitiveTask/CompoundTask + CompileVerifier
- [ ] **B6. 컴파일 sandbox** ~1-2h → swap 전 별도 위치 컴파일 검증 (현재는 swap 후 컴파일)
- [ ] **B7. 멀티파일 코디네이션** ~3-4h → HTN CompoundTask 확장. `improveMultipleLayers([A,B,C])`: 후보 일괄 생성(primitive별 SA 억제) → CandidateSet 단위로 sandbox 컴파일 → 실패 시 에러 파싱으로 문제 파일 특정 → 해당 candidate만 재생성 → 재컴파일 → 성공 시 atomic commit. `@RequiresSelfAction`은 CompoundTask 수준에서만 발동. B6 sandbox 위에서 구현.

### C. 메모리·RAG (Long-context)
- [ ] **C1. 실패 패턴 임베딩** ~2h → 과거 실패 ledger 임베딩. 비슷한 task 만나면 hint 주입
- [ ] **C2. 성공 패턴 임베딩** ~2h → "이런 query엔 이 tree 통했음" 패턴 캐시
- [ ] **C3. ABox 정확도 개선** ~1h → 임베딩 품질 평가·튜닝

### D. 멀티모델 분업
- [x] D1. LLMConfig simple/medium/complex tier
- [x] D2. 실제 tier 활용 (exaone3.5:2.4b / 7.8b / qwen3:14b)
- [ ] **D3. 자가비판 별도 모델** ~1h → 자기 출력을 다른 모델로 리뷰

### E. 자기비판 (Self-Critique)
- [x] **E1. 후보 자가검토** — improveLayer 후 SIMPLE LLM 독립 품질 게이트 (테스트 완료)
- [ ] **E2. action 자가검토** ~30min → execute_tree 결정 후 실행 전 한번 더 검토

### F. Paradigm Shift
- [ ] **F1. Plan-Execute-Verify** ~5-8h → PLAN(N step + expected outcome) → executor(expected vs actual) → 부분 replan

## 4. 우선순위 (다음 세션 시작점)

1. **B6 컴파일 sandbox** ← **여기 시작**
2. E2 action 자가검토
3. 실제 복잡 쿼리 관찰 → 약점 기반 다음 과제 결정

## 5. 절대 위반 금지

- 레이어 격리 원칙: 레이어 코드/KDoc에 타 레이어명 언급 금지 (`src/.../layer/CLAUDE.md`)
- 자가개선 게이트: `improveLayer()` 는 `.hana/candidates/` 에만 저장. 원본 직접 덮어쓰기 금지
- `[필수후속]` / `@RequiresSelfAction`: improveLayer 후 reviewLayerCandidate 자동 호출 필수
- LLM JSON schema 강제 (`LLMParams.Schema.JSON.Basic`)
- 레이어 .kt 변경은 `develop.applyLayerCandidate` 만 (PR3a 가드)

## 6. 회귀 테스트 빠른 검증

```bash
# happy path
curl -s -X POST http://localhost:8080/chat -H "Content-Type: application/json" \
  -d '{"message":"현재 git 브랜치 이름 알려줘"}' | jq -r .result

# verifier 차단 케이스
curl -s -X POST http://localhost:8080/chat -H "Content-Type: application/json" \
  -d '{"message":"/tmp/존재안함.txt 읽어줘"}' | jq -r .result
```

## 7. 코드 위치 빠른 참조

- ReAct 루프: `src/main/kotlin/com/hana/orchestrator/orchestrator/core/DefaultReActStrategy.kt`
- 자가개선: `src/main/kotlin/com/hana/orchestrator/layer/DevelopLayer.kt`
- 보호 규칙: `src/main/kotlin/com/hana/orchestrator/layer/FileSystemLayer.kt` (`protectionReason`)
- LLM 호출: `src/main/kotlin/com/hana/orchestrator/llm/OllamaLLMClient.kt`
- 프롬프트 빌더: `src/main/kotlin/com/hana/orchestrator/llm/LLMPromptBuilder.kt`
- MCP: `src/main/kotlin/com/hana/orchestrator/presentation/controller/McpController.kt`

## 8. 알려진 이슈 (별도 PR 대상)

### Ollama / Java LLM 호출 stuck — 해결됨 (부분)
- watchdog 코루틴이 timeoutMs 후 `ollamaClient.close()` 호출 → callLLM + generateDirectAnswer 모두 적용
- 단, `close()`가 koog 내부 blocking IO를 항상 끊지는 못함 — LLM이 결국 응답하면 정상 완료
- improveLayer 대형 프롬프트는 4~6분 소요 — timeout 발동해도 LLM이 응답하면 정상 처리

### AgentLayer.runGoal 서브루프 이력 분리
- 서브루프 stepHistory가 외부 루프에 노출되지 않음 (설계상 의도)
- 디버깅 시 서브 executionId 별도 추적 필요 (현재 UI에 노출 안 됨)

## 9. 관찰된 14B 약점 (구조로 메울 것들)

- **layerName 표기 혼동** — kebab/Pascal/snake 자주 섞음 → `normalize` PR 로 해결
- **{{parent}} 잘못 씀** — findFiles 결과를 readFile 의 path 로 그대로 넘김 등 → A4 prompt 정교화
- **같은 실수 반복** — ERROR 봤는데도 다음 step 동일 패턴 → A1 failure ledger
- **창의적 우회** — PR1 우회 (writeFile 로 직접 swap) → PR3a 차단
- **finish 조급증** — ERROR 결과로 finish 시도 → PR2 verifier
- **합리화 한 줄 답변** — 시도 안 해보고 "안 됨" 답변 → PR2 verifier 가 답변 자세하게 유도

---

_마지막 업데이트: 2026-05-21 (세션3). autoReview 제거·@RequiresSelfAction 단일 경로 확립. MCP hana 등록. "agentLayer 코드 개선해줘" 3스텝 COMPLETED 확인._

## 10. MCP 사용법 (다음 세션)

```bash
# 다음 세션에서 hana MCP 툴 자동 로드됨
# chat_async → get_execution 폴링 패턴 사용

# 비동기 실행 시작
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"chat_async","arguments":{"message":"..."}}}' | jq .

# 상태 폴링
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_execution","arguments":{}}}' \
  | jq -r '.result.content[0].text' | jq '{status, logs: .logs[-5:]}'
```
