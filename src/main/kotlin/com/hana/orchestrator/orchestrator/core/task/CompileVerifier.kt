package com.hana.orchestrator.orchestrator.core.task

import com.hana.orchestrator.llm.VerifyOutcome

/**
 * 컴파일 결과 객관 검증기 — `build.compileKotlin` 등 gradle build 류 함수 결과 검사.
 *
 * 판정 규칙:
 *  - "BUILD FAILED" 포함 또는 "ERROR" 로 시작 → satisfied = false
 *  - "BUILD SUCCESSFUL" 포함 → satisfied = true
 *  - 그 외 → satisfied = true (BUILD FAILED 마커 없음 = 통과 추정, 보수적 fallback)
 *
 * `e: file:///...` 형식 컴파일 에러 첫 줄을 reasoning 에 노출 (LLM 가독성).
 */
class CompileVerifier : TaskVerifier {
    override suspend fun verify(result: String): VerifyOutcome {
        val isError = result.contains("BUILD FAILED") || result.startsWith("ERROR")
        if (isError) {
            val firstErr = result.lineSequence()
                .firstOrNull { it.trimStart().startsWith("e:") }
                ?.trim()
                ?.take(200)
                ?: "BUILD FAILED 감지"
            return VerifyOutcome(
                satisfied = false,
                missing = "컴파일 실패",
                reasoning = firstErr
            )
        }
        if (result.contains("BUILD SUCCESSFUL")) {
            return VerifyOutcome(satisfied = true, reasoning = "BUILD SUCCESSFUL")
        }
        return VerifyOutcome(satisfied = true, reasoning = "BUILD FAILED 마커 없음 — 통과 추정")
    }
}

/**
 * 함수 이름 → 자동 verifier 매핑 (catch-all 규칙).
 * LLM 권한 X, 인프라 결정. 미래에 annotation 기반으로 확장 가능.
 */
object DefaultVerifierRegistry {
    fun pick(layerName: String, function: String): TaskVerifier? = when {
        layerName == "build" && function in setOf("compileKotlin", "build") -> CompileVerifier()
        else -> null
    }
}
