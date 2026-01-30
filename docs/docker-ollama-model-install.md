# Docker 환경에서 Ollama 모델 설치하기

## 개요
이 프로젝트는 **모델이 미리 설치된 커스텀 이미지**를 사용합니다. 
`docker-compose.yml`에서 각 Ollama 서비스는 `Dockerfile.ollama-qwen3`를 통해 빌드되며, 
`qwen3:8b` 모델이 이미 포함되어 있습니다.

따라서 별도로 모델을 설치할 필요가 없습니다! 🎉

## 자동 설치 방식 (권장)

`docker-compose.yml`에서 이미 모델이 포함된 이미지를 사용하도록 설정되어 있습니다:

```yaml
ollama-simple:
  build:
    context: .
    dockerfile: Dockerfile.ollama-qwen3
```

이 Dockerfile은 `ollama/ollama:latest`를 기반으로 `qwen3:8b` 모델을 미리 설치합니다.

## 수동 설치 방법 (필요시)

## 포트 할당
- `ollama-simple`: 포트 11435
- `ollama-medium`: 포트 11436
- `ollama-complex`: 포트 11437

## 모델 설치 방법

### 1. 컨테이너 내부에서 직접 설치

각 Ollama 컨테이너에 접속하여 모델을 설치합니다:

```bash
# ollama-simple에 모델 설치
docker exec -it docker-ollama-simple-1 ollama pull qwen3:8b

# ollama-medium에 모델 설치
docker exec -it docker-ollama-medium-1 ollama pull qwen3:8b

# ollama-complex에 모델 설치
docker exec -it docker-ollama-complex-1 ollama pull qwen3:8b
```

### 2. HTTP API를 통한 설치

컨테이너 외부에서 HTTP API를 통해 모델을 설치할 수 있습니다:

```bash
# ollama-simple에 모델 설치
curl http://localhost:11435/api/pull -d '{"name": "qwen3:8b"}'

# ollama-medium에 모델 설치
curl http://localhost:11436/api/pull -d '{"name": "qwen3:8b"}'

# ollama-complex에 모델 설치
curl http://localhost:11437/api/pull -d '{"name": "qwen3:8b"}'
```

### 3. 설치된 모델 확인

```bash
# ollama-simple 모델 목록 확인
curl http://localhost:11435/api/tags

# ollama-medium 모델 목록 확인
curl http://localhost:11436/api/tags

# ollama-complex 모델 목록 확인
curl http://localhost:11437/api/tags
```

## 주의사항

1. **볼륨 분리**: 각 Ollama 인스턴스는 독립적인 볼륨을 사용하므로, 모델을 각각 설치해야 합니다.
2. **모델 크기**: 모델 크기에 따라 다운로드 시간이 오래 걸릴 수 있습니다.
3. **디스크 공간**: 각 모델은 약 4-8GB 정도의 공간을 사용할 수 있습니다.

## 예제: 모든 인스턴스에 qwen3:8b 설치

```bash
#!/bin/bash

# ollama-simple
echo "Installing qwen3:8b on ollama-simple..."
curl http://localhost:11435/api/pull -d '{"name": "qwen3:8b"}'

# ollama-medium
echo "Installing qwen3:8b on ollama-medium..."
curl http://localhost:11436/api/pull -d '{"name": "qwen3:8b"}'

# ollama-complex
echo "Installing qwen3:8b on ollama-complex..."
curl http://localhost:11437/api/pull -d '{"name": "qwen3:8b"}'

echo "All models installed!"
```

## 다른 모델 설치 예제

```bash
# llama2 설치
curl http://localhost:11435/api/pull -d '{"name": "llama2"}'

# mistral 설치
curl http://localhost:11436/api/pull -d '{"name": "mistral"}'

# codellama 설치
curl http://localhost:11437/api/pull -d '{"name": "codellama"}'
```
