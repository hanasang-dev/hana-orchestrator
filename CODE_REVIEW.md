# 코드 리뷰: JSON Schema Builder 및 LLM Client 개선

## 변경사항 요약
1. **JsonSchemaBuilder.kt** - 새로 생성 (250줄)
2. **OllamaLLMClient.kt** - schema 파라미터 추가, 재시도 로직 개선
3. **LLMPromptBuilder.kt** - 경로 처리 규칙 추가, 예시 generic화

---

## 원칙별 검토

### ✅ KISS (Keep It Simple, Stupid)

**잘 된 점:**
- `JsonSchemaBuilder`는 단일 책임 (JSON Schema 생성만)
- 각 함수는 명확한 목적을 가짐
- 재시도 로직이 단순하고 이해하기 쉬움

**개선 필요:**
- ❌ **JsonSchemaBuilder의 중복 패턴**: 각 스키마 빌더 함수가 비슷한 구조를 반복
  ```kotlin
  // 반복되는 패턴:
  "type" to JsonPrimitive("object"),
  "required" to JsonArray(...),
  "properties" to JsonObject(...)
  ```

---

### ❌ DRY (Don't Repeat Yourself)

**문제점:**

1. **JsonSchemaBuilder의 중복 코드**
   - `buildResultEvaluationSchema()`, `buildComparisonResultSchema()`, `buildLLMDirectAnswerCapabilitySchema()`가 거의 동일한 패턴
   - 각 함수에서 `JsonObject(mapOf("type" to JsonPrimitive(...)))` 반복
   - `required` 필드 생성 로직 중복: `listOf(...).map { JsonPrimitive(it) }`

2. **프로퍼티 스키마 생성 중복**
   - boolean 타입 프로퍼티 생성 패턴이 여러 곳에서 반복
   - string 타입 프로퍼티 생성 패턴이 여러 곳에서 반복

**개선 제안:**
```kotlin
// 헬퍼 함수 추가
private fun createObjectSchema(
    required: List<String>,
    properties: Map<String, JsonObject>
): JsonObject

private fun createBooleanProperty(description: String): JsonObject
private fun createStringProperty(description: String): JsonObject
```

---

### ✅ YAGNI (You Aren't Gonna Need It)

**잘 된 점:**
- 현재 필요한 스키마만 구현됨
- 미래 확장을 위한 과도한 추상화 없음
- `schema` 파라미터는 현재 사용하지 않지만 향후 사용 예정 (TODO로 명시)

**주의사항:**
- `schema` 파라미터가 현재 사용되지 않지만, 구조화된 출력 구현을 위해 필요 (YAGNI 위반 아님)

---

### ✅ OOP (Object-Oriented Programming)

**잘 된 점:**
- **SRP (Single Responsibility Principle)**: 
  - `JsonSchemaBuilder`: 스키마 생성만 담당
  - `OllamaLLMClient`: LLM 통신만 담당
  - `LLMPromptBuilder`: 프롬프트 생성만 담당

- **캡슐화**: 
  - `buildExecutionNodeSchema`는 private으로 내부 구현 숨김
  - `JsonSchemaBuilder`는 `internal object`로 패키지 내부에서만 사용

- **DIP (Dependency Inversion Principle)**:
  - `LLMClient` 인터페이스를 통해 추상화
  - `JsonSchemaBuilder`는 프로바이더 독립적

**개선 가능:**
- `JsonSchemaBuilder`가 `object`로 되어 있어 테스트하기 어려움 (의존성 주입 불가)
  - 하지만 현재는 상태가 없으므로 `object`가 적절할 수 있음

---

## 구체적인 개선 제안

### 1. JsonSchemaBuilder DRY 개선

**현재 문제:**
```kotlin
// 중복되는 패턴이 여러 함수에 반복됨
fun buildResultEvaluationSchema(): JsonObject {
    return JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "required" to JsonArray(listOf(...).map { JsonPrimitive(it) }),
            "properties" to JsonObject(mapOf(...))
        )
    )
}
```

**개선안:**
```kotlin
internal object JsonSchemaBuilder {
    // 헬퍼 함수로 중복 제거
    private fun createObjectSchema(
        required: List<String>,
        properties: Map<String, JsonObject>
    ): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "required" to JsonArray(required.map { JsonPrimitive(it) }),
                "properties" to JsonObject(properties)
            )
        )
    }
    
    private fun createBooleanProperty(description: String): JsonObject {
        return JsonObject(mapOf(
            "type" to JsonPrimitive("boolean"),
            "description" to JsonPrimitive(description)
        ))
    }
    
    private fun createStringProperty(description: String): JsonObject {
        return JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive(description)
        ))
    }
    
    // 개선된 함수
    fun buildResultEvaluationSchema(): JsonObject {
        return createObjectSchema(
            required = listOf("isSatisfactory", "reason", "needsRetry"),
            properties = mapOf(
                "isSatisfactory" to createBooleanProperty("요구사항 충족 여부"),
                "reason" to createStringProperty("평가 이유"),
                "needsRetry" to createBooleanProperty("재처리 필요 여부")
            )
        )
    }
}
```

### 2. OllamaLLMClient의 재시도 로직

**현재 상태:** ✅ 적절함
- 재시도 로직이 명확하고 단순함
- 에러 정보를 프롬프트에 포함하여 개선

### 3. LLMPromptBuilder의 경로 처리

**현재 상태:** ✅ 적절함
- 경로 처리 규칙이 명확하게 추가됨
- 예시가 generic하게 변경되어 하드코딩 제거

---

## 우선순위별 개선 사항

### 🔴 High Priority (커밋 전 개선 권장)
1. **JsonSchemaBuilder DRY 개선** - 중복 코드 제거로 유지보수성 향상

### 🟡 Medium Priority (향후 개선)
1. `schema` 파라미터 실제 적용 (LLMParams.Schema.JSON 사용법 확인)
2. `JsonSchemaBuilder` 테스트 코드 추가

### 🟢 Low Priority (선택사항)
1. `JsonSchemaBuilder`를 클래스로 변경하여 의존성 주입 가능하게 (현재는 object로 충분)

---

## 결론

**전체 평가:**
- ✅ KISS: 대체로 단순하지만 JsonSchemaBuilder에 중복 있음
- ❌ DRY: JsonSchemaBuilder에 중복 코드 다수
- ✅ YAGNI: 불필요한 기능 없음
- ✅ OOP: 원칙 준수 양호

**권장사항:**
- 커밋 전에 JsonSchemaBuilder의 DRY 개선을 권장합니다.
- 하지만 현재 코드도 동작하므로, 우선 커밋하고 향후 리팩토링도 가능합니다.
