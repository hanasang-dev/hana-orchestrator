# Hana Orchestrator - 개발 규칙

## 코딩 원칙 (필수 준수)

### DRY (Don't Repeat Yourself)
- 동일한 로직이 2곳 이상 등장하면 반드시 추출
- 공통 함수/클래스/컴포넌트로 분리
- 복사-붙여넣기 금지

### KISS (Keep It Simple, Stupid)
- 가장 단순한 구현을 먼저 선택
- 불필요한 추상화 레이어 추가 금지
- 읽기 어려운 코드는 잘못된 코드

### YAGNI (You Aren't Gonna Need It)
- 지금 당장 필요한 것만 구현
- "나중에 필요할 것 같은" 코드 추가 금지
- 요구사항에 없는 기능 선제 구현 금지

### OOP
- SRP: 클래스/함수는 단 하나의 책임만
- 변경 이유가 2개 이상이면 분리
- 인터페이스로 의존성 역전 (DIP)
- 구현보다 인터페이스에 의존

## 아키텍처 원칙 (절대 위반 금지)

### 레이어 격리
- **레이어는 서로의 존재를 알아서는 안 된다** — 레이어 코드·KDoc에 타 레이어명·함수명 언급 금지
- **레이어의 동작·사용법은 레이어 자신이 KDoc으로 설명한다** — 조합 패턴은 LLM이 결정
- 세부 규칙 → [`layer/CLAUDE.md`](src/main/kotlin/com/hana/orchestrator/layer/CLAUDE.md)

### 오케스트레이터 책임
- **오케스트레이터도 하나의 레이어다** — `LLMPromptBuilder`는 자신의 구조적 책임만 담는다
  - 허용: 트리 구조, `{{parent}}` 동작, execute_tree/finish 판단, JSON 포맷
  - 금지: 특정 레이어명·함수명 언급, 레이어 조합 패턴
- LLM 라우팅 개선이 필요하면 → 해당 레이어의 KDoc을 수정한다. 프롬프트에 우겨넣지 않는다.
- 세부 규칙 → [`llm/CLAUDE.md`](src/main/kotlin/com/hana/orchestrator/llm/CLAUDE.md)

## 개발 워크플로우
- 빌드 가능한 단위로 작업 후 반드시 빌드 확인
- 빌드 성공 확인 후 브라우저/API 테스트
- 테스트 확인 후 커밋
