# hana-orchestrator

**개인 실험 프로젝트** - 개발 진행 중인 AI 오케스트레이션 시스템

> **참고**: 이 프로젝트는 개인적인 실험 및 학습 목적으로 개발 중인 프로젝트입니다. 프로덕션 사용을 권장하지 않습니다.

## 개요

Hana Orchestrator는 **LLM 기반 오케스트레이션**을 통해 여러 레이어를 조율하는 시스템입니다. 핵심 설계 철학은 **레이어의 LLM 의존성 제거**와 **오케스트레이터의 LLM 사용 일원화**를 통해 정확하고 일관된 결과를 도출하는 것입니다.

## 핵심 설계 원칙

### 🎯 LLM 사용 일원화
- **오케스트레이터만 LLM을 사용**: 모든 의사결정(실행 트리 생성, 결과 평가, 재시도 전략 등)은 오케스트레이터의 LLM 클라이언트를 통해 수행
- **레이어는 LLM 독립적**: 각 레이어는 순수하게 자신의 기능만 수행하며, LLM에 의존하지 않음
- **정확한 결과 도출**: 레이어별 LLM 사용으로 인한 불일치를 방지하고, 오케스트레이터가 전체 컨텍스트를 고려한 일관된 판단 수행

### 🔌 PnP 형태의 레이어 교체
- **플러그 앤 플레이**: 레이어를 쉽게 추가/제거/교체 가능
- **표준 인터페이스**: `CommonLayerInterface` 하나로 모든 레이어 통합 관리
- **동적 등록**: 런타임에 레이어 등록 및 해제 가능

### 🌐 분산 아키텍처 용이
- **로컬/원격 레이어 동일 처리**: HTTP 기반 표준 통신으로 원격 레이어를 로컬과 동일하게 사용
- **독립적 서버 배포**: 각 레이어를 별도 서버로 배포 가능
- **서비스 발견 및 관리**: 자동 포트 할당 및 서비스 레지스트리 관리

## 주요 기능

### 🤖 ReAct 모드 실행 (기본)
- **기본 실행 모드**: LLM이 단계별로 판단하며 실행 (`mode: "reactive"`)
- **ReAct 루프**: 각 단계에서 LLM이 미니 트리 생성 → 실행 → 결과 확인 → 다음 행동 결정
- 최대 200스텝으로 무한루프 방지
- 이전 단계 결과를 컨텍스트로 활용하여 점진적 실행

### 🔄 실행 트리 기반 워크플로우 (레거시 모드)
- LLM이 생성한 실행 계획을 트리 구조로 관리 (`mode: "tree"`)
- 병렬/순차 실행 자동 처리
- 노드별 성공/실패 추적 및 재시도 전략

### 🔁 자가개선 루프 (Self-Improvement Loop)
- **후보 게이트**: `develop.improveLayer()` → `.hana/candidates/`에 저장, 원본 즉시 수정 금지
- **2단계 적용**: diff 확인 후 `applyLayerCandidate()` 또는 `rejectLayerCandidate()`
- **컴파일 검증**: 후보 적용 시 자동 `compileKotlin` — 실패 시 `.bak` 복구
- **`[필수후속]` 태그**: `improveLayer()` 결과가 apply/reject 없이 finish 불가하도록 강제
- **복리 설계**: 루프가 `DevelopLayer`, `LLMPromptBuilder` 등 자기 자신도 개선 가능
- **스케줄러 연동**: `.hana/jobs/` 설정으로 루프 주기·자동승인 제어

### 🛡️ 파일 수정 승인 게이트
- **P0 안전장치**: FileSystemLayer의 파일 쓰기 전 사용자 승인 요청
- **코루틴 일시정지**: 승인 대기 중 실행 흐름 유지
- **변경 내용 미리보기**: unified diff 형식으로 변경사항 표시
- **autoApprove per-node**: 노드별 자동 승인 설정 가능 (스케줄러 연동)
- UI에서 승인/거부 가능

### ❓ ClarificationGate
- **불명확한 요청 처리**: LLM이 실행 전 추가 정보가 필요하면 질문 생성
- **코루틴 일시정지**: 답변 대기 중 실행 흐름 유지
- **답변 후 재개**: 사용자 답변을 컨텍스트에 반영 후 자동 재실행
- API: `GET /clarification/pending`, `POST /clarification/{id}/answer`

### ⏱️ 스케줄러 (Job Scheduler)
- **`.hana/jobs/` 설정**: JSON 파일로 반복 실행 작업 정의
- **자동승인 게이트**: 스케줄 작업의 자동 승인/거부 규칙 설정
- **무인 자가개선 루프**: 스케줄러 + autoApprove 조합으로 야간 자가개선 실행
- API: `GET/POST /jobs`, `GET/PATCH/DELETE /jobs/{id}`, `POST /jobs/{id}/trigger`

### 📊 메트릭 (Metrics)
- **레이어별 실행 통계**: 호출 횟수, 성공/실패율, 평균 응답시간
- **자가개선 메트릭**: 후보 생성/적용/거부 횟수 추적
- API: `GET /metrics`

### 🌳 인터랙티브 트리 편집기
- **Cytoscape.js 시각화**: 실행 트리를 그래프로 시각화
- **노드 추가/삭제**: 팔레트에서 레이어 선택 후 트리에 추가
- **엣지 삭제**: 호버 시 삭제 버튼으로 연결 제거
- **Args 편집**: 노드별 입력값 직접 수정
- **LLM 검토**: 편집된 트리를 실행 전 LLM이 검토
- **트리 저장/로드**: `.hana/trees/{name}.json`에 영구 저장

### 💾 실행 트리 저장/재사용
- 잘 동작하는 실행 트리를 이름과 함께 저장
- 저장된 트리를 즉시 재실행 (검토 없이 바로 실행 가능)
- 스와이프 삭제로 트리 관리
- 원자적 파일 쓰기로 저장 안전성 보장

### 🏗️ BuildLayer
- **빌드 자동화**: `compileKotlin`, `build`, `clean`, `runBuild` 함수 제공
- **자기 개선 지원**: `restart()` — 새 JAR로 빌드 후 재시작
- 다양한 빌드 도구 지원 (Gradle, Maven, npm, cargo 등)
- 프로젝트 루트 상대 경로 강제로 보안 보장

### 🐚 ShellLayer
- **OS-aware 셸 실행**: macOS/Linux: `sh -c`, Windows: `cmd /c`
- **타임아웃 보호**: 기본 30초, 초과 시 프로세스 강제 종료
- **출력 제한**: stdout+stderr 5000자 초과 시 잘라냄
- **exit code 비정상 시**: `ERROR(exit=N):` 접두어로 반환

### 🔬 DevelopLayer (자가개선 전용)
- **후보 생성**: `improveLayer()` → `.hana/candidates/` 저장, 원본 보존
- **후보 적용**: `applyLayerCandidate()` → 컴파일 검증 후 원본 교체 (실패 시 `.bak` 복구)
- **전략 후보**: `createStrategyCandidate()` → `DefaultReActStrategy` 개선 후보 생성
- **핫로드**: `hotLoadStrategy()` → 컴파일 없이 전략 런타임 교체
- **[필수후속] 태그**: `improveLayer()` 리턴 시 apply/reject 없이 finish 불가하도록 강제

### 🧪 CoreEvaluationLayer
- **RC 후보 평가**: `evaluateReport()` → 현재 소스 vs RC 후보 비교 보고서
- **RC 적용**: `applyCandidate()` → RC를 현재 소스에 반영
- **시나리오 실행**: 전략 후보를 실제 시나리오로 평가 ("시스템 콜" 역할)

### 📊 실시간 실행 모니터링
- WebSocket을 통한 실시간 실행 상태 업데이트
- 실행 이력 및 상세 로그 추적
- 실행 트리 시각화

### 🔍 요구사항 사전검증
- 사용자 쿼리의 실행 가능성 사전 검증
- 사용 불가능한 요청에 대한 명확한 피드백

### 📁 파일 시스템 레이어
- 파일 읽기/쓰기, 디렉토리 목록 조회, 파일 검색, 백업 기능
- 상대 경로 사용 강제로 경로 해석 오류 방지
- 자동 백업 기능으로 파일 수정 안전성 보장

### 🔀 Git 레이어
- `clone`, `commit`, `push`, `pull`, `status` 등 주요 Git 작업 지원

### 📌 컨텍스트 관리
- **영구·휘발 컨텍스트**: 트리 생성/평가 시 LLM 프롬프트에 항상 주입 (영구 + 휘발 + 본문 순)
- **휘발성**: 요청 시 클라이언트 `context` 반영, 서버가 `workingDirectory`·`projectRoot` 자동 설정
- **영구**: `projectRoot` 변경 시 `.cursor/rules` 또는 `AGENTS.md` 로드해 `projectRules` 저장 (파일: `.hana/context/persistent-context.json`)
- 상세: [컨텍스트 배치 설계](docs/context-placement-design.md), [컨텍스트 관리 설계](docs/context-management-design.md)

### 🎯 Structured Outputs (Ollama format 강제)
- `JsonSchemaBuilder`로 ReAct 결정 스킴 생성 → `LLMParams.Schema.JSON.Basic`으로 `Prompt.withUpdatedParams` 경유 Ollama `format` 필드 전달
- 모델이 `execute_tree` / `finish` 외 포맷 출력 불가
- 파싱 실패 시 자동 재시도 (최대 2회)

## 아키텍처

### 오케스트레이터 구조

```
┌─────────────────────────────────────────────────────────────┐
│                    사용자 요청                                │
│              "Hello World를 대문자로 변환해줘"                │
└───────────────────────┬─────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────────┐
│                    Orchestrator                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  LLM Client (일원화)                                 │   │
│  │  • ReAct 루프 실행 (기본)                            │   │
│  │  • 실행 트리 생성 (레거시)                            │   │
│  │  • 결과 평가 + 자기 개선 재시도                       │   │
│  │  • 트리 검토 및 승인 게이트                           │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Layer Registry                                       │   │
│  │  • BuildLayer, FileSystemLayer, GitLayer, LLMLayer   │   │
│  │  • 원격 레이어: RemoteLayer("http://...")           │   │
│  └──────────────────────────────────────────────────────┘   │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │                 │
        ▼               ▼                 ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  Layer 1     │ │  Layer 2     │ │  Layer N     │
│ (LLM 독립)   │ │ (LLM 독립)   │ │ (LLM 독립)   │
│              │ │              │ │              │
│ 순수 기능만  │ │ 순수 기능만  │ │ 순수 기능만  │
│ 수행         │ │ 수행         │ │ 수행         │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 실행 흐름 (ReAct 모드, 기본)

```
사용자 요청
    │
    ▼
요구사항 검증 (LLM)
    │
    ▼
┌──────────────────────────────────┐
│  ReAct 루프 (최대 15스텝)         │
│                                  │
│  LLM 판단 → 미니 트리 생성       │
│       → TreeExecutor 실행        │
│       → 결과 확인                │
│       → 다음 행동 결정           │
│       (finish or next step)      │
└──────────────────────────────────┘
    │
    ▼
결과 평가 (LLM) → 불만족 시 최대 5회 재시도
    │
    ▼
최종 응답
```

### LLM 아키텍처 (단일 Ollama 서버, 다중 모델)

```
┌─────────────────────────────────────────────────────────────┐
│              Ollama 서버 (단일 인스턴스)                      │
│              http://localhost:11434                         │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Model Runner │  │ Model Runner │  │ Model Runner │     │
│  │ qwen3:8b     │  │ qwen3:8b     │  │ qwen3:8b     │     │
│  │ (SIMPLE)     │  │ (MEDIUM)     │  │ (COMPLEX)    │     │
│  │              │  │              │  │              │     │
│  │ 독립 프로세스 │  │ 독립 프로세스 │  │ 독립 프로세스 │     │
│  │ 병렬 실행    │  │ 병렬 실행    │  │ 병렬 실행    │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

**핵심**: 단일 Ollama 서버가 여러 모델 러너를 관리하며, 각 러너는 독립적인 프로세스로 병렬 실행됩니다.

자세한 내용은 [Ollama 다중 모델 아키텍처 문서](docs/ollama-multi-model-architecture.md)를 참조하세요.

## Core Interface

```kotlin
interface CommonLayerInterface {
    // 레이어 자기 기술 정보
    suspend fun describe(): LayerDescription

    // 실제 작업 실행 (LLM 독립적)
    suspend fun execute(function: String, args: Map<String, Any>): String
}
```

**중요**: 레이어는 LLM을 사용하지 않습니다. 모든 LLM 호출은 오케스트레이터에서만 수행됩니다.

## 사용 예시

### 레이어 구현 (LLM 독립적)

```kotlin
class TextTransformerLayer : CommonLayerInterface {
    override suspend fun describe() = LayerDescription(
        name = "text-transformer",
        description = "텍스트 변환 기능",
        functions = listOf("toUpperCase", "toLowerCase")
    )

    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "toUpperCase" -> {
                val text = args["text"] as String
                text.uppercase()  // 순수 기능만 수행
            }
            // ...
        }
    }
}
```

### 원격 레이어 사용

```kotlin
// 다른 서버에 있는 레이어 연결
val remoteLayer = RemoteLayer("http://file-server:8081")
orchestrator.registerLayer(remoteLayer)

// 사용은 로컬 레이어와 동일
val result = remoteLayer.execute("read_file", mapOf("path" to "config.json"))
```

## 빌드 및 실행

### 사전 요구사항
- Java 17+
- Kotlin 1.9+
- Ollama (로컬 설치 필요, GPU 가속 권장)

### 빌드
```bash
./gradlew build
```

### 실행

#### 1. Ollama 설치 및 실행
```bash
# macOS
brew install ollama
ollama serve

# 필요한 모델 다운로드
ollama pull gemma2:2b
ollama pull llama3.1:8b
```

#### 2. 애플리케이션 실행
```bash
./gradlew run
```

**참고**:
- 프로그램 시작 시 자동으로 `PortAllocator.cleanupHanaPorts()`를 호출하여 기존 실행 중인 Hana 서비스를 그레이스풀하게 종료합니다.
- Ollama가 실행 중이어야 합니다 (기본 포트: 11434)

### API 테스트

#### 서버 시작
```bash
# 서버 실행 (자동으로 기존 서비스 정리)
./gradlew run

# 기존 서비스 정리 없이 실행 (개발용)
./gradlew run --args="--skip-cleanup"
```

#### 기본 테스트
```bash
# Health check
curl http://localhost:8080/health

# Chat API - ReAct 모드 (기본)
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello World를 대문자로 변환해줘"}'

# Chat API - 트리 모드 (레거시)
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello World를 대문자로 변환해줘","mode":"tree"}'

# context 포함 (currentFile, selection 등; workingDirectory·projectRoot는 서버가 자동 설정)
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"현재 파일 내용 요약해줘","context":{"currentFile":"src/App.kt","selection":"fun main"}}'

# 실행 이력 조회
curl http://localhost:8080/executions

# 원격 레이어 등록
curl -X POST http://localhost:8080/layers/register-remote \
  -H "Content-Type: application/json" \
  -d '{"baseUrl":"http://remote-layer:8081"}'
```

#### 트리 편집기 API
```bash
# 편집된 트리 LLM 검토
curl -X POST http://localhost:8080/tree/review \
  -H "Content-Type: application/json" \
  -d '{"query":"작업 내용","tree":{...}}'

# 편집된 트리 직접 실행
curl -X POST http://localhost:8080/tree/execute \
  -H "Content-Type: application/json" \
  -d '{"query":"작업 내용","tree":{...}}'

# 트리 저장
curl -X POST http://localhost:8080/trees/save \
  -H "Content-Type: application/json" \
  -d '{"name":"my-tree","query":"작업 내용","tree":{...}}'

# 저장된 트리 목록 조회
curl http://localhost:8080/trees

# 특정 트리 로드
curl http://localhost:8080/trees/my-tree

# 트리 삭제
curl -X DELETE http://localhost:8080/trees/my-tree
```

#### 승인 게이트 API
```bash
# 대기 중인 승인 요청 목록
curl http://localhost:8080/approval/pending

# 승인
curl -X POST http://localhost:8080/approval/{id}/approve

# 거부
curl -X POST http://localhost:8080/approval/{id}/reject
```

#### ClarificationGate API
```bash
# 대기 중인 질문 목록
curl http://localhost:8080/clarification/pending

# 답변 제출
curl -X POST http://localhost:8080/clarification/{id}/answer \
  -H "Content-Type: application/json" \
  -d '{"answer": "답변 내용"}'
```

#### 스케줄러 API
```bash
# 작업 목록 조회
curl http://localhost:8080/jobs

# 작업 생성
curl -X POST http://localhost:8080/jobs \
  -H "Content-Type: application/json" \
  -d '{"query":"자가개선 루프 실행","cronExpr":"0 2 * * *","autoApprove":true}'

# 작업 즉시 트리거
curl -X POST http://localhost:8080/jobs/{id}/trigger

# 작업 수정/삭제
curl -X PATCH http://localhost:8080/jobs/{id} -d '{"enabled":false}'
curl -X DELETE http://localhost:8080/jobs/{id}
```

#### 메트릭 API
```bash
# 레이어별 실행 통계
curl http://localhost:8080/metrics
```

#### WebSocket (실시간 실행 상태)
```
ws://localhost:8080/ws/executions
```

#### 서버 종료
```bash
# 그레이스풀 셧다운
curl -X POST http://localhost:8080/shutdown \
  -H "Content-Type: application/json" \
  -d '{"reason": "테스트 완료"}'
```

### LLM 상태 확인
```bash
# LLM 상태 확인 (로컬 Ollama 서버 및 모델 확인)
curl http://localhost:8080/llm-status
```

**참고**: 현재는 로컬 Ollama를 사용합니다. GPU 가속을 위해 Mac에서는 Metal을 활용합니다.

## 프로젝트 구조

```
hana-orchestrator/
├── src/main/kotlin/com/hana/orchestrator/
│   ├── application/                # 앱 생명주기
│   │   ├── bootstrap/ApplicationBootstrap.kt
│   │   ├── lifecycle/ApplicationLifecycleManager.kt
│   │   ├── port/PortManager.kt
│   │   └── server/ServerConfigurator.kt
│   ├── context/                    # 컨텍스트 관리 (영구/휘발, AppContextService, PromptComposer)
│   ├── domain/                     # 도메인 엔티티
│   │   ├── dto/ChatDto.kt          # mode: "reactive" | "tree"
│   │   └── entity/
│   │       ├── ExecutionTree.kt
│   │       ├── ExecutionNode.kt
│   │       ├── ExecutionHistory.kt
│   │       └── ...
│   ├── layer/                      # 레이어 인터페이스 및 구현
│   │   ├── CommonLayerInterface.kt  # 핵심 인터페이스
│   │   ├── BuildLayer.kt           # 빌드 자동화 (compileKotlin, build, restart)
│   │   ├── CoreEvaluationLayer.kt  # RC 후보 평가/적용 (시스템 콜)
│   │   ├── DevelopLayer.kt         # 자가개선 전용 (후보 게이트, 핫로드)
│   │   ├── EchoLayer.kt            # 예제 레이어
│   │   ├── FileSystemLayer.kt      # 파일 시스템 작업
│   │   ├── GitLayer.kt             # Git 작업 (clone, commit, push 등)
│   │   ├── LLMLayer.kt             # LLM 직접 답변
│   │   ├── LayerInfoLayer.kt       # 레이어 정보 조회
│   │   ├── RemoteLayer.kt          # 원격 레이어
│   │   ├── ShellLayer.kt           # OS-aware 셸 명령 실행
│   │   ├── TextTransformerLayer.kt # 텍스트 변환
│   │   ├── TextValidatorLayer.kt   # 텍스트 검증
│   │   └── LayerFactory.kt         # 레이어 생성 도구
│   ├── orchestrator/               # 오케스트레이터 구현
│   │   ├── Orchestrator.kt         # 핵심 파사드
│   │   ├── ApprovalGate.kt         # 파일 수정 승인 게이트
│   │   ├── CandidateRegistry.kt    # 자가개선 후보 관리
│   │   ├── ClarificationGate.kt    # 불명확한 요청 질문 게이트
│   │   ├── JobRepository.kt        # 스케줄 작업 저장/로드 (.hana/jobs/)
│   │   ├── JobScheduler.kt         # 주기적 작업 실행
│   │   ├── MetricsService.kt       # 레이어별 실행 메트릭
│   │   ├── ReActTreeConverter.kt   # ReAct 스텝 → 트리 변환
│   │   ├── TreeRepository.kt       # 트리 저장/로드 (.hana/trees/)
│   │   └── core/
│   │       ├── ReactiveExecutor.kt      # ReAct 루프 (max 200스텝)
│   │       ├── DefaultReActStrategy.kt  # 기본 ReAct 전략 (자가개선 대상)
│   │       ├── LayerManager.kt          # 레이어 런타임 관리
│   │       └── TreeExecutor.kt          # 실행 트리 실행 (병렬/순차)
│   ├── llm/                        # LLM 클라이언트 (오케스트레이터 전용)
│   │   ├── LLMClient.kt
│   │   ├── OllamaLLMClient.kt
│   │   ├── LLMPromptBuilder.kt
│   │   ├── LLMResponseModels.kt    # ReActStep, ReActDecision, ResultEvaluation 등
│   │   ├── JsonSchemaBuilder.kt
│   │   └── JsonExtractor.kt
│   ├── presentation/               # 프레젠테이션 레이어
│   │   ├── controller/
│   │   │   ├── ChatController.kt           # POST /chat
│   │   │   ├── TreeController.kt           # /tree/*, /trees/*
│   │   │   ├── ApprovalController.kt       # /approval/*
│   │   │   ├── ClarificationController.kt  # /clarification/*
│   │   │   ├── SchedulerController.kt      # /jobs/*
│   │   │   ├── MetricsController.kt        # /metrics
│   │   │   ├── ExecutionWebSocketController.kt  # ws /ws/executions
│   │   │   ├── LayerController.kt
│   │   │   ├── HealthController.kt
│   │   │   └── ServiceController.kt
│   │   ├── mapper/
│   │   └── model/
│   ├── service/                    # 서비스 레이어
│   │   ├── ServiceRegistry.kt
│   │   ├── ServiceDiscovery.kt
│   │   └── PortAllocator.kt
│   └── Application.kt
├── src/main/resources/static/
│   ├── index.html                  # 대시보드 UI (트리 편집기, 승인 모달 포함)
│   └── app.js                      # Cytoscape.js 트리 시각화 및 편집 로직
├── docker/
│   ├── docker-compose.yml
│   └── Dockerfile.*
├── docs/
└── build.gradle.kts
```

## 최근 주요 변경사항

### 자가개선 루프 완성 (2026-04)
- **후보 게이트**: `DevelopLayer.improveLayer()` → `.hana/candidates/` 저장, 원본 보존
- **`[필수후속]` 태그**: apply/reject 없이 finish 불가하도록 LLM 강제
- **Ollama Structured Outputs**: `LLMParams.Schema.JSON.Basic` + `Prompt.withUpdatedParams`로 ReAct 결정 JSON 스킴 강제
- **자가 자신 개선**: `DevelopLayer`, `DefaultReActStrategy`, `LLMPromptBuilder` 모두 자가개선 대상

### ShellLayer / DevelopLayer / CoreEvaluationLayer 추가
- **ShellLayer**: OS-aware 셸 명령 실행 (타임아웃, 출력 제한 포함)
- **DevelopLayer**: 자가개선 전용 레이어 (후보 생성, 전략 핫로드, 컴파일 검증)
- **CoreEvaluationLayer**: RC 후보 평가·적용 (시나리오 실행 포함)

### ClarificationGate + 스케줄러 + 메트릭
- **ClarificationGate**: LLM이 불명확한 요청에 질문 → 답변 후 재개
- **JobScheduler**: `.hana/jobs/` 기반 반복 작업, autoApprove per-node 지원
- **MetricsService**: 레이어별 호출 통계 실시간 추적

### ReAct 루프 안정화
- **ReactiveExecutor** → `orchestrator/core/` 이동
- **max 200스텝** (15스텝에서 증가)
- **DefaultReActStrategy**: 전략 인터페이스(`ReActStrategy`) 분리 → 핫로드 가능
- **alreadyDoneNote**: 동일 함수 중복 호출 방지

### 실행 트리 저장/재사용
- **TreeRepository**: `.hana/trees/{name}.json`에 원자적으로 저장
- 저장된 트리 즉시 재실행 (LLM 검토 없이도 가능)

### 파일 수정 승인 게이트
- **ApprovalGate**: 파일 쓰기 전 사용자 승인을 코루틴으로 대기
- unified diff 형식으로 변경 내용 미리보기

### 인터랙티브 트리 편집기
- **Cytoscape.js** 기반 DAG 시각화
- 노드 추가/삭제, 엣지 삭제, Args 편집

### 컨텍스트 관리
- **AppContextService**: 앱 단위 영구·휘발 컨텍스트 관리
- **PromptComposer**: 영구 블록 + 휘발 블록 + 본문
- **FileBackedContextStore**: `.hana/context/persistent-context.json`에 저장

## 확장성

### 새로운 레이어 추가
1. `CommonLayerInterface` 구현
2. `describe()`와 `execute()` 메소드 구현 (LLM 사용 없이)
3. 오케스트레이터에 등록

### 원격 레이어 배포
1. 레이어를 별도 서버로 배포
2. `CommonLayerInterface`를 HTTP 엔드포인트로 노출
3. 오케스트레이터에서 `RemoteLayer`로 등록

## 기술 스택

- **Kotlin**: 메인 언어
- **Ktor**: 웹 프레임워크 및 HTTP 클라이언트
- **Kotlinx Serialization**: JSON 직렬화
- **Kotlinx Coroutines**: 비동기 처리 (승인 게이트 일시정지 포함)
- **Cytoscape.js**: 트리 편집기 시각화
- **KSP (Kotlin Symbol Processing)**: 메타데이터 자동 생성
- **Gradle**: 빌드 도구
- **Ollama**: 로컬 LLM 서버 (단일 인스턴스, 다중 모델 지원)
- **ai.koog**: LLM 프레임워크 (프롬프트 실행 및 Structured Outputs 지원)

## 라이선스

MIT License

## 기여

이 프로젝트는 개인 실험 프로젝트입니다. Issue나 Pull Request는 환영하지만, 프로덕션 사용을 권장하지 않습니다.
