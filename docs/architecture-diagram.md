# Hana Orchestrator 아키텍처 다이어그램

## 전체 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Hana Orchestrator System                        │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐
│   HTTP Client   │  (사용자 또는 외부 시스템)
└────────┬────────┘
         │ POST /chat {"message": "안녕하세요", "context": {"currentFile": "...", ...} (선택)}
         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Application.kt (Ktor Server)                    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Routes:                                                         │   │
│  │  • POST /chat          → Orchestrator.execute()                 │   │
│  │  • GET  /layers        → Orchestrator.getAllLayerDescriptions() │   │
│  │  • GET  /health        → Health check                           │   │
│  │  • POST /layers/{name} → Direct layer execution                 │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Orchestrator                                    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Core Responsibilities:                                          │   │
│  │  • 레이어 관리 (registerLayer, getAllLayerDescriptions)          │   │
│  │  • 실행 계획 생성 및 실행                                        │   │
│  │  • 트리 구조 실행 관리                                            │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────┐      ┌──────────────────┐                      │
│  │  Layer Registry   │      │  LLM Client       │                      │
│  │  ┌──────────────┐ │      │  ┌──────────────┐ │                      │
│  │  │ EchoLayer    │ │      │  │ OllamaLLM   │ │                      │
│  │  │ RemoteLayer  │ │      │  │ Client      │ │                      │
│  │  │ ...          │ │      │  └──────────────┘ │                      │
│  │  └──────────────┘ │      └──────────────────┘                      │
│  └──────────────────┘                                                 │
└───────────────────────────────┬───────────────────────────────────────┘
                                 │
                                 ▼
                    ┌────────────────────────┐
                    │  Execution Flow        │
                    └────────────────────────┘
```

## 실행 흐름 상세 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    사용자 요청 처리 흐름 (POST /chat)                    │
└─────────────────────────────────────────────────────────────────────────┘

[1] HTTP Request
    │
    │ POST /chat {"message": "안녕하세요", "context": {...} (선택)}
    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Application.kt                                                          │
│   • ChatRequest 수신                                                    │
│   • orchestrator.execute("execute", {query: "안녕하세요"}) 호출          │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Orchestrator.execute("execute", ...)                                    │
│                                                                          │
│  [2] 레이어 정보 수집                                                    │
│      getAllLayerDescriptions()                                          │
│      → [LayerDescription(name="echo-layer", functions=["echo", ...])]   │
│                                                                          │
│  [3] LLM에게 트리 생성 요청                                              │
│      ┌────────────────────────────────────────────────────────────┐   │
│      │ OllamaLLMClient.createExecutionTree(..., appContextService) │   │
│      │                                                              │   │
│      │  • 프롬프트 생성 (PromptComposer):                           │   │
│      │    [영구 컨텍스트] (projectRules 등) + [휘발성 컨텍스트]      │   │
│      │    (workingDirectory, projectRoot, currentFile 등) + 본문    │   │
│      │    "사용자 요청: 안녕하세요" / "사용 가능한 레이어: ..."     │   │
│      │                                                              │   │
│      │  • LLM 호출 (Ollama qwen3:8b)                               │   │
│      │    타임아웃: 30초                                            │   │
│      │                                                              │   │
│      │  • JSON 응답 파싱:                                          │   │
│      │    {                                                         │   │
│      │      "rootNode": {                                          │   │
│      │        "layerName": "echo-layer",                          │   │
│      │        "function": "echo",                                  │   │
│      │        "args": {"query": "안녕하세요"},                    │   │
│      │        "children": []                                      │   │
│      │      }                                                       │   │
│      │    }                                                         │   │
│      └────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  [4] 트리 검증 및 수정                                                  │
│      ┌────────────────────────────────────────────────────────────┐   │
│      │ ExecutionTreeValidator.validateAndFix(rawTree, query)      │   │
│      │                                                              │   │
│      │  검증 항목:                                                 │   │
│      │  ✓ 트리 깊이 (최대 10단계)                                  │   │
│      │  ✓ 순환 참조 감지                                           │   │
│      │  ✓ 레이어명 존재 여부                                       │   │
│      │  ✓ 함수명 존재 여부                                         │   │
│      │  ✓ 필수 인자 ("query") 추가                                 │   │
│      │                                                              │   │
│      │  자동 수정:                                                 │   │
│      │  • 존재하지 않는 레이어 → 유사 레이어로 교체                │   │
│      │  • 존재하지 않는 함수 → 기본 함수로 교체                    │   │
│      │  • 누락된 인자 → 자동 추가                                  │   │
│      │                                                              │   │
│      │  결과: ValidationResult                                     │   │
│      │    • isValid: true/false                                    │   │
│      │    • warnings: ["레이어명 자동 수정됨", ...]               │   │
│      │    • fixedTree: ExecutionTree                               │   │
│      └────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  [5] 트리 실행                                                          │
│      ┌────────────────────────────────────────────────────────────┐   │
│      │ Orchestrator.executeTree(finalTree)                         │   │
│      │                                                              │   │
│      │  executeNode(rootNode, depth=0, visitedLayers={})          │   │
│      │    │                                                         │   │
│      │    ├─ [5-1] 깊이 검증 (depth > 10? → 에러)                 │   │
│      │    ├─ [5-2] 순환 참조 검증                                  │   │
│      │    ├─ [5-3] 현재 노드 실행                                  │   │
│      │    │   executeOnLayer("echo-layer", "echo", args)          │   │
│      │    │     │                                                    │   │
│      │    │     ▼                                                    │   │
│      │    │   ┌──────────────────────────────────────────────┐     │   │
│      │    │   │ EchoLayer.execute("echo", args)              │     │   │
│      │    │   │   • args["query"] 또는 args["message"] 추출 │     │   │
│      │    │   │   • "Echo: 안녕하세요" 반환                   │     │   │
│      │    │   └──────────────────────────────────────────────┘     │   │
│      │    │                                                         │   │
│      │    ├─ [5-4] 자식 노드 확인                                  │   │
│      │    │   children.isEmpty()? → 현재 결과만 반환             │   │
│      │    │                                                         │   │
│      │    └─ [5-5] 결과 반환                                        │   │
│      │        "Echo: 안녕하세요"                                    │   │
│      └────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  [6] 결과 반환                                                          │
│      → "Echo: 안녕하세요"                                               │
└───────────────────────────────┬─────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Application.kt                                                          │
│   • ChatResponse(results=["Echo: 안녕하세요"]) 생성                    │
│   • HTTP 200 응답                                                       │
└─────────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
                    [7] HTTP Response
                    {"response": ["Echo: 안녕하세요"]}
```

## 트리 구조 실행 예시

### 단순 트리 (현재 구현)
```
ExecutionTree
└── rootNode: ExecutionNode
    ├── layerName: "echo-layer"
    ├── function: "echo"
    ├── args: {"query": "안녕하세요"}
    ├── parallel: false
    └── children: []
```

### 복잡한 트리 (미래 확장 가능)
```
ExecutionTree
└── rootNode: ExecutionNode
    ├── layerName: "analyzer-layer"
    ├── function: "analyze"
    ├── args: {"query": "파일을 분석해줘"}
    ├── parallel: false
    └── children: [
        │
        ├── ExecutionNode
        │   ├── layerName: "file-layer"
        │   ├── function: "read"
        │   ├── args: {"path": "..."}
        │   ├── parallel: false
        │   └── children: []
        │
        └── ExecutionNode
            ├── layerName: "processor-layer"
            ├── function: "process"
            ├── args: {"data": "previousResult"}
            ├── parallel: false
            └── children: [
                └── ExecutionNode
                    ├── layerName: "output-layer"
                    ├── function: "save"
                    ├── args: {"result": "parentResult"}
                    └── children: []
            ]
    ]
```

## 병렬 실행 예시

```
ExecutionTree
└── rootNode: ExecutionNode
    ├── layerName: "orchestrator"
    ├── function: "execute"
    ├── parallel: true  ← 병렬 실행 플래그
    └── children: [
        │
        ├── ExecutionNode (async 실행)
        │   ├── layerName: "layer-a"
        │   └── function: "process-a"
        │
        ├── ExecutionNode (async 실행)
        │   ├── layerName: "layer-b"
        │   └── function: "process-b"
        │
        └── ExecutionNode (async 실행)
            ├── layerName: "layer-c"
            └── function: "process-c"
    ]

실행 순서:
1. rootNode 실행
2. children[0], children[1], children[2] 병렬 실행 (coroutineScope + async)
3. 모든 결과 수집 후 반환
```

## 순차 실행 예시

```
ExecutionTree
└── rootNode: ExecutionNode
    ├── layerName: "layer-1"
    ├── function: "step1"
    ├── parallel: false  ← 순차 실행
    └── children: [
        │
        ├── ExecutionNode
        │   ├── layerName: "layer-2"
        │   ├── function: "step2"
        │   ├── args: {
        │   │     "query": "...",
        │   │     "previousResult": "layer-1 결과",  ← 자동 추가
        │   │     "parentResult": "layer-1 결과"     ← 자동 추가
        │   │   }
        │   └── children: []
        │
        └── ExecutionNode
            ├── layerName: "layer-3"
            ├── function: "step3"
            ├── args: {
            │     "query": "...",
            │     "previousResult": "layer-2 결과",  ← 이전 노드 결과
            │     "parentResult": "layer-1 결과"
            │   }
            └── children: []
    ]

실행 순서:
1. layer-1 실행 → 결과1
2. layer-2 실행 (previousResult=결과1) → 결과2
3. layer-3 실행 (previousResult=결과2) → 결과3
4. 최종: 결과1 + 결과2 + 결과3
```

## 주요 컴포넌트 상세

### 1. Orchestrator
```
┌─────────────────────────────────────────────────────────┐
│                    Orchestrator                          │
├─────────────────────────────────────────────────────────┤
│ Responsibilities:                                        │
│  • 레이어 등록 및 관리                                   │
│  • 실행 계획 생성 (LLM 연동)                             │
│  • 트리 구조 실행                                       │
│  • 에러 처리 및 타임아웃 관리                            │
│                                                          │
│ Key Methods:                                            │
│  • registerLayer(layer)                                 │
│  • getAllLayerDescriptions()                            │
│  • execute(function, args)                              │
│  • executeTree(tree)                                    │
│  • executeNode(node, depth, visited)                    │
└─────────────────────────────────────────────────────────┘
```

### 2. ExecutionTreeValidator
```
┌─────────────────────────────────────────────────────────┐
│              ExecutionTreeValidator                     │
├─────────────────────────────────────────────────────────┤
│ Responsibilities:                                        │
│  • 트리 구조 유효성 검증                                 │
│  • 자동 수정 및 폴백 처리                                │
│                                                          │
│ Validation Checks:                                       │
│  ✓ 트리 깊이 (MAX_DEPTH = 10)                          │
│  ✓ 순환 참조 감지                                       │
│  ✓ 레이어명 존재 여부                                   │
│  ✓ 함수명 존재 여부                                     │
│  ✓ 필수 인자 확인                                       │
│                                                          │
│ Auto-Fix Mechanisms:                                   │
│  • 유사 레이어 자동 매칭                                │
│  • 기본 함수로 자동 교체                                │
│  • 누락된 인자 자동 추가                                 │
└─────────────────────────────────────────────────────────┘
```

### 3. OllamaLLMClient
```
┌─────────────────────────────────────────────────────────┐
│                OllamaLLMClient                         │
├─────────────────────────────────────────────────────────┤
│ Responsibilities:                                        │
│  • LLM과 통신하여 실행 계획 생성                         │
│  • JSON 응답 파싱                                        │
│                                                          │
│ Process:                                                 │
│  1. 레이어 정보를 프롬프트로 변환                        │
│  2. LLM에게 트리 구조 생성 요청                          │
│  3. JSON 응답 추출 (마크다운 코드 블록 처리)             │
│  4. ExecutionTreeResponse로 파싱                        │
│  5. ExecutionTree로 변환                                │
│                                                          │
│ Error Handling:                                         │
│  • 타임아웃: 30초                                        │
│  • 파싱 실패 시 폴백 트리 생성                           │
└─────────────────────────────────────────────────────────┘
```

### 3.5 컨텍스트 및 프롬프트 조합
```
┌─────────────────────────────────────────────────────────┐
│  AppContextService / PromptComposer                     │
├─────────────────────────────────────────────────────────┤
│  • 요청 시: chatDto.context → 휘발성 갱신                 │
│  • ensureVolatileServerWorkingDirectory: workingDirectory│
│    projectRoot(미지정 시) = 서버 user.dir                │
│  • refreshPersistentIfNeeded(projectRoot): .cursor/rules │
│    또는 AGENTS.md → projectRules (영구)                  │
│  • PromptComposer: [영구] + [휘발] + 본문 → 최종 프롬프트│
└─────────────────────────────────────────────────────────┘
```

### 4. CommonLayerInterface
```
┌─────────────────────────────────────────────────────────┐
│           CommonLayerInterface                          │
├─────────────────────────────────────────────────────────┤
│ Interface:                                               │
│  suspend fun describe(): LayerDescription               │
│  suspend fun execute(function: String, args: Map): String│
│                                                          │
│ Implementations:                                        │
│  • EchoLayer: 로컬 레이어                                │
│  • RemoteLayer: 원격 레이어 (HTTP)                      │
│  • Orchestrator: 자기 자신도 레이어로 동작              │
│                                                          │
│ LayerDescription:                                       │
│  • name: 레이어 고유 이름                                │
│  • description: 레이어 설명                             │
│  • functions: 사용 가능한 함수 목록                      │
└─────────────────────────────────────────────────────────┘
```

## 데이터 흐름

```
User Query: "안녕하세요"
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 1. HTTP Request                                         │
│    POST /chat                                            │
│    Body: {"message": "안녕하세요", "context": {...} (선택)}             │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 2. Orchestrator.execute("execute", {query: "안녕하세요"})│
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 3. LLM 트리 생성                                        │
│    Input:                                                │
│      • query: "안녕하세요"                               │
│      • layers: [LayerDescription(...)]                   │
│      • appContext: 영구+휘발 스냅샷 (PromptComposer로 본문과 결합)     │
│    Output:                                               │
│      ExecutionTree {                                     │
│        rootNode: {                                       │
│          layerName: "echo-layer",                       │
│          function: "echo",                               │
│          args: {"query": "안녕하세요"}                  │
│        }                                                 │
│      }                                                   │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 4. 트리 검증 및 수정                                    │
│    Input: rawTree                                        │
│    Process:                                              │
│      • 깊이 검증                                         │
│      • 순환 참조 검증                                    │
│      • 레이어/함수명 검증                                │
│      • 인자 자동 추가                                    │
│    Output: fixedTree                                     │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 5. 트리 실행                                            │
│    executeNode(rootNode)                                │
│      → executeOnLayer("echo-layer", "echo", args)       │
│        → EchoLayer.execute("echo", args)                 │
│          → "Echo: 안녕하세요"                            │
└─────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ 6. HTTP Response                                        │
│    {"response": ["Echo: 안녕하세요"]}                   │
└─────────────────────────────────────────────────────────┘
```

## 보안 및 안정성 메커니즘

```
┌─────────────────────────────────────────────────────────┐
│              보안 및 안정성 계층                        │
├─────────────────────────────────────────────────────────┤
│                                                          │
│ 1. 트리 깊이 제한                                       │
│    • 최대 10단계 (MAX_TREE_DEPTH)                       │
│    • 무한 재귀 방지                                      │
│                                                          │
│ 2. 순환 참조 방지                                       │
│    • visitedLayers로 경로 추적                          │
│    • 같은 레이어:함수 조합 반복 실행 차단                │
│                                                          │
│ 3. 타임아웃 관리                                         │
│    • LLM 호출: 30초                                      │
│    • 전체 실행: 60초                                     │
│    • 개별 노드: 10초                                     │
│                                                          │
│ 4. 에러 처리                                             │
│    • 부분 실패 시 계속 진행                              │
│    • 에러 메시지를 결과에 포함                           │
│    • 폴백 트리 자동 생성                                 │
│                                                          │
│ 5. 입력 검증                                             │
│    • 레이어명/함수명 존재 여부 확인                      │
│    • 필수 인자 자동 추가                                 │
│    • 유사 레이어 자동 매칭                               │
└─────────────────────────────────────────────────────────┘
```

## 확장성 포인트

```
┌─────────────────────────────────────────────────────────┐
│              확장 가능한 영역                            │
├─────────────────────────────────────────────────────────┤
│                                                          │
│ 1. 레이어 추가                                           │
│    • CommonLayerInterface 구현                           │
│    • registerLayer()로 등록                             │
│    • 자동으로 LLM이 인식                                 │
│                                                          │
│ 2. 실행 전략 확장                                        │
│    • 현재: 순차/병렬                                     │
│    • 확장: 조건부 실행, 반복 실행 등                      │
│                                                          │
│ 3. 결과 처리                                             │
│    • 현재: 문자열 결합                                  │
│    • 확장: 구조화된 결과 객체                            │
│                                                          │
│ 4. LLM 통합                                             │
│    • 현재: Ollama                                        │
│    • 확장: OpenAI, Claude 등                             │
│                                                          │
│ 5. 원격 레이어                                           │
│    • HTTP 기반 원격 레이어 지원                          │
│    • MCP 프로토콜 지원 (계획)                            │
└─────────────────────────────────────────────────────────┘
```
