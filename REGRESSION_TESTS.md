# Regression Test Suite — ReAct Loop

수동 테스트 절차. 빌드 후 서버 구동 상태에서 curl로 확인.

## 실행 방법

```bash
# 엔드포인트: /chat, 필드: message (query 아님)
curl -s -X POST http://localhost:8080/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "<질문>"}' | jq .

# Python (RTK가 curl 응답 필터링할 때 우회)
python3 -c "
import urllib.request, json
data = json.dumps({'message': '<질문>'}).encode()
req = urllib.request.Request('http://localhost:8080/chat', data=data, headers={'Content-Type': 'application/json'})
import urllib.request
with urllib.request.urlopen(req, timeout=300) as r: print(r.read().decode())
"
```

---

## T1 — 단순 응답 (LLM 직접 답변 or 1스텝)

| ID | 질문 | 기대 동작 | 통과 기준 |
|----|------|-----------|-----------|
| T1-1 | `"안녕"` | finish (직접 답변) | 레이어 호출 없음, 인사 응답 |
| T1-2 | `"1+1은?"` | finish | 레이어 호출 없음, "2" 포함 |
| T1-3 | `"오늘 날짜 알아?"` | finish | 레이어 없이 답변 또는 date 레이어 1회 |

**로그 체크**: `스텝 #1 결정: action=finish` 확인

---

## T2 — 단일 레이어 호출 (2스텝: execute_tree → finish)

| ID | 질문 | 기대 레이어 | 통과 기준 |
|----|------|------------|-----------|
| T2-1 | `"현재 git 브랜치가 뭐야?"` | `git.getCurrentBranch` | 브랜치 이름 포함 (예: `main`) |
| T2-2 | `"넌 뭘 할 수 있어?"` | `layer-info.listLayers` | 레이어 목록 반환 |
| T2-3 | `"git log 최근 3개 보여줘"` | `git.getLog` | 커밋 메시지 3개 이상 |
| T2-4 | `"DefaultReActStrategy.kt 파일 읽어줘"` | `file-system.readFile` | 파일 내용 반환 |

**로그 체크**:  
- `스텝 #1`: execute_tree  
- `스텝 #2`: finish (또는 중복감지 → lastData 반환)  
- ❌ `llm.analyze` 호출 없어야 함

---

## T3 — 다단계 (3스텝 이상)

| ID | 질문 | 기대 흐름 | 통과 기준 |
|----|------|-----------|-----------|
| T3-1 | `"OllamaLLMClient.kt 파일에서 타임아웃 관련 설정 찾아서 설명해줘"` | readFile → finish | 타임아웃 값/설정 설명 포함 |
| T3-2 | `"현재 브랜치의 최신 커밋 메시지 알려줘"` | git.getCurrentBranch → git.getLog → finish | 커밋 메시지 반환 |
| T3-3 | `"JsonSchemaBuilder.kt 파일 읽고 어떤 역할인지 설명해줘"` | readFile → finish | 스키마 빌더 역할 설명 |

**로그 체크**:  
- 각 스텝 결과가 다음 스텝 컨텍스트에 포함됨  
- 최종 응답이 `"작업 완료"` 단독이면 ❌ (lastData 오염)

---

## 회귀 트랩 — 반드시 확인

### RT-1: 중복 루프 감지 후 `llm.analyze` 호출 금지
```
증상: 🔁 [ReAct] 중복 루프 감지 → llm.analyze로 최종 답변 생성 중
원인: DefaultReActStrategy.kt duplicate detection에서 LLM 재호출
수정: 중복 감지 시 lastData 즉시 반환
```
**검증**: 로그에 `중복 루프 감지 → 마지막 결과 반환` 있어야 함. `llm.analyze` 없어야 함.

---

### RT-2: `requiredFollowUp` 노드 `autoApprove = true` 강제
```
증상: 서버 응답 없음 (무한 대기)
원인: rfNode 생성 시 autoApprove 기본값 false → ApprovalGate 대기
수정: ExecutionNodeResponse(rfLayerName, rfFunction, rfArgsJson, autoApprove = true)
```
**검증**: `requiredFollowUp` 포함된 응답 후 행이 없고 최종 결과 반환됨.

---

### RT-3: `lastData` 오염 방지 (중복 스텝 결과 우선)
```
증상: 응답에 실제 데이터 대신 "layer-info" 결과 또는 "작업 완료" 반환
원인: rfNode 결과가 lastData 덮어씀
수정: stepHistory에서 successfulFunctions ∩ proposed 있는 스텝 결과 우선 선택
```
**검증**: T2-4 쿼리 응답에 파일 내용 있어야 함. 레이어 목록 아님.

---

### RT-4: 히스토리 압축 후 LLM 망각
```
증상: 파일 읽은 직후 동일 readFile 재제안
원인: 결과가 크면 히스토리 13000자 초과 → 압축 → LLM이 이미 읽었음을 망각
현상: 중복감지가 커버함 (RT-1 수정으로 자동 처리)
```
**검증**: 위 상황 발생 시 `🔁 중복 루프 감지 → 마지막 결과 반환` 로그 있어야 함.

---

### RT-5a: `requiredFollowUp`에 존재하지 않는 함수 지정
```
증상: rfNode 실행 실패 "Unknown function: xxx" → LLM이 fallback readFile 제안 → 중복감지
결과: lastData(raw 파일 내용) 반환됨 — 처리 안 된 데이터, 사용자에게 무의미
원인: 모델이 없는 함수(text-transformer.searchContent 등)를 rfNode에 넣음
상태: rfNode 실패 시 에러 전파되지 않고 다음 스텝으로 넘어감 (tech debt)
```
**검증**: `ERROR(xxx.yyy): Unknown function` 로그 후 정상 스텝 진행 확인.

---

### RT-5: 병렬 요청 NPE (`ensureInitialized` 미처리)
```
증상: 동시 요청 시 NPE, "formatLayerDescriptionsCompact" 오류
원인: ensureInitialized()에 mutex 없음 → 레이어 24개 중복 등록 → null 엔트리
상태: 미수정 (tech debt)
회피: 테스트는 순차 실행
```
**검증**: 단일 요청에서 재현 안 됨. 동시 2+ 요청 시 재현 가능.

---

## 로그 패턴 레퍼런스

정상 흐름:
```
🔄 [ReAct] 스텝 #N 시작
🤔 [ReAct] LLM 결정 요청 (스텝 N, 프롬프트 XXXXX자)
📋 [RAW responseText ...]
🤔 [ReAct] 스텝 #N 결정: action=execute_tree
🌳 [ReAct] 스텝 #N 미니트리 실행: [layer.function]
✅ [TreeExecutor] layer.function 완료
...
🤔 [ReAct] 스텝 #N+1 결정: action=finish
```

중복감지 정상:
```
🔁 [ReAct] 중복 루프 감지 → 마지막 결과 반환 (이미 완료: [layer.function(args)])
```

이상 징후:
```
❌ llm.analyze 호출 로그 (RT-1)
❌ rfNode 실행 후 응답 없음 (RT-2)
❌ 최종 응답 = "작업 완료" 단독 (RT-3)
❌ ERROR(xxx): Unknown function — rfNode 잘못된 함수 (RT-5a)
❌ NPE in formatLayerDescriptionsCompact (RT-5)
```

## 확인된 동작 (2026-05-15 기준)

| 케이스 | 결과 | 비고 |
|--------|------|------|
| T2-1 "현재 git 브랜치가 뭐야?" | ✅ "main" 반환 | |
| T2-2 "넌 뭘 할 수 있어?" | ✅ 레이어 목록 반환 | 중복감지 정상 작동 |
| T2-3 "git log 최근 3개" | ✅ 108.6초, 커밋 3개 | |
| T2-4 "FileSystemLayer.kt 파일 읽어줘" | ✅ 파일 내용 반환 | 중복감지 후 lastData 정상 |
| T3-1 "OllamaLLMClient.kt timeoutMs 값?" | ⚠️ 클라이언트 300초 타임아웃 | rfNode에 없는 함수(searchContent) → 3스텝 중복감지까지 갔으나 클라이언트 먼저 끊김 |
