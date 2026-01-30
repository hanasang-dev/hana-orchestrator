# Ollama 단일 인스턴스 다중 모델 아키텍처

## 개요

Hana Orchestrator는 **단일 Ollama 서버 인스턴스**를 통해 여러 모델을 효율적으로 병렬 실행합니다. 이 문서는 이러한 아키텍처가 성능 문제 없이 작동하는 이유와 작동 방식을 설명합니다.

## 아키텍처 구조

### 단일 서버, 다중 모델 러너

```
┌─────────────────────────────────────────────────────────────┐
│                    Ollama 서버 (단일 인스턴스)                │
│              /Applications/Ollama.app/ollama serve           │
│                      포트: 11434 (HTTP API)                  │
└───────────────────────┬─────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ Model Runner │ │ Model Runner │ │ Model Runner │
│ gemma2:2b   │ │ llama3.1:8b  │ │ llama3.1:8b  │
│ Port: 56099 │ │ Port: 55432  │ │ Port: 55432  │
│              │ │              │ │              │
│ 독립 프로세스 │ │ 독립 프로세스 │ │ 독립 프로세스 │
│ 독립 메모리  │ │ 독립 메모리  │ │ 독립 메모리  │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 핵심 개념

1. **단일 Ollama 서버**: 하나의 `ollama serve` 프로세스가 모든 모델 요청을 관리
2. **독립적인 모델 러너**: 각 모델마다 별도의 `ollama runner` 프로세스가 생성됨
3. **병렬 실행**: 각 러너는 독립적으로 실행되므로 여러 모델이 동시에 추론 가능

## 작동 방식

### 1. 모델 요청 처리 흐름

```
애플리케이션 (Hana Orchestrator)
    │
    │ HTTP POST /api/generate
    │ { "model": "gemma2:2b", "prompt": "..." }
    │
    ▼
Ollama 서버 (ollama serve)
    │
    │ 1. 요청 수신 및 파싱
    │ 2. 모델 러너 프로세스 확인/생성
    │ 3. 러너에게 추론 작업 위임
    │
    ▼
Model Runner (ollama runner --model gemma2:2b)
    │
    │ 1. 모델 파일 로드 (메모리에 없으면)
    │ 2. GPU/CPU에서 추론 실행
    │ 3. 결과 반환
    │
    ▼
Ollama 서버 → 애플리케이션
```

### 2. 모델 러너 프로세스 관리

- **온디맨드 생성**: 모델이 처음 요청될 때 러너 프로세스가 생성됨
- **독립 메모리 공간**: 각 러너는 독립적인 메모리 공간을 가짐
- **포트 분리**: 각 러너는 고유한 내부 포트를 사용 (예: 56099, 55432)
- **생명주기 관리**: Ollama 서버가 러너 프로세스의 생성/종료를 관리

### 3. 병렬 실행 예시

```kotlin
// 동시에 3개의 다른 모델 요청
coroutineScope {
    val simple = async { 
        ollamaClient.generate("gemma2:2b", "간단한 질문")
    }
    val medium = async { 
        ollamaClient.generate("llama3.1:8b", "중간 복잡도 질문")
    }
    val complex = async { 
        ollamaClient.generate("llama3.1:8b", "복잡한 질문")
    }
    
    // 모든 요청이 병렬로 실행됨
    val results = awaitAll(simple, medium, complex)
}
```

## 성능상 문제가 없는 이유

### 1. 프로세스 격리

- **독립 메모리**: 각 모델 러너는 독립적인 메모리 공간을 사용
- **CPU 스케줄링**: OS가 각 프로세스를 독립적으로 스케줄링
- **리소스 경합 최소화**: 메모리나 CPU 리소스가 격리되어 있음

### 2. GPU 활용 (Mac Apple Silicon)

- **Metal GPU 가속**: 각 러너가 Metal을 통해 GPU를 효율적으로 사용
- **병렬 처리**: GPU는 여러 추론 작업을 동시에 처리 가능
- **메모리 관리**: GPU 메모리가 충분하면 여러 모델을 동시에 로드 가능

### 3. 메모리 효율성

```
모델별 메모리 사용량 (예시):
- gemma2:2b: ~3.2GB (로드 시), ~50MB (대기 시)
- llama3.1:8b: ~6GB (로드 시), ~50MB (대기 시)

단일 Ollama 서버 사용 시:
- 서버 프로세스: ~250MB
- 러너 프로세스: 모델별로 독립적
- 총 메모리: 각 모델의 메모리 합계 + 서버 오버헤드
```

### 4. 네트워크 효율성

- **단일 엔드포인트**: 하나의 HTTP 서버로 모든 모델 관리
- **연결 재사용**: HTTP 연결 풀링으로 네트워크 오버헤드 최소화
- **로컬 통신**: localhost 통신으로 네트워크 지연 없음

### 5. 확장성

- **동적 모델 추가**: 새로운 모델을 런타임에 추가 가능
- **모델 교체**: 모델을 교체해도 서버 재시작 불필요
- **리소스 관리**: Ollama가 자동으로 리소스를 관리

## 실제 동작 확인

### 프로세스 확인

```bash
# Ollama 서버 프로세스
ps aux | grep "ollama serve"
# → /Applications/Ollama.app/Contents/Resources/ollama serve

# 모델 러너 프로세스들
ps aux | grep "ollama runner"
# → ollama runner --model gemma2:2b --port 56099
# → ollama runner --model llama3.1:8b --port 55432
```

### 병렬 실행 테스트

```bash
# 동시에 3개 모델 요청
curl -s http://localhost:11434/api/generate -d '{"model":"gemma2:2b","prompt":"안녕"}' &
curl -s http://localhost:11434/api/generate -d '{"model":"llama3.1:8b","prompt":"Hello"}' &
curl -s http://localhost:11434/api/generate -d '{"model":"llama3.1:8b","prompt":"こんにちは"}' &

# 모든 요청이 병렬로 처리됨
```

## Docker vs 로컬 Ollama 비교

### 이전 방식 (Docker Compose)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ Container 1 │  │ Container 2 │  │ Container 3 │
│ Ollama      │  │ Ollama      │  │ Ollama      │
│ Port 11435  │  │ Port 11436  │  │ Port 11437  │
│             │  │             │  │             │
│ 각각 독립   │  │ 각각 독립   │  │ 각각 독립   │
│ 서버 프로세스│ │ 서버 프로세스│ │ 서버 프로세스│
└─────────────┘  └─────────────┘  └─────────────┘
```

**문제점**:
- 각 컨테이너마다 Ollama 서버 프로세스 필요 (메모리 낭비)
- 포트 관리 복잡
- 컨테이너 오버헤드

### 현재 방식 (로컬 Ollama)

```
┌─────────────────────────────────────┐
│      단일 Ollama 서버               │
│      (로컬 설치)                    │
│      Port: 11434                   │
│                                     │
│  ┌──────────┐  ┌──────────┐      │
│  │ Runner 1 │  │ Runner 2 │      │
│  │ gemma2:2b│  │llama3.1:8b│     │
│  └──────────┘  └──────────┘      │
└─────────────────────────────────────┘
```

**장점**:
- 단일 서버 프로세스로 효율적
- GPU 직접 활용 가능 (Metal)
- 메모리 사용량 최적화
- 포트 관리 단순화

## 성능 최적화 팁

### 1. 모델 워밍업

```kotlin
// 애플리케이션 시작 시 모델을 미리 로드
suspend fun warmUpModels() {
    ollamaClient.generate("gemma2:2b", "warmup")
    ollamaClient.generate("llama3.1:8b", "warmup")
}
```

### 2. Keep-Alive 설정

```bash
# Ollama 환경변수로 모델을 메모리에 유지
export OLLAMA_KEEP_ALIVE=30m
```

### 3. 모델 선택 전략

- **작업 복잡도별 모델 분리**: SIMPLE/MEDIUM/COMPLEX 작업에 적합한 모델 사용
- **리소스 효율성**: 작은 모델로 가능한 작업은 작은 모델 사용

## 결론

단일 Ollama 서버로 여러 모델을 병렬 실행하는 것은:

1. ✅ **프로세스 격리**로 안전하게 작동
2. ✅ **독립적인 메모리 공간**으로 리소스 경합 최소화
3. ✅ **GPU 병렬 처리**로 성능 저하 없음
4. ✅ **효율적인 리소스 관리**로 메모리 사용량 최적화
5. ✅ **확장성**으로 새로운 모델 추가 용이

따라서 성능상 문제 없이 여러 모델을 동시에 사용할 수 있습니다.
