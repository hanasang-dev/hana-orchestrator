package com.hana.orchestrator.presentation.controller

import com.hana.orchestrator.domain.entity.JobSchedule
import com.hana.orchestrator.domain.entity.ScheduledJob
import com.hana.orchestrator.orchestrator.JobRepository
import com.hana.orchestrator.orchestrator.JobScheduler
import com.hana.orchestrator.orchestrator.Orchestrator
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP (Model Context Protocol) 서버 컨트롤러
 *
 * SSE transport (spec 2024-11-05):
 *   GET  /mcp              → SSE 스트림 오픈, endpoint 이벤트로 sessionId 발급
 *   POST /mcp?sessionId=xx → JSON-RPC 요청 수신, SSE 채널로 응답 전송
 *
 * Streamable HTTP (직접 호출):
 *   POST /mcp              → JSON-RPC 요청/응답 직접 교환 (sessionId 없을 때)
 *
 * 노출 도구:
 *   list_layers   : 등록된 레이어 + 함수 목록 조회
 *   execute_layer : 특정 레이어 함수 직접 실행
 *   chat          : 오케스트레이터 ReAct 루프 실행
 *
 * Claude Code 등록:
 *   claude mcp add --transport sse hana http://localhost:8080/mcp
 */
class McpController(
    private val orchestrator: Orchestrator,
    private val jobRepository: JobRepository,
    private val jobScheduler: JobScheduler
) {

    /** sessionId → SSE 응답 채널 */
    private val sessions = ConcurrentHashMap<String, Channel<String>>()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private val TOOLS = buildJsonArray {
            addJsonObject {
                put("name", "list_layers")
                put("description", "List all registered layers with their functions and descriptions.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "execute_layer")
                put("description", "Execute a function on a specific layer. Use list_layers first to discover available layers and functions.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("layer") {
                            put("type", "string")
                            put("description", "Layer name, e.g. file-system, llm, git, develop, strategy")
                        }
                        putJsonObject("function") {
                            put("type", "string")
                            put("description", "Function name to call on the layer")
                        }
                        putJsonObject("args") {
                            put("type", "object")
                            put("description", "Key-value arguments for the function (optional)")
                        }
                    }
                    putJsonArray("required") { add("layer"); add("function") }
                }
            }
            addJsonObject {
                put("name", "chat")
                put("description", "Send a task or question to the orchestrator (blocking). The orchestrator decides which layers to use and returns the result. May take minutes on slow models — prefer chat_async for long tasks.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("message") {
                            put("type", "string")
                            put("description", "Task or question to execute")
                        }
                        putJsonObject("mode") {
                            put("type", "string")
                            put("description", "reactive (default) or tree")
                        }
                    }
                    putJsonArray("required") { add("message") }
                }
            }
            addJsonObject {
                put("name", "chat_async")
                put("description", "Start an orchestrator task in the background. Returns executionId immediately. Use get_execution(executionId) to poll status and fetch result.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("message") {
                            put("type", "string")
                            put("description", "Task or question to execute")
                        }
                    }
                    putJsonArray("required") { add("message") }
                }
            }
            addJsonObject {
                put("name", "get_execution")
                put("description", "Fetch execution status, result, logs, and ReAct tree for a given executionId. If executionId is omitted, returns the most recent execution (running or finished).")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("executionId") {
                            put("type", "string")
                            put("description", "Optional execution id. If omitted, the latest one is returned.")
                        }
                    }
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "list_executions")
                put("description", "List recent executions (newest first) with id, query, status, and timing.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "Max number of entries to return (default 20)")
                        }
                    }
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "patch_layer_kdoc")
                put("description", "Replace the class-level KDoc block of a layer source file. Use this to fix LLM routing when hana picks the wrong layer (re-explain what the layer does). After patching, run execute_layer('build', 'compileKotlin') then execute_layer('develop', 'reloadLayer', {name: 'LayerName'}) to take effect — KDoc is baked into LayerDescription at compile time by the KSP processor.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("layerName") {
                            put("type", "string")
                            put("description", "Layer name (e.g. \"Echo\", \"FileSystem\"). \"Layer\" suffix optional.")
                        }
                        putJsonObject("newKdoc") {
                            put("type", "string")
                            put("description", "Full KDoc block including opening /** and closing */. Will be inserted verbatim in place of the existing class KDoc.")
                        }
                    }
                    putJsonArray("required") { add("layerName"); add("newKdoc") }
                }
            }
            addJsonObject {
                put("name", "compare_candidate")
                put("description", "Diff a layer's pending improvement candidate against its current source. Reads .hana/candidates/{LayerName}Layer.candidate.kt vs src/.../layer/{LayerName}Layer.kt. Returns unified diff + line stats. Use this after develop.improveLayer to decide apply/reject without re-reading the full file.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("layerName") {
                            put("type", "string")
                            put("description", "Layer name (e.g. \"Echo\", \"FileSystem\"). \"Layer\" suffix optional.")
                        }
                        putJsonObject("truncate") {
                            put("type", "integer")
                            put("description", "Max chars for diff/source fields (default 16000, 0 = no truncate)")
                        }
                    }
                    putJsonArray("required") { add("layerName") }
                }
            }
            addJsonObject {
                put("name", "create_job")
                put("description", "Register a scheduled / background orchestrator task. Use this to queue work for hana to do on its own (self-improvement loop, periodic eval, retry queues). schedule shapes: {\"type\":\"once\",\"at\":<epochMs>}, {\"type\":\"interval\",\"intervalMs\":<ms>}, {\"type\":\"daily\",\"hour\":H,\"minute\":M}, {\"type\":\"loop\",\"delayMs\":<ms>}. Polling cadence is 60s — use trigger_job for immediate start.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string"); put("description", "Human-readable label.") }
                        putJsonObject("query") { put("type", "string"); put("description", "Task message sent to the ReAct loop.") }
                        putJsonObject("schedule") {
                            put("type", "object")
                            put("description", "JobSchedule polymorphic JSON (kotlinx serialization). type field selects variant.")
                        }
                        putJsonObject("enabled") { put("type", "boolean"); put("description", "Default true. Set false to register but pause.") }
                        putJsonObject("autoApprove") { put("type", "boolean"); put("description", "Skip approval gates (unattended runs). Default false.") }
                        putJsonObject("includeMetrics") { put("type", "boolean"); put("description", "Prepend system metrics snapshot to the query. Default false.") }
                    }
                    putJsonArray("required") { add("name"); add("query"); add("schedule") }
                }
            }
            addJsonObject {
                put("name", "list_jobs")
                put("description", "List all registered scheduled jobs with id, name, schedule, lastRunAt, lastStatus.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "trigger_job")
                put("description", "Run a registered job immediately (bypass the 60s poller). Returns false if job not found or already running.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("jobId") { put("type", "string"); put("description", "Job id from list_jobs / create_job.") }
                    }
                    putJsonArray("required") { add("jobId") }
                }
            }
            addJsonObject {
                put("name", "update_job")
                put("description", "Update fields of an existing job. Omitted fields stay unchanged. Pass {\"enabled\":false} to pause a Loop.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("jobId") { put("type", "string"); put("description", "Job id to update.") }
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("query") { put("type", "string") }
                        putJsonObject("schedule") { put("type", "object"); put("description", "If present, nextRunAt is recomputed.") }
                        putJsonObject("enabled") { put("type", "boolean") }
                        putJsonObject("autoApprove") { put("type", "boolean") }
                        putJsonObject("includeMetrics") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("jobId") }
                }
            }
            addJsonObject {
                put("name", "delete_job")
                put("description", "Delete a scheduled job. Running iterations finish; Loop schedules stop on next reload.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("jobId") { put("type", "string") }
                    }
                    putJsonArray("required") { add("jobId") }
                }
            }
            addJsonObject {
                put("name", "get_reasoning_trace")
                put("description", "Fetch per-step LLM I/O (sent prompt, raw response, extracted JSON, parse error, latency) for a ReAct execution. Use this to inspect why the orchestrator made a decision and fix prompts or layer KDocs when reasoning is wrong.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("executionId") {
                            put("type", "string")
                            put("description", "Execution id to inspect (required)")
                        }
                        putJsonObject("step") {
                            put("type", "integer")
                            put("description", "Single step number. Omit to return all steps.")
                        }
                        putJsonObject("truncate") {
                            put("type", "integer")
                            put("description", "Max chars per prompt/response field (default 8000, 0 = no truncate)")
                        }
                    }
                    putJsonArray("required") { add("executionId") }
                }
            }
            addJsonObject {
                put("name", "cancel_execution")
                put("description", "Cancel a running execution by id. If executionId is omitted, cancels all active executions.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("executionId") {
                            put("type", "string")
                            put("description", "Execution id to cancel (optional — omit to cancel all active).")
                        }
                    }
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "session_list")
                put("description", "List all hana sessions. Each session is a named conversation context that groups executions in time order. The active session's context is injected into every ReAct prompt.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "session_create")
                put("description", "Create a new session and make it active. Use this to start a fresh conversation context.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "session_activate")
                put("description", "Switch the active session to a given sessionId. Future executions will be appended to this session and its history will be injected into the ReAct prompt.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") { put("type", "string"); put("description", "Session id to activate (from session_list).") }
                    }
                    putJsonArray("required") { add("sessionId") }
                }
            }
            addJsonObject {
                put("name", "session_current")
                put("description", "Get the current active session details including task history (query + executionId pairs in time order).")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    putJsonArray("required") {}
                }
            }
            addJsonObject {
                put("name", "session_clear")
                put("description", "Clear all tasks from a session (reset history). The session id is preserved.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("sessionId") { put("type", "string"); put("description", "Session id to clear. Omit to clear the active session.") }
                    }
                    putJsonArray("required") {}
                }
            }
        }
    }

    /** chat_async가 띄운 background job 보관 — 서버 종료될 때까지 유지 */
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun configureRoutes(route: Route) {
        route.options("/mcp") {
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            call.response.headers.append("Access-Control-Allow-Headers", "Content-Type, Accept, Mcp-Session-Id")
            call.respond(HttpStatusCode.OK)
        }

        // SSE transport: 클라이언트가 SSE 연결 오픈 → endpoint 이벤트로 sessionId 발급
        route.get("/mcp") {
            val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
            val channel = Channel<String>(capacity = 32)
            sessions[sessionId] = channel

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Cache-Control", "no-cache")
            call.response.headers.append("Connection", "keep-alive")
            call.response.headers.append("X-Accel-Buffering", "no")

            call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                // 25초마다 SSE 코멘트 라인 송신 — 장시간 chat에서 클라이언트 idle 타임아웃 방지
                val heartbeat: Job = scope.launch {
                    while (isActive) {
                        delay(25_000)
                        runCatching {
                            synchronized(this@respondTextWriter) {
                                write(": heartbeat\n\n")
                                flush()
                            }
                        }.onFailure { return@launch }
                    }
                }
                try {
                    // 클라이언트에 POST 엔드포인트 알림
                    write("event: endpoint\ndata: /mcp?sessionId=$sessionId\n\n")
                    flush()
                    // 채널에 메시지 오면 SSE로 전송
                    for (message in channel) {
                        synchronized(this) {
                            write("event: message\ndata: $message\n\n")
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    // 클라이언트 연결 끊김
                } finally {
                    heartbeat.cancel()
                    scope.cancel()
                    sessions.remove(sessionId)
                    channel.close()
                }
            }
        }

        // POST: SSE 세션 있으면 채널 경유, 없으면 직접 HTTP 응답
        route.post("/mcp") {
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            val sessionId = call.request.queryParameters["sessionId"]

            val body = runCatching { call.receiveText() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, """{"error":"Cannot read request body"}""")
                return@post
            }

            val req = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, """{"error":"Invalid JSON"}""")
                return@post
            }

            val id = req["id"]
            val method = req["method"]?.jsonPrimitive?.contentOrNull ?: ""
            val params = req["params"]?.jsonObject ?: buildJsonObject {}

            // Notification (id 없음) → 응답 없음
            if (id == null) {
                call.respond(HttpStatusCode.Accepted)
                return@post
            }

            val channel = sessionId?.let { sessions[it] }
            val response = buildResponse(id, method, params, channel)

            if (sessionId != null) {
                // SSE transport: 채널로 전송
                if (channel != null) {
                    channel.send(response.toString())
                    call.respond(HttpStatusCode.Accepted)
                } else {
                    call.respond(HttpStatusCode.NotFound, """{"error":"Session not found: $sessionId"}""")
                }
            } else {
                // Streamable HTTP: 직접 응답
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }
    }

    private suspend fun buildResponse(
        id: JsonElement,
        method: String,
        params: JsonObject,
        sseChannel: Channel<String>?
    ): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> putJsonObject("result") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "hana-orchestrator")
                        put("version", "1.0.0")
                    }
                }

                "tools/list" -> putJsonObject("result") {
                    put("tools", TOOLS)
                }

                "tools/call" -> {
                    val toolName = params["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val arguments = params["arguments"]?.jsonObject ?: buildJsonObject {}
                    val progressToken = params["_meta"]?.jsonObject?.get("progressToken")
                    val (text, isError) = runCatching { callTool(toolName, arguments, sseChannel, progressToken) }
                        .fold(
                            onSuccess = { it to false },
                            onFailure = { "Error: ${it.message}" to true }
                        )
                    putJsonObject("result") {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", text)
                            }
                        }
                        put("isError", isError)
                    }
                }

                else -> putJsonObject("error") {
                    put("code", -32601)
                    put("message", "Method not found: $method")
                }
            }
        }
    }

    private suspend fun callTool(
        name: String,
        arguments: JsonObject,
        sseChannel: Channel<String>?,
        progressToken: JsonElement?
    ): String {
        return when (name) {
            "list_layers" -> {
                val descriptions = orchestrator.getAllLayerDescriptions()
                json.encodeToString(descriptions)
            }

            "execute_layer" -> {
                val layer = arguments["layer"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'layer' argument required"
                val function = arguments["function"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'function' argument required"
                val args = arguments["args"]?.jsonObject?.toAnyMap() ?: emptyMap()
                orchestrator.executeOnLayer(layer, function, args)
            }

            "chat_async" -> {
                val message = arguments["message"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'message' argument required"
                val idDeferred = kotlinx.coroutines.CompletableDeferred<String>()
                asyncScope.launch {
                    runCatching {
                        orchestrator.executeOrchestration(
                            com.hana.orchestrator.domain.dto.ChatDto(message = message),
                            onStart = { id -> idDeferred.complete(id) }
                        )
                    }
                }
                val id = kotlinx.coroutines.withTimeout(5_000) { idDeferred.await() }
                json.encodeToString(buildJsonObject {
                    put("executionId", id)
                    put("status", "RUNNING")
                    put("hint", "Use get_execution(executionId) to poll status and fetch result.")
                })
            }

            "get_execution" -> {
                val requestedId = arguments["executionId"]?.jsonPrimitive?.contentOrNull
                val history: com.hana.orchestrator.domain.entity.ExecutionHistory? = if (requestedId != null) {
                    orchestrator.getCurrentExecution()?.takeIf { it.id == requestedId }
                        ?: orchestrator.getExecutionHistory(200).firstOrNull { it.id == requestedId }
                } else {
                    orchestrator.getCurrentExecution()
                        ?: orchestrator.getExecutionHistory(1).firstOrNull()
                }
                if (history == null) "Error: execution not found"
                else json.encodeToString(history)
            }

            "list_executions" -> {
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20
                val current = orchestrator.getCurrentExecution()
                val history = orchestrator.getExecutionHistory(limit)
                val combined = if (current != null && history.none { it.id == current.id }) {
                    listOf(current) + history
                } else history
                val summary = buildJsonArray {
                    combined.take(limit).forEach { h ->
                        addJsonObject {
                            put("executionId", h.id)
                            put("query", h.query)
                            put("status", h.status.name)
                            put("startTime", h.startTime)
                            h.endTime?.let { put("endTime", it) }
                        }
                    }
                }
                summary.toString()
            }

            "patch_layer_kdoc" -> {
                val raw = arguments["layerName"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'layerName' argument required"
                val newKdoc = arguments["newKdoc"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'newKdoc' argument required"
                if (!newKdoc.trimStart().startsWith("/**") || !newKdoc.trimEnd().endsWith("*/")) {
                    return "Error: 'newKdoc' must start with /** and end with */"
                }

                val normalized = raw.removeSuffix("Layer")
                val projectRoot = java.io.File(System.getProperty("user.dir") ?: ".")
                val file = java.io.File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer/${normalized}Layer.kt")
                if (!file.exists()) return "Error: layer source not found: ${file.relativeTo(projectRoot).path}"

                val src = file.readText()
                // 클래스 선언 직전 KDoc 블록 매칭 (어노테이션 사이 포함)
                val classDecl = Regex("""class\s+${Regex.escape(normalized)}Layer\b""")
                val classMatch = classDecl.find(src)
                    ?: return "Error: class ${normalized}Layer not found in source"
                val classStart = classMatch.range.first
                // 클래스 선언 직전까지의 부분에서 마지막 KDoc 블록 위치 추적
                val before = src.substring(0, classStart)
                val kdocPattern = Regex("""/\*\*[\s\S]*?\*/""")
                val kdocMatches = kdocPattern.findAll(before).toList()
                if (kdocMatches.isEmpty()) {
                    return "Error: no KDoc block found before class ${normalized}Layer. Add it manually first."
                }
                val lastKdoc = kdocMatches.last()
                // 마지막 KDoc과 클래스 선언 사이엔 공백·어노테이션만 허용
                val between = before.substring(lastKdoc.range.last + 1)
                if (!between.matches(Regex("""\s*(?:@\w+(?:\([^)]*\))?\s*)*"""))) {
                    return "Error: code between KDoc and class declaration is not just whitespace/annotations — refusing to patch. Investigate manually."
                }

                val patched = src.substring(0, lastKdoc.range.first) + newKdoc + src.substring(lastKdoc.range.last + 1)

                // atomic write
                val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(patched)
                tmp.renameTo(file)

                val oldKdocSnippet = lastKdoc.value.let { if (it.length > 300) it.take(300) + "…" else it }
                val newKdocSnippet = newKdoc.let { if (it.length > 300) it.take(300) + "…" else it }
                json.encodeToString(buildJsonObject {
                    put("layerName", "${normalized}Layer")
                    put("filePath", file.relativeTo(projectRoot).path)
                    put("oldKdocChars", lastKdoc.value.length)
                    put("newKdocChars", newKdoc.length)
                    put("oldKdocPreview", oldKdocSnippet)
                    put("newKdocPreview", newKdocSnippet)
                    put("nextSteps", "execute_layer('build', 'compileKotlin') → execute_layer('develop', 'reloadLayer', {name: '${normalized}Layer'})")
                })
            }

            "compare_candidate" -> {
                val raw = arguments["layerName"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'layerName' argument required"
                val truncate = arguments["truncate"]?.jsonPrimitive?.intOrNull ?: 16000
                fun cut(s: String) = if (truncate > 0 && s.length > truncate) s.take(truncate) + "…[truncated, ${s.length - truncate} more chars]" else s

                val normalized = raw.removeSuffix("Layer")
                val projectRoot = java.io.File(System.getProperty("user.dir") ?: ".")
                val originalFile = java.io.File(projectRoot, "src/main/kotlin/com/hana/orchestrator/layer/${normalized}Layer.kt")
                val candidateFile = java.io.File(projectRoot, ".hana/candidates/${normalized}Layer.candidate.kt")

                val candidateExists = candidateFile.exists()
                val originalExists = originalFile.exists()

                val diff = if (candidateExists && originalExists) {
                    runCatching {
                        val proc = ProcessBuilder("diff", "-u", originalFile.absolutePath, candidateFile.absolutePath)
                            .redirectErrorStream(true)
                            .start()
                        val out = proc.inputStream.bufferedReader().readText()
                        proc.waitFor()
                        out.ifBlank { "(no diff — identical)" }
                    }.getOrElse { "(diff failed: ${it.message})" }
                } else ""

                val (added, removed) = if (diff.isNotEmpty()) {
                    val a = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
                    val r = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
                    a to r
                } else 0 to 0

                json.encodeToString(buildJsonObject {
                    put("layerName", "${normalized}Layer")
                    put("originalPath", originalFile.relativeTo(projectRoot).path)
                    put("candidatePath", candidateFile.relativeTo(projectRoot).path)
                    put("originalExists", originalExists)
                    put("candidateExists", candidateExists)
                    put("addedLines", added)
                    put("removedLines", removed)
                    if (candidateExists && originalExists) {
                        put("diff", cut(diff))
                    } else {
                        put("hint", "No pending candidate. Run develop.improveLayer(layerName) first.")
                    }
                })
            }

            "create_job" -> {
                val name = arguments["name"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'name' argument required"
                val query = arguments["query"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'query' argument required"
                val scheduleJson = arguments["schedule"]?.jsonObject
                    ?: return "Error: 'schedule' argument required"
                val schedule = runCatching { json.decodeFromJsonElement<JobSchedule>(scheduleJson) }
                    .getOrElse { return "Error: invalid schedule shape — ${it.message}" }
                val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                val autoApprove = arguments["autoApprove"]?.jsonPrimitive?.booleanOrNull ?: false
                val includeMetrics = arguments["includeMetrics"]?.jsonPrimitive?.booleanOrNull ?: false

                val job = ScheduledJob(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    query = query,
                    schedule = schedule,
                    enabled = enabled,
                    autoApprove = autoApprove,
                    includeMetrics = includeMetrics
                )
                val scheduled = jobScheduler.scheduleNext(job)
                jobRepository.save(scheduled)
                json.encodeToString(scheduled)
            }

            "list_jobs" -> json.encodeToString(jobRepository.list())

            "trigger_job" -> {
                val jobId = arguments["jobId"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'jobId' argument required"
                val ok = jobScheduler.triggerNow(jobId)
                json.encodeToString(buildJsonObject {
                    put("triggered", ok)
                    put("jobId", jobId)
                })
            }

            "update_job" -> {
                val jobId = arguments["jobId"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'jobId' argument required"
                val existing = jobRepository.load(jobId)
                    ?: return "Error: job not found: $jobId"
                val newSchedule = arguments["schedule"]?.jsonObject?.let {
                    runCatching { json.decodeFromJsonElement<JobSchedule>(it) }
                        .getOrElse { e -> return "Error: invalid schedule shape — ${e.message}" }
                }
                val updated = existing.copy(
                    name = arguments["name"]?.jsonPrimitive?.contentOrNull ?: existing.name,
                    query = arguments["query"]?.jsonPrimitive?.contentOrNull ?: existing.query,
                    schedule = newSchedule ?: existing.schedule,
                    enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull ?: existing.enabled,
                    autoApprove = arguments["autoApprove"]?.jsonPrimitive?.booleanOrNull ?: existing.autoApprove,
                    includeMetrics = arguments["includeMetrics"]?.jsonPrimitive?.booleanOrNull ?: existing.includeMetrics
                )
                val rescheduled = if (newSchedule != null) jobScheduler.scheduleNext(updated) else updated
                jobRepository.save(rescheduled)
                json.encodeToString(rescheduled)
            }

            "delete_job" -> {
                val jobId = arguments["jobId"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'jobId' argument required"
                val deleted = jobRepository.delete(jobId)
                json.encodeToString(buildJsonObject {
                    put("deleted", deleted)
                    put("jobId", jobId)
                })
            }

            "get_reasoning_trace" -> {
                val executionId = arguments["executionId"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'executionId' argument required"
                val step = arguments["step"]?.jsonPrimitive?.intOrNull
                val truncate = arguments["truncate"]?.jsonPrimitive?.intOrNull ?: 8000
                fun cut(s: String) = if (truncate > 0 && s.length > truncate) s.take(truncate) + "…[truncated, ${s.length - truncate} more chars]" else s

                val trace = com.hana.orchestrator.llm.ReasoningTraceRecorder.get(executionId)
                    ?: return "Error: no trace recorded for executionId=$executionId (executions older than ${50} may have been evicted)"

                val steps = if (step != null) {
                    trace.steps.filter { it.stepNumber == step }
                } else trace.steps

                if (step != null && steps.isEmpty()) {
                    return "Error: step $step not found for executionId=$executionId"
                }

                json.encodeToString(buildJsonObject {
                    put("executionId", trace.executionId)
                    put("createdAt", trace.createdAt)
                    put("stepCount", trace.steps.size)
                    putJsonArray("steps") {
                        steps.forEach { s ->
                            addJsonObject {
                                put("stepNumber", s.stepNumber)
                                put("contextMode", s.contextMode)
                                put("promptChars", s.promptChars)
                                put("latencyMs", s.latencyMs)
                                put("prompt", cut(s.prompt))
                                put("rawResponse", cut(s.rawResponse))
                                put("extractedJson", cut(s.extractedJson))
                                s.parsedDecision?.let { put("parsedDecision", cut(it)) }
                                s.parseError?.let { put("parseError", it) }
                            }
                        }
                    }
                })
            }

            "cancel_execution" -> {
                val targetId = arguments["executionId"]?.jsonPrimitive?.contentOrNull
                val cancelled = orchestrator.cancelCurrentExecution(targetId)
                json.encodeToString(buildJsonObject {
                    put("cancelled", cancelled)
                    if (targetId != null) put("executionId", targetId)
                })
            }

            "chat" -> {
                val message = arguments["message"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'message' argument required"

                // progressToken + SSE 채널 둘 다 있으면 ReAct 진행상황을 notifications/progress 로 흘려보냄
                val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val capturedExecutionId = AtomicReference<String?>(null)
                val progressJob: Job? = if (sseChannel != null && progressToken != null) {
                    orchestrator.progressUpdates
                        .onEach { update ->
                            val current = capturedExecutionId.get()
                            // 첫 STARTING으로 이 호출의 executionId 캡처, 이후 동일 id만 통과
                            if (current == null) {
                                if (update.phase.name == "STARTING") {
                                    capturedExecutionId.set(update.executionId)
                                } else return@onEach
                            } else if (update.executionId != current) {
                                return@onEach
                            }
                            val notif = buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("method", "notifications/progress")
                                putJsonObject("params") {
                                    put("progressToken", progressToken)
                                    put("progress", update.progress)
                                    put("total", 100)
                                    put("message", "[${update.phase.name}] ${update.message}")
                                }
                            }
                            runCatching { sseChannel.send(notif.toString()) }
                        }
                        .launchIn(progressScope)
                } else null

                try {
                    val result = orchestrator.executeOrchestration(message)
                    result.result.ifEmpty { result.error ?: "(empty result)" }
                } finally {
                    progressJob?.cancel()
                    progressScope.cancel()
                }
            }

            "session_list" -> orchestrator.executeOnLayer("session", "listSessions")

            "session_create" -> orchestrator.executeOnLayer("session", "createSession")

            "session_activate" -> {
                val sessionId = arguments["sessionId"]?.jsonPrimitive?.contentOrNull
                    ?: return "Error: 'sessionId' argument required"
                orchestrator.executeOnLayer("session", "activateSession", mapOf("sessionId" to sessionId))
            }

            "session_current" -> orchestrator.executeOnLayer("session", "getSession")

            "session_clear" -> {
                val sessionId = arguments["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
                orchestrator.executeOnLayer("session", "clearSession",
                    if (sessionId.isNotBlank()) mapOf("sessionId" to sessionId) else emptyMap())
            }

            else -> "Unknown tool: $name"
        }
    }

    /** JsonObject → Map<String, Any> (재귀 변환) */
    private fun JsonObject.toAnyMap(): Map<String, Any> = entries.associate { (k, v) -> k to v.toAny() }

    private fun JsonElement.toAny(): Any = when (this) {
        is JsonPrimitive -> when {
            isString -> content
            content == "true" || content == "false" -> content.toBoolean()
            content.contains('.') -> content.toDoubleOrNull() ?: content
            else -> content.toLongOrNull() ?: content
        }
        is JsonObject -> toAnyMap()
        is JsonArray -> map { it.toAny() }
    }
}
