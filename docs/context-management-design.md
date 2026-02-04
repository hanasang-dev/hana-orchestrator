# 컨텍스트 관리 설계 (확장성 포함)

## 1. 개념 모델

프롬프트 구성은 다음 세 가지를 조합한다.

```
최종 프롬프트 = [영구 컨텍스트] + [휘발성 컨텍스트] + [본문(태스크별 프롬프트)]
```

- **영구 컨텍스트 (Persistent)**  
  앱 동작 중 **특정 조건**에서만 갱신된다. 예: 프로젝트 규칙(projectRoot 변경 시 로드), 기본 시스템 규칙, 레이어 메타정보 요약.  
  조건부 갱신이므로 “항상 최신”이 아니라 “조건 만족 시 스냅샷 갱신”이다.

- **휘발성 컨텍스트 (Volatile)**  
  **앱 기동 시 초기값 설정** 가능하고, **요청·API 호출 등으로 수정**된다. 예: 현재 작업 디렉터리, 현재 파일, 선택 영역.  
  요청 단위로 덮어쓰거나, 클라이언트가 매 요청에 실어 보내면 그때마다 반영.

- **본문 (Prompt body)**  
  LLM 태스크별로 이미 쓰고 있는 실제 프롬프트(요청 문장, 레이어 설명, JSON 규칙 등).  
  기존 `LLMPromptBuilder`가 만드는 내용.

목적별 확장을 위해 **스코프(단위)** 를 두고, 각 스코프에서 “영구/휘발” 의미를 나눌 수 있게 한다.  
우선 **앱 단위**만 영구/휘발을 정의하고, 작업 트리·LLM 태스크·레이어 단위는 추후 같은 추상으로 확장한다.

---

## 2. 스코프(단위) 추상 — 목적별 확장

컨텍스트를 **어디에 쓰는지**에 따라 스코프를 나눈다. 새 목적이 생기면 스코프와 저장소만 추가하면 된다.

| 스코프 | 생명주기 | 영구/휘발 (현재 정의) | 비고 |
|--------|----------|------------------------|------|
| **App** | 앱 프로세스 전체 | 영구(조건부 갱신) + 휘발(기동 시/요청 시 설정·수정) | 당장 구현 대상 |
| **ExecutionTree** | 한 오케스트레이션 실행 | (추후) 실행 시작 시 세팅, 실행 끝까지 유지 | 요청 메시지, 실행 ID 등 |
| **LLMTask** | 단일 LLM 호출 | (추후) 호출 시점에 App·ExecutionTree 등에서 조합 | createExecutionTree, evaluateResult 등 |
| **Layer** | 레이어/노드 단위 | (추후) describe() 결과, 노드 args | 이미 레이어 설명으로 일부 존재 |

**추상화 원칙:**  
“컨텍스트를 **어떤 스코프**에서, **영구인지 휘발인지**로 구분해 저장하고, **어떤 LLM 태스크**에서 **어떤 스코프들을** 조합해 쓸지”를 인터페이스로 분리한다.  
그래서 나중에 ExecutionTree 스코프를 넣을 때도, “ExecutionTree용 저장소 + 영구/휘발 정책”만 추가하면 된다.

**영구 저장소와 파일:**  
- 한 ContextStore(영구) = 한 파일로 둔다.  
- **지금:** App 스코프 영구 1개 → `persistent-context.json` 한 개만 유지해도 됨.  
- **나중에 목적이 늘면:** 스코프·용도별로 저장소를 나누고, **파일도 나눈다.**  
  - 예: App 영구 → `persistent-context.json`, 실행 요약 캐시 → `execution-summary-cache.json` 등.  
- 한 JSON에 다 넣기보다 **목적별로 ContextStore(및 파일)를 추가**하는 방식이 맞다.

---

## 3. 앱 단위: 영구 vs 휘발

### 3.1 영구 컨텍스트 (App-scoped, Persistent)

- **역할:** “거의 안 바뀌거나, 특정 조건에서만 갱신되는” 앱 전역 정보.
- **갱신 시점 예:**  
  - `projectRoot`가 (휘발성에) 설정되거나 변경될 때 → 프로젝트 규칙(.cursor/rules 등) 로드 후 영구 저장소에 반영.  
  - 앱 기동 시 한 번 로드(예: 기본 규칙 파일).  
  - 주기적 갱신(필요 시), 또는 “최초 1회 로드 후 캐시” 등.
- **저장:** 앱 전역에서 하나의 “영구 스냅샷”으로 유지.  
  - 구조: 키–값 맵 또는 순서 보장 리스트.  
  - 예: `projectRules`, `defaultSystemRules`, `layerSummary`(선택) 등.

### 3.2 휘발성 컨텍스트 (App-scoped, Volatile)

- **역할:** “요청·세션·사용자 동작에 따라 자주 바뀌는” 앱 전역 정보.
- **설정 시점:**  
  - **앱 기동 시** 초기값(예: 작업 디렉터리 = process cwd).  
  - **요청마다** 클라이언트가 `POST /chat` 등에 실어 보낸 값으로 덮어쓰기(예: currentFile, selection).  
  - 또는 **별도 API**로 “지금부터 현재 파일은 X”처럼 업데이트.
- **저장:** 앱 전역에서 하나의 “휘발성 맵”으로 유지.  
  - 예: `workingDirectory`, `currentFile`, `selection`, `projectRoot`(클라이언트가 보낼 수 있음) 등.  
  - 요청 처리 시 “이번 요청에 실린 context만 쓰기” vs “앱 전역 휘발성 + 요청 context 병합” 정책을 선택 가능.

### 3.3 개념적 흐름

```
[앱 기동]
  → 휘발성 초기값 설정 (예: workingDirectory = user.dir)
  → (선택) 영구 컨텍스트 초기 로드 (기본 규칙 등)

[요청 수신 시]
  → 요청에 context가 있으면 휘발성 업데이트 (currentFile, selection 등)
  → projectRoot가 바뀌었으면 영구 컨텍스트 조건부 갱신 (projectRules 로드 등)

[LLM 호출 시 (예: createExecutionTree)]
  → 영구 스냅샷 취득
  → 휘발성 스냅샷 취득 (또는 “이번 요청만” context)
  → 본문 생성 (기존 PromptBuilder)
  → 최종 프롬프트 = 영구 블록 + 휘발성 블록 + 본문
```

---

## 4. 추상화 설계 (확장 가능)

### 4.1 공통 타입

- **ContextEntry**  
  키–값 한 쌍. 순서 보장이 필요하면 `List<ContextEntry>`, 아니면 `Map<String, String>`로 직렬화에 사용.  
  **현재 구현:** `ContextSnapshot = Map<String, String>`만 사용. ContextEntry/List는 미사용.

```kotlin
// 개념
data class ContextEntry(val key: String, val value: String)
typealias ContextSnapshot = Map<String, String>  // 또는 List<ContextEntry>
```

- **ContextScope**  
  목적(단위) 식별. 추후 ExecutionTree, LLMTask, Layer 추가 가능.

```kotlin
enum class ContextScope { App /* , ExecutionTree, LLMTask, Layer */ }
```

- **PersistenceKind**  
  같은 스코프 안에서 “영구 vs 휘발” 구분.

```kotlin
enum class PersistenceKind { Persistent, Volatile }
```

### 4.2 저장소 추상 (스코프·영구/휘발별)

“특정 스코프 + 영구/휘발” 조합마다 읽기/쓰기 인터페이스를 둔다.  
앱 단위는 `App + Persistent`, `App + Volatile` 두 개 구현하면 된다.

```kotlin
/**
 * 특정 스코프·영구성의 컨텍스트 저장소.
 * 확장 시: ExecutionTree + Volatile 등 새 조합 추가.
 */
interface ContextStore {
    fun getScope(): ContextScope
    fun getPersistenceKind(): PersistenceKind
    
    /** 현재 스냅샷 (읽기 전용). */
    fun snapshot(): ContextSnapshot
    
    /** 키 하나 갱신. (Volatile에서 자주 씀) */
    fun put(key: String, value: String)
    
    /** 여러 키 한 번에. */
    fun putAll(entries: Map<String, String>)
    
    /** 조건부 갱신 시 사용. Persistent에서 "projectRoot 바뀌었을 때" 등. */
    fun clear()
}
```

- **Persistent**  
  `put`/`putAll`은 “조건이 만족될 때”만 호출(예: 규칙 로더가 projectRoot 기준으로 로드 후 `putAll`).  
  `clear()`는 “다시 로드해야 할 때” 등.
- **Volatile**  
  기동 시 초기값 `putAll`, 요청/API로 `put`·`putAll` 호출.

### 4.3 앱 전역 컨텍스트 서비스 (진입점)

앱 단위 두 저장소를 들고, “프롬프트용 스냅샷 조합”과 “휘발성/영구 갱신”을 제공한다.

```kotlin
/**
 * 앱 단위 컨텍스트: 영구 + 휘발성.
 * 추후 ExecutionTree 등 다른 스코프가 생기면 이 서비스가 그 스코프 저장소도 보유하거나,
 * Scope별로 ContextRegistry 같은 걸 두고 "App"만 여기서 다룰 수 있음.
 */
interface AppContextService {
    fun getPersistentStore(): ContextStore   // scope=App, kind=Persistent
    fun getVolatileStore(): ContextStore    // scope=App, kind=Volatile
    
    /** 프롬프트 조합용: 영구 + 휘발 스냅샷. (본문은 호출자가 별도 생성) */
    fun getAppContextForPrompt(): Pair<ContextSnapshot, ContextSnapshot>
    
    /** 요청에 실린 context로 휘발성 덮어쓰기. (요청 처리 진입점에서 호출) */
    fun updateVolatileFromRequest(context: Map<String, String>)
    
    /** projectRoot 등이 바뀌었을 때 영구 컨텍스트 조건부 갱신. */
    fun refreshPersistentIfNeeded(trigger: PersistentRefreshTrigger)
}
```

- `getAppContextForPrompt()`  
  - 첫 번째: 영구 스냅샷.  
  - 두 번째: 휘발성 스냅샷.  
  - 호출부(LLMPromptBuilder 또는 새 “프롬프트 조합기”)에서 “영구 블록 + 휘발 블록 + 본문”으로 합친다.
- `updateVolatileFromRequest(context)`  
  - `POST /chat` 등에서 `request.context`를 넘기면, 휘발성 저장소에 `putAll`로 반영.
- `refreshPersistentIfNeeded(trigger)`  
  - 예: `trigger.projectRoot`가 이전과 다르면 `.cursor/rules` 등을 읽어서 `getPersistentStore().putAll(...)` 호출.

`PersistentRefreshTrigger`는 “영구 컨텍스트를 언제 갱신할지” 조건을 넘기는 용도다.

```kotlin
data class PersistentRefreshTrigger(val projectRoot: String?)
// 필요 시: lastRefreshedRoot, forceRefresh 등 확장
```

### 4.4 프롬프트 조합 (영구 + 휘발 + 본문)

LLM 태스크별로 “어떤 스코프를 쓸지”와 “본문”만 다르게 주면 된다.  
지금은 App 스코프만 쓰므로:

```kotlin
/**
 * 영구 + 휘발 + 본문을 합쳐 최종 프롬프트 문자열 생성.
 * taskType은 추후 태스크별 블록 선택(예: 평가 시 다른 조합)에 사용. 현재는 CREATE_TREE만 사용.
 */
interface PromptComposer {
    fun compose(
        taskType: LLMTaskType,
        appContext: AppContextService,
        body: String
    ): String
}
```

`compose` 내부 동작 (현재):

1. `appContext.getAppContextForPrompt()` 로 `(persistent, volatile)` 취득.
2. `persistent`가 비어 있지 않으면 `## 영구 컨텍스트\n` + 직렬화(키: 값 줄마다).
3. `volatile`가 비어 있지 않으면 `## 휘발성 컨텍스트\n` + 직렬화.
4. `body` 붙임.

직렬화 형식은 팀 규칙에 맞게(예: `key: value` 한 줄씩, 값에 줄바꿈 있으면 이스케이프 또는 블록 구분).

---

## 5. 당장 사용처 (1단계)

- **휘발성:**  
  - 앱 기동 시: `workingDirectory` = `System.getProperty("user.dir")` (또는 설정값).  
  - 요청 시: 클라이언트가 보낸 `context`로 `currentFile`, `selection`, `projectRoot` 등 업데이트.
- **영구:**  
  - `projectRoot`가 휘발성에 있으면, 해당 경로에서 `.cursor/rules`(또는 AGENTS.md) 읽어서 `projectRules` 키로 영구 저장.  
  - 갱신 조건: `refreshPersistentIfNeeded`를 “요청 처리 시 projectRoot가 이전과 다를 때” 호출.
- **본문:**  
  - 기존 `LLMPromptBuilder.buildExecutionTreePrompt(...)` 등 그대로 사용.  
  - 다만 `createExecutionTree` 호출 시, 그 앞에 `PromptComposer.compose(CREATE_TREE, appContext, body)` 로 **영구 + 휘발 + 본문** 합친 문자열을 LLM에 넘긴다.

이렇게 하면 “현재 위치한 디렉, 파일 등은 휘발성”, “프로젝트 규칙은 영구(조건부 갱신)”, “실제 프롬프트 본문은 기존대로”라는 개념적 접근이 코드에 그대로 반영된다.

---

## 5.1 주의: 경로 해석과 currentFile/selection 출처

**경로(projectRoot, currentFile 등)를 꼭 알려줘야 하나? 절대 경로가 필요한가?**

- **상대 경로**(`.`, `src/App.kt` 등)는 **서버 프로세스의 작업 디렉터리(앱 기동 시 user.dir)** 기준으로 해석된다. FileSystemLayer, BuildLayer, ProjectRulesLoader 모두 서버가 떠 있는 그 디렉터리 기준.
- 서버와 클라이언트가 **같은 프로젝트/같은 cwd**라면 상대 경로만 넘겨도 동작함.
- **원격 클라이언트**이거나 **서버가 다른 프로젝트에서 돌아가는 경우**에는, 서버가 어느 프로젝트를 볼지 알 수 있게 **projectRoot를 절대 경로로 넘기는 것**을 권장. (또는 서버를 해당 프로젝트 루트에서 기동.)

**currentFile / selection은 앱 기동 시 없는데 왜 프롬프트에 나올 수 있나?**

- **workingDirectory·projectRoot:** 오케스트레이터가 **요청 처리 시마다** 서버 프로세스의 현재 디렉터리(user.dir)를 휘발성에 넣음. 호출자가 실어보낼 필요 없음. (`ensureVolatileServerWorkingDirectory`) projectRoot는 클라이언트가 안 보내면 서버 cwd로 둠.
- **currentFile, selection:** 현재 구현에는 "현재 파일을 선택하는 UI"가 없음. **호출자**가 `context`에 넣어 줄 때만 존재(에디터 연동 시 등).

---

## 6. 확장 시나리오 (참고)

- **작업 트리 단위 컨텍스트**  
  - `ContextScope.ExecutionTree`, `ContextStore(ExecutionTree, Volatile)` 추가.  
  - 오케스트레이션 시작 시 `executionId`, `userMessage`, (선택) `requestContext` 스냅샷을 여기에 넣고, 해당 실행이 끝날 때까지 유지.  
  - `PromptComposer`가 `CREATE_TREE` 등에서 “App + ExecutionTree” 스냅샷을 같이 넣도록 확장.
- **LLM 태스크별 선택**  
  - `compose(taskType, ...)` 안에서 `taskType`에 따라 “영구만”, “영구+휘발”, “영구+휘발+ExecutionTree” 등 조합을 다르게 하면 된다.
- **레이어 단위**  
  - “트리 생성 시 레이어 설명”은 이미 `layerDescriptions`로 본문에 들어가므로, 컨텍스트 저장소로 빼고 싶을 때만 `ContextScope.Layer` 또는 “레이어 메타만 영구 컨텍스트에 요약 저장”等方式으로 확장 가능.

---

## 7. 요약

| 항목 | 내용 |
|------|------|
| 개념 | **영구(조건부 갱신) + 휘발(요청 시 서버 cwd 자동 + 클라이언트 context) + 본문** 조합으로 프롬프트 구성. |
| 확장성 | **ContextScope** + **PersistenceKind** + **ContextStore** 로 “목적별·영구/휘발” 추가 가능. |
| 앱 단위 | **AppContextService**가 Persistent + Volatile store 보유, `getAppContextForPrompt`, `updateVolatileFromRequest`, `ensureVolatileServerWorkingDirectory`, `refreshPersistentIfNeeded` 제공. |
| 당장 사용 | 휘발: workingDirectory·projectRoot(요청 시 서버 cwd 자동 설정), currentFile/selection(요청 시 클라이언트). 영구: projectRules(projectRoot 변경 시 로드). |
| 프롬프트 | **PromptComposer**가 영구 스냅샷 + 휘발 스냅샷 + 본문을 합쳐 LLM에 넘길 문자열 생성. |

이 설계대로 구현하면, 지금은 앱 전역의 영구/휘발만 쓰고, 나중에 작업 트리·LLM 태스크·레이어 단위 컨텍스트를 같은 추상 위에 올릴 수 있다.
