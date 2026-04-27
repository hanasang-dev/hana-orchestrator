# LLM 패키지 - 개발 규칙

## LLMPromptBuilder 책임 원칙 (절대 위반 금지)

### 오케스트레이터는 레이어다
- `LLMPromptBuilder`는 오케스트레이터 자신의 구조적 책임만 담는다
- 오케스트레이터도 하나의 레이어로서, **자신의 작동 방식만 LLM에게 설명**한다
- 레이어의 사용법·조합 패턴을 오케스트레이터 프롬프트에 우겨넣는 것은 SRP 위반이다

### LLMPromptBuilder에 담아야 하는 것
- 트리 구조 규칙 (`rootNodes`, `children`, `parallel`)
- `{{parent}}` 동작 방식 (직접 부모 결과만 참조, 형제 불가)
- `execute_tree` / `finish` 판단 기준
- JSON 응답 포맷
- 중복 루프 방지 규칙

### LLMPromptBuilder에 담아서는 안 되는 것
- 특정 레이어명 언급 (예: `file-system`, `llm`, `git`)
- 특정 함수명 언급 (예: `readFile`, `analyze`, `writeFile`)
- 레이어 조합 패턴 (예: "readFile 후 llm.analyze로 전달하세요")
- "이 레이어를 쓸 때는 ..." 같은 레이어별 사용 지침

### 위반 사례와 올바른 대응
```
// ❌ 위반 — 프롬프트에 특정 레이어 언급
"readFile 시 반드시 이 경로를 사용"
"file-system.writeFile을 children에 추가하세요"

// ✅ 올바름 — 범용 구조 설명
"파일 접근 시 반드시 이 경로를 사용"
"저장 노드를 children에 추가하세요"
```

### LLM 라우팅 개선이 필요할 때
- 특정 레이어의 선택이나 사용 방식이 잘못된다 → 해당 **레이어의 KDoc을 수정**한다
- 프롬프트에 레이어 힌트를 추가하는 것은 금지
- 레이어 KDoc이 LLM에게 노출되므로, 레이어 자신이 "어떤 상황에서 나를 써야 하는지" 설명하면 된다
