# CommonLayerInterface 최종 설계

## 핵심 아키텍처

### 1. 인터페이스 정의

```kotlin
/**
 * 오케스트레이션 시스템의 모든 레이어가 구현해야 할 기본 인터페이스
 * 
 * 설계 원칙:
 * - 자기기술: 각 레이어가 자신의 기능을 스스로 설명
 * - 분산 가능: 로컬/원격 레이어 모두 지원
 * - 단순함: 복잡한 타입 구분 없이 문자열 기반 통신
 */
interface CommonLayerInterface {
    /**
     * 레이어의 기능과 속성을 설명
     * 오케스트레이터가 이 정보로 레이어를 등록하고 관리
     */
    suspend fun describe(): LayerDescription
    
    /**
     * 실제 작업 실행
     * @param function 실행할 함수명
     * @param args 함수 파라미터
     * @return 작업 결과 (문자열)
     */
    suspend fun do(function: String, args: Map<String, Any> = emptyMap()): String
}

/**
 * 레이어의 전체 설명 정보
 */
data class LayerDescription(
    val name: String,                           // 레이어 고유 이름
    val description: String,                    // 레이어 목적 설명
    val layerDepth: Int,                       // Z-index 개념 (동기/비동기 실행)
    val functions: List<FunctionDefinition>,    // 사용 가능한 함수 목록
    val llmEndpoint: String? = null            // 레이어 전용 LLM (없으면 오케스트레이터 LLM 사용)
)

/**
 * 함수 상세 정의
 */
data class FunctionDefinition(
    val name: String,                           // 함수명
    val description: String,                    // 함수 설명
    val parameters: Map<String, ParameterSchema>,  // 파라미터 스키마
    val returnType: String                     // 반환 타입 시그니처 (예: "string", "file_path")
)

/**
 * 파라미터 스키마
 */
data class ParameterSchema(
    val type: String,                          // 파라미터 타입
    val description: String,                   // 파라미터 설명
    val required: Boolean = true,              // 필수 여부
    val defaultValue: Any? = null              // 기본값
)
```

### 2. 오케스트레이터의 역할

```kotlin
/**
 * 오케스트레이터: 레이어 관리와 NLP 처리
 */
class Orchestrator(
    private val llmService: LLMService,
    private val httpClient: HttpClient
) {
    private val layers = mutableMapOf<String, CommonLayerInterface>()
    
    /**
     * 레이어 등록 (로컬/원격)
     */
    suspend fun registerLayer(layer: CommonLayerInterface) {
        val description = layer.describe()
        layers[description.name] = layer
    }
    
    suspend fun registerRemoteLayer(endpoint: String) {
        val remoteLayer = RemoteLayer(endpoint, httpClient)
        registerLayer(remoteLayer)
    }
    
    /**
     * 사용자 요청 처리 (2단계 LLM 처리)
     */
    suspend fun process(userInput: String): String {
        // 1단계: 자연어 → 함수 호출
        val intent = llmService.analyzeIntent(userInput, getAllFunctions())
        val layer = layers[intent.layerName] 
            ?: throw LayerNotFoundException("Layer not found: ${intent.layerName}")
        
        // 2단계: 레이어 실행
        val result = layer.do(intent.function, intent.arguments)
        
        // 3단계: 결과 → 자연어 설명
        return llmService.explainResult(userInput, result)
    }
    
    /**
     * 레이어 실행 순서 결정 (layerDepth 기반)
     */
    private suspend fun executeLayers(layers: List<String>, input: Any): String {
        val sortedLayers = layers.mapNotNull { this.layers[it] }
            .sortedBy { it.describe().layerDepth }
            
        var result = input.toString()
        for (layer in sortedLayers) {
            result = layer.do("process", mapOf("input" to result))
        }
        return result
    }
}

/**
 * LLM 서비스 인터페이스
 */
interface LLMService {
    suspend fun analyzeIntent(userInput: String, functions: List<FunctionDefinition>): Intent
    suspend fun explainResult(originalInput: String, toolResult: String): String
}

/**
 * 의도 분석 결과
 */
data class Intent(
    val layerName: String,
    val function: String,
    val arguments: Map<String, Any>
)
```

### 3. 분산 통신 지원

```kotlin
/**
 * 원격 레이어 구현 (HTTP 기반)
 */
class RemoteLayer(
    private val baseUrl: String,
    private val httpClient: HttpClient
) : CommonLayerInterface {
    
    override suspend fun describe(): LayerDescription {
        return httpClient.get("$baseUrl/describe").body()
    }
    
    override suspend fun do(function: String, args: Map<String, Any>): String {
        val request = LayerRequest(function, args)
        val response = httpClient.post("$baseUrl/do") {
            setBody(request)
            contentType(ContentType.Application.Json)
        }.body<LayerResponse>()
        
        return response.result
    }
}

/**
 * HTTP 통신 데이터 모델
 */
data class LayerRequest(
    val function: String,
    val arguments: Map<String, Any>
)

data class LayerResponse(
    val success: Boolean,
    val result: String,
    val error: String? = null
)
```

### 4. 레이어 구현 예시

```kotlin
/**
 * 파일 처리 레이어 구현 예시
 */
class FileProcessorLayer : CommonLayerInterface {
    
    override suspend fun describe(): LayerDescription {
        return LayerDescription(
            name = "file-processor",
            description = "파일 읽기, 쓰기, 삭제 등 파일 조작 기능",
            layerDepth = 2,
            functions = listOf(
                FunctionDefinition(
                    name = "create_file",
                    description = "파일 생성",
                    parameters = mapOf(
                        "path" to ParameterSchema("string", "파일 경로"),
                        "content" to ParameterSchema("string", "파일 내용", false, "")
                    ),
                    returnType = "string"
                ),
                FunctionDefinition(
                    name = "read_file",
                    description = "파일 읽기", 
                    parameters = mapOf(
                        "path" to ParameterSchema("string", "파일 경로")
                    ),
                    returnType = "string"
                ),
                FunctionDefinition(
                    name = "delete_file",
                    description = "파일 삭제",
                    parameters = mapOf(
                        "path" to ParameterSchema("string", "파일 경로")
                    ),
                    returnType = "string"
                )
            )
        )
    }
    
    override suspend fun do(function: String, args: Map<String, Any>): String {
        return when (function) {
            "create_file" -> {
                val path = args["path"] as String
                val content = args["content"] as? String ?: ""
                File(path).writeText(content)
                "File created: $path"
            }
            "read_file" -> {
                val path = args["path"] as String
                File(path).readText()
            }
            "delete_file" -> {
                val path = args["path"] as String
                File(path).delete()
                "File deleted: $path"
            }
            else -> "Unknown function: $function"
        }
    }
}
```

## 설계의 핵심 특징

### 1. 자기기술 방식
- 각 레이어가 자신의 기능을 명확하게 설명
- 오케스트레이터가 자동으로 레이어 등록과 관리

### 2. 단순한 통신
- 모든 결과는 문자열로 처리
- 복잡한 타입 구분 없음
- LLM이 결과를 자연어로 변환

### 3. 분산 지원
- 로컬 레이어와 원격 레이어 동일하게 처리
- HTTP 기반 표준 통신 프로토콜

### 4. 실행 순서 제어
- layerDepth로 동기/비동기 실행 제어
- 복잡한 워크플로우 자동 관리

## 확장성 고려

### MCP 호환성 확장점
```kotlin
interface MCPCompatibleLayer : CommonLayerInterface {
    fun toMCPTools(): List<MCPTool>
    fun fromMCPRequest(request: MCPRequest): LayerRequest
    fun toMCPResponse(response: LayerResponse): MCPResponse
}
```

### 플러그인 시스템
```kotlin
interface LayerPlugin {
    fun createLayer(config: Map<String, Any>): CommonLayerInterface
    fun getLayerInfo(): PluginInfo
}
```

이 설계가 확장성과 단순함의 균형을 잘 맞췄다고 생각합니다.