# hana-orchestrator

Hana AI Orchestration Agent - Multi-layer AI agent orchestration system built with Kotlin and Ktor

## 개요

Hana Orchestrator는 단순하면서도 강력한 AI 오케스트레이션 시스템입니다. 분산 레이어 아키텍처를 기반으로 하여 유연한 확장성과 실용적인 개발 경험을 제공합니다.

## 핵심 특징

### 🎯 자기기술 레이어
- 각 레이어가 자신의 기능을 스스로 설명
- 오케스트레이터가 자동으로 레이어 등록과 관리
- 동적 레이어 확장 지원

### 🔄 분산 아키텍처
- 로컬 레이어와 원격 레이어 동일하게 처리
- HTTP 기반 표준 통신 프로토콜
- 독립적인 서버 배포 가능

### 🚀 단순한 통신
- 모든 결과는 문자열 기반
- LLM이 자연어로 결과 변환
- 복잡한 타입 시스템 없음

### 📊 파이프라인 제어
- layerDepth로 실행 순서 제어
- 동기/비동기 워크플로우 자동 관리
- 복잡한 작업의 단계별 처리

## 아키텍처

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   사용자 입력    │───▶│   오케스트레이터 │───▶│     레이어들     │
│                │    │                 │    │                 │
│ "파일 만들어줘"  │    │ • 자연어 분석    │    │ • 파일 처리      │
│                │    │ • 레이어 선택    │    │ • 코드 분석      │
│                │    │ • 결과 설명      │    │ • 저장 등        │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## Core Interface

```kotlin
interface CommonLayerInterface {
    // 레이어 자기 기술 정보
    suspend fun describe(): LayerDescription
    
    // 실제 작업 실행 (문자열 기반)
    suspend fun execute(function: String, args: Map<String, Any>): String
}

data class LayerDescription(
    val name: String,           // 레이어 이름
    val description: String,    // 레이어 설명
    val layerDepth: Int,       // 실행 순서 (0→1→2...)
    val functions: List<String> // 사용 가능한 함수
)
```

## 사용 예시

### 레이어 구현

```kotlin
class FileProcessorLayer : CommonLayerInterface {
    override suspend fun describe() = LayerDescription(
        name = "file-processor",
        description = "파일 읽기, 쓰기, 삭제 기능",
        layerDepth = 1,
        functions = listOf("create_file", "read_file", "delete_file")
    )
    
    override suspend fun execute(function: String, args: Map<String, Any>): String {
        return when (function) {
            "create_file" -> {
                val path = args["path"] as String
                File(path).writeText(args["content"] as? String ?: "")
                "File created: $path"
            }
            // ... 다른 함수들
        }
    }
}
```

### 원격 레이어 사용

```kotlin
// 다른 서버에 있는 레이어 연결
val remoteLayer = RemoteLayer("http://file-server:8081", httpClient)
orchestrator.registerLayer(remoteLayer)

// 사용은 로컬 레이어와 동일
val result = remoteLayer.execute("read_file", mapOf("path" to "config.json"))
```

## 빌드 및 실행

### 사전 요구사항
- Java 17+
- Kotlin 1.9+
- Docker (선택사항)

### 빌드
```bash
./gradlew build
```

### 실행
```bash
./gradlew run
```

### API 테스트
```bash
# 서버 실행 후
curl http://localhost:8080/health

# 응답: "Hana Orchestrator is running"
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
│   │   ├── FileProcessorLayer.kt   # 파일 처리 레이어
│   │   └── LayerFactory.kt        # 레이어 생성 도구
│   ├── orchestrator/               # 오케스트레이터 구현
│   └── Application.kt             # 메인 애플리케이션
├── docker/                        # Docker 설정
├── docs/                         # 문서
└── build.gradle.kts              # 빌드 설정
```

## 확장성

### 새로운 레이어 추가
1. `CommonLayerInterface` 구현
2. `describe()`와 `execute()` 메소드 구현
3. 오케스트레이터에 등록

### MCP 호환성
미래에 Model Context Protocol(MCP) 호환성을 위한 확장점이 준비되어 있습니다.

### 플러그인 시스템
동적 레이어 로딩을 위한 플러그인 아키텍처 지원 계획

## 기술 스택

- **Kotlin**: 메인 언어
- **Ktor**: 웹 프레임워크 및 HTTP 클라이언트
- **Kotlinx Serialization**: JSON 직렬화
- **Gradle**: 빌드 도구
- **Docker**: 컨테이너화

## 라이선스

MIT License

## 기여

환영합니다! Issue나 Pull Request를 통해 기여해주세요.