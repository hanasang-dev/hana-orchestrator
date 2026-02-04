# LLM 태스크 수준(Tier) 검증

## 개요

각 LLM 호출은 `@LLMTask(complexity = ...)` 로 SIMPLE / MEDIUM / COMPLEX 중 하나가 지정되며,  
**KSP가 생성한 `GeneratedModelSelectionStrategy`** 가 해당 복잡도에 맞는 클라이언트(모델 설정)를 선택한다.

## 태스크 → Tier 매핑 (보장되는 수준)

| LLM 태스크 | 복잡도 | 사용 클라이언트 | 용도 |
|------------|--------|------------------|------|
| `createExecutionTree` | COMPLEX | `createComplexClient()` | 실행 트리 생성 (긴 프롬프트, 레이어 목록) |
| `suggestRetryStrategy` | COMPLEX | `createComplexClient()` | 재처리 방안 제시 |
| `evaluateResult` | MEDIUM | `createMediumClient()` | 실행 결과가 요구사항 부합인지 평가 |
| `compareExecutions` | MEDIUM | `createMediumClient()` | 이전/현재 실행 비교 |
| `generateDirectAnswer` | MEDIUM | `createMediumClient()` | LLM 직접 답변 (LLMLayer) |
| `extractParameters` | SIMPLE | `createSimpleClient()` | 부모 결과 → 자식 함수 인자 추출 |
| `checkIfLLMCanAnswerDirectly` | SIMPLE | `createSimpleClient()` | LLM 직접 답변 가능 여부 판단 |

- **구현 위치**: `LLMClient.kt` 의 각 메서드에 `@LLMTask(complexity = ...)` 적용.
- **전략 구현**: `build/generated/ksp/.../GeneratedModelSelectionStrategy.kt` (KSP 자동 생성).
- **클라이언트 생성**: `LLMClientFactory` → `createSimpleClient()` / `createMediumClient()` / `createComplexClient()` 가 `LLMConfig` 의 simple/medium/complex 설정 사용.

## 기동 시 검증

서버 기동 시 다음 로그로 **실제 적용된 모델**을 확인할 수 있다.

```
📋 LLM 설정: simple=gemma2:2b, medium=gemma2:2b, complex=gemma2:2b
```

- `application.conf` 의 `llm.simple/medium/complex` (및 환경변수 오버라이드)가 이 값으로 적용된다.
- 각 tier별로 다른 모델을 쓰려면 `application.conf` 또는 `LLM_MEDIUM_MODEL` 등 환경변수로 구분 지정하면 된다.

## 트리 생성·실행 검증

- **트리 생성**: COMPLEX 클라이언트 사용. 로그에서 다음으로 확인 가능.
  - `🔄 [트리생성] LLM 호출 시작 (타임아웃: 60000ms)` → `✅ [트리생성] LLM 호출 완료`
  - 트리 생성 전용 타임아웃 60초로 제한 (무한 대기 방지).
- **실행**: 생성된 트리의 노드만 실행. 로그에서 다음으로 확인.
  - `🌳 [TreeExecutor] 실행 트리 시작: execution_plan (루트 노드 N개)`
  - `✅ 성공한 노드: N개`, `node_xxx: 레이어.함수 (depth=...)`

## 테스트 요약 (재시작 후)

- **단순 요청** (`"Hello를 echo로 출력해줘"`): 트리 생성(COMPLEX) → 단일 노드 `echo.echo` 실행 → 평가(MEDIUM) 정상 동작.
- **복잡 요청** (echo → toUpperCase → addPrefix 3단계): 트리 생성은 정상 완료하나, 현재 기본 모델(gemma2:2b)이 **멀티스텝을 단일 노드(echo)로 단순화**하는 경우가 있음. 트리 생성/실행/평가 파이프라인과 tier 매핑은 올바르게 동작함.

요약: **트리는 정상적으로 생성되고, 각 LLM 태스크의 수준(SIMPLE/MEDIUM/COMPLEX)은 코드 및 KSP 생성 전략으로 보장된다.**  
멀티스텝 트리가 더 안정적으로 나오게 하려면 COMPLEX용으로 더 큰 모델(예: llama3.1:8b)을 사용하는 것을 권장한다.
