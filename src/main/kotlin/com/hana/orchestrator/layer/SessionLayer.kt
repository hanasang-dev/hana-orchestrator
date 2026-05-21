package com.hana.orchestrator.layer

import com.hana.orchestrator.domain.entity.Session
import com.hana.orchestrator.domain.entity.SessionTask
import com.hana.orchestrator.orchestrator.createOrchestratorLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 세션 관리 레이어 — 연속된 쿼리 실행을 시간순으로 기록.
 *
 * tasks 배열 순서 = 실행 순서 (대화 흐름).
 * 각 task는 {query, executionId} — 중복/유사 병합 없음.
 * executionId로 ExecutionHistory 상세 참조 가능.
 *
 * 저장 위치: .hana/sessions/{sessionId}.json
 * 활성 세션: .hana/sessions/active.txt
 */
@Layer
class SessionLayer(
    private val projectRoot: File = File(System.getProperty("user.dir"))
) : CommonLayerInterface {

    private val logger = createOrchestratorLogger(SessionLayer::class.java, null)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sessionsDir get() = File(projectRoot, ".hana/sessions")
    private val activeFile get() = File(sessionsDir, "active.txt")

    // ── 공개 함수 ────────────────────────────────────────────────────────────

    /**
     * 새 세션 생성 후 활성 세션으로 설정.
     * @return 생성된 세션 ID
     */
    @LayerFunction
    suspend fun createSession(): String {
        val id = "ses_${System.currentTimeMillis()}"
        saveSession(Session(id = id))
        setActiveSessionId(id)
        logger.info("📂 [Session] 세션 생성: $id")
        return "OK: 세션 생성 완료 (id=$id)"
    }

    /**
     * 현재 활성 세션 ID 반환. 없으면 자동 생성.
     */
    @LayerFunction
    suspend fun currentSessionId(): String {
        return getOrCreateActiveSessionId()
    }

    /**
     * 현재 활성 세션의 쿼리 이력을 프롬프트 주입용 텍스트로 반환.
     * 세션이 없거나 비어있으면 빈 문자열 반환.
     */
    @LayerFunction
    suspend fun currentContext(): String {
        val sessionId = activeFile.takeIf { it.exists() }?.readText()?.trim() ?: return ""
        val session = loadSession(sessionId) ?: return ""
        if (session.tasks.isEmpty()) return ""
        val lines = session.tasks.joinToString("\n") { "- ${it.query}" }
        return "[세션 이전 작업]\n$lines"
    }

    /**
     * 실행 완료 후 현재 세션에 시간순 추가.
     * @param executionId 완료된 실행 ID
     * @param query 실행한 쿼리
     */
    @LayerFunction
    suspend fun addExecution(executionId: String, query: String): String {
        val sessionId = getOrCreateActiveSessionId()
        val session = loadSession(sessionId) ?: Session(id = sessionId)
        session.tasks.add(SessionTask(query = query, executionId = executionId))
        saveSession(session)
        logger.info("📌 [Session] task 추가: \"$query\" ($executionId)")
        return "OK: 세션에 추가됨 (sessionId=$sessionId, executionId=$executionId)"
    }

    /**
     * 세션 상세 정보 반환 (JSON).
     * @param sessionId 조회할 세션 ID. 생략 시 활성 세션.
     */
    @LayerFunction
    suspend fun getSession(sessionId: String = ""): String {
        val id = sessionId.ifBlank {
            activeFile.takeIf { it.exists() }?.readText()?.trim() ?: return "ERROR: 활성 세션 없음"
        }
        val session = loadSession(id) ?: return "ERROR: 세션 없음 (id=$id)"
        return json.encodeToString(session)
    }

    /**
     * 모든 세션 목록 반환.
     */
    @LayerFunction
    suspend fun listSessions(): String {
        val dir = sessionsDir
        if (!dir.exists()) return "세션 없음"
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return "세션 없음"
        if (files.isEmpty()) return "세션 없음"
        val activeId = activeFile.takeIf { it.exists() }?.readText()?.trim()
        return files.sortedByDescending { it.lastModified() }.joinToString("\n") { f ->
            val id = f.nameWithoutExtension
            val marker = if (id == activeId) " ← 활성" else ""
            "- $id$marker"
        }
    }

    /**
     * 세션 tasks 초기화 (세션 ID 유지).
     * @param sessionId 초기화할 세션 ID. 생략 시 활성 세션.
     */
    @LayerFunction
    suspend fun clearSession(sessionId: String = ""): String {
        val id = sessionId.ifBlank {
            activeFile.takeIf { it.exists() }?.readText()?.trim() ?: return "ERROR: 활성 세션 없음"
        }
        val session = loadSession(id) ?: return "ERROR: 세션 없음 (id=$id)"
        val count = session.tasks.size
        session.tasks.clear()
        saveSession(session)
        return "OK: 세션 초기화 완료 ($count tasks 삭제, id=$id)"
    }

    /**
     * 세션 활성화. 해당 세션이 존재할 때만 active.txt 갱신.
     * @param sessionId 활성화할 세션 ID
     */
    @LayerFunction
    suspend fun activateSession(sessionId: String): String {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return "ERROR: 세션 없음 (id=$sessionId)"
        setActiveSessionId(sessionId)
        logger.info("✅ [Session] 세션 활성화: $sessionId")
        return "OK: 세션 활성화 완료 (id=$sessionId)"
    }

    /**
     * 세션 삭제. 활성 세션이면 활성 해제.
     * @param sessionId 삭제할 세션 ID
     */
    @LayerFunction
    suspend fun deleteSession(sessionId: String): String {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return "ERROR: 세션 없음 (id=$sessionId)"
        file.delete()
        val activeId = activeFile.takeIf { it.exists() }?.readText()?.trim()
        if (activeId == sessionId) activeFile.delete()
        return "OK: 세션 삭제 완료 (id=$sessionId)"
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────────────────

    private fun getOrCreateActiveSessionId(): String {
        val existing = activeFile.takeIf { it.exists() }?.readText()?.trim()
        if (existing != null && File(sessionsDir, "$existing.json").exists()) return existing
        val id = "ses_${System.currentTimeMillis()}"
        saveSession(Session(id = id))
        setActiveSessionId(id)
        logger.info("📂 [Session] 활성 세션 없어 자동 생성: $id")
        return id
    }

    private fun setActiveSessionId(id: String) {
        sessionsDir.mkdirs()
        activeFile.writeText(id)
    }

    private fun saveSession(session: Session) {
        sessionsDir.mkdirs()
        File(sessionsDir, "${session.id}.json").writeText(json.encodeToString(session))
    }

    private fun loadSession(id: String): Session? {
        val file = File(sessionsDir, "$id.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Session>(file.readText())
        } catch (e: Exception) {
            logger.warn("⚠️ [Session] 세션 파일 파싱 실패 ($id): ${e.message}")
            null
        }
    }

    override suspend fun approvalPreview(function: String, args: Map<String, Any>): ApprovalPreview =
        ApprovalPreview(path = "session.$function", oldContent = null, newContent = "", kind = ApprovalKind.READ_ONLY)

    override suspend fun describe(): LayerDescription = SessionLayer_Description.layerDescription

    override suspend fun execute(function: String, args: Map<String, Any>): String = when (function) {
        "createSession" -> createSession()
        "currentSessionId" -> currentSessionId()
        "currentContext" -> currentContext()
        "addExecution" -> {
            val executionId = args["executionId"]?.toString() ?: return "ERROR: executionId 필수"
            val query = args["query"]?.toString() ?: return "ERROR: query 필수"
            addExecution(executionId, query)
        }
        "getSession" -> getSession(args["sessionId"]?.toString() ?: "")
        "listSessions" -> listSessions()
        "clearSession" -> clearSession(args["sessionId"]?.toString() ?: "")
        "activateSession" -> {
            val sessionId = args["sessionId"]?.toString() ?: return "ERROR: sessionId 필수"
            activateSession(sessionId)
        }
        "deleteSession" -> {
            val sessionId = args["sessionId"]?.toString() ?: return "ERROR: sessionId 필수"
            deleteSession(sessionId)
        }
        else -> throw IllegalArgumentException("Unknown function: $function. Available: createSession, currentSessionId, currentContext, addExecution, getSession, listSessions, clearSession, activateSession, deleteSession")
    }
}
