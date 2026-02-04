package com.hana.orchestrator.presentation.model.chat

import kotlinx.serialization.Serializable

/**
 * 채팅/실행 요청.
 *
 * @param context 선택. workingDirectory·projectRoot는 오케스트레이터가 요청 시 서버 cwd로 자동 설정함.
 *   호출자가 넣어 줄 수 있는 것: currentFile, selection, projectRoot(서버와 다른 프로젝트일 때) 등.
 */
@Serializable
data class ChatRequest(
    val message: String,
    val context: Map<String, String> = emptyMap()
)
