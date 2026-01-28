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

### 🎯 자기기술 레이어
- 각 레이어가 자신의 기능을 스스로 설명 (`describe()`)
- KSP 프로세서를 통한 함수 메타데이터 자동 생성
- 오케스트레이터가 자동으로 레이어 등록과 관리

### 🔄 실행 트리 기반 워크플로우
- LLM이 생성한 실행 계획을 트리 구조로 관리
- 병렬/순차 실행 자동 처리
- 노드별 성공/실패 추적 및 재시도 전략

### 📊 실시간 실행 모니터링
- WebSocket을 통한 실시간 실행 상태 업데이트
- 실행 이력 및 상세 로그 추적
- 실행 트리 시각화

### 🔍 요구사항 사전검증
- 사용자 쿼리의 실행 가능성 사전 검증
- 사용 불가능한 요청에 대한 명확한 피드백

## 아키텍처

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
│  │  • 실행 트리 생성                                     │   │
│  │  • 결과 평가                                         │   │
│  │  • 재시도 전략 생성                                  │   │
│  │  • 요구사항 검증                                     │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Layer Registry                                       │   │
│  │  • 로컬 레이어: TextTransformerLayer, ...            │   │
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

### 실행 흐름

1. **사용자 요청**: "Hello World를 대문자로 변환해줘"
2. **요구사항 검증**: 오케스트레이터의 LLM이 실행 가능 여부 확인
3. **실행 트리 생성**: 오케스트레이터의 LLM이 필요한 레이어와 순서 결정
4. **레이어 실행**: 각 레이어는 순수하게 기능만 수행 (LLM 사용 없음)
5. **결과 평가**: 오케스트레이터의 LLM이 최종 결과 평가 및 재시도 결정

## 빌드 및 실행

### 사전 요구사항
- Java 17+
- Kotlin 1.9+
- Ollama (LLM 서버, 로컬 또는 원격)
- Docker (선택사항)

### 빌드
```bash
./gradlew build
```

### 실행
```bash
./gradlew run
```

**참고**: 프로그램 시작 시 자동으로 `PortAllocator.cleanupHanaPorts()`를 호출하여 기존 실행 중인 Hana 서비스를 그레이스풀하게 종료합니다.

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

# Chat API 테스트
curl -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hello World를 대문자로 변환해줘"}'

# 실행 이력 조회
curl http://localhost:8080/executions

# 원격 레이어 등록
curl -X POST http://localhost:8080/layers/register-remote \
  -H "Content-Type: application/json" \
  -d '{"baseUrl":"http://remote-layer:8081"}'
```

#### 서버 종료
```bash
# 그레이스풀 셧다운
curl -X POST http://localhost:8080/shutdown \
  -H "Content-Type: application/json" \
  -d '{"reason": "테스트 완료"}'
```

### Docker로 실행
```bash
cd docker
docker-compose up --build
```

## 프로젝트 구조

```
hana-orchestrator/
├── src/main/kotlin/com/hana/orchestrator/
│   ├── layer/                      # 레이어 인터페이스 및 구현
│   │   ├── CommonLayerInterface.kt  # 핵심 인터페이스
│   │   ├── RemoteLayer.kt          # 원격 레이어
│   │   ├── EchoLayer.kt            # 예제 레이어
│   │   ├── TextGeneratorLayer.kt   # 텍스트 생성 레이어
│   │   ├── TextTransformerLayer.kt # 텍스트 변환 레이어
│   │   └── LayerFactory.kt         # 레이어 생성 도구
│   ├── orchestrator/               # 오케스트레이터 구현
│   │   ├── Orchestrator.kt        # 핵심 오케스트레이터
│   │   └── ExecutionTreeValidator.kt
│   ├── llm/                        # LLM 클라이언트 (오케스트레이터 전용)
│   │   ├── OllamaLLMClient.kt      # Ollama LLM 클라이언트
│   │   └── LLMPromptBuilder.kt    # 프롬프트 빌더
│   ├── domain/                     # 도메인 엔티티
│   │   └── entity/
│   │       ├── ExecutionTree.kt
│   │       ├── ExecutionNode.kt
│   │       ├── ExecutionHistory.kt
│   │       └── ...
│   ├── presentation/               # 프레젠테이션 레이어
│   │   ├── controller/            # HTTP/WebSocket 컨트롤러
│   │   ├── mapper/                # 도메인 ↔ 프레젠테이션 변환
│   │   └── model/                  # DTO
│   ├── service/                    # 서비스 레이어
│   │   ├── ServiceRegistry.kt      # 서비스 레지스트리
│   │   ├── ServiceDiscovery.kt      # 서비스 발견
│   │   └── PortAllocator.kt       # 포트 할당
│   └── Application.kt             # 메인 애플리케이션
├── docker/                        # Docker 설정
├── docs/                          # 문서
└── build.gradle.kts              # 빌드 설정
```

## 최근 주요 변경사항

### 실행 트리 시각화
- 실행 계획을 트리 구조로 시각화하여 표시
- 노드별 실행 상태 및 결과 추적

### KSP 기반 메타데이터 자동 생성
- 함수 시그니처만으로 메타데이터 자동 추출
- 개발자가 수동으로 메타데이터 작성 불필요

### OOP 원칙 준수 리팩토링
- SRP, DRY, DIP 등 OOP 원칙 준수
- Mapper 분리 및 의존성 방향 개선

### 실시간 실행 모니터링
- WebSocket을 통한 실시간 상태 업데이트
- 실행 이력 및 상세 로그 추적

### 요구사항 사전검증
- 사용자 쿼리의 실행 가능성 사전 검증
- 불가능한 요청에 대한 명확한 피드백

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
- **Kotlinx Coroutines**: 비동기 처리
- **KSP (Kotlin Symbol Processing)**: 메타데이터 자동 생성
- **Gradle**: 빌드 도구
- **Docker**: 컨테이너화
- **Ollama**: LLM 서버

## 라이선스

MIT License

## 기여

이 프로젝트는 개인 실험 프로젝트입니다. Issue나 Pull Request는 환영하지만, 프로덕션 사용을 권장하지 않습니다.
