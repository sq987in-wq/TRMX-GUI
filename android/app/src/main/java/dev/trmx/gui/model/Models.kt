package dev.trmx.gui.model

/*
 * TRMX-P/1 wire models. Field names intentionally mirror the wire exactly
 * (snake_case) so there is no @SerialName mapping to get wrong; every field
 * the app does not need is still parsed leniently (ignoreUnknownKeys).
 *
 * Normative shapes: the JSON fixtures under fixtures/v1 + docs/PROTOCOL.md.
 * CAUTION: Kotlin block comments NEST — a glob pattern written inside a
 * comment opens a nested comment and can silently swallow the whole file.
 * (This exact bug cost CI round 1; see tests/kt_comment_audit below.)
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SystemInfo(
    val bridge_version: String = "",
    val protocol_versions: List<Int> = emptyList(),
    val uptime_s: Long = 0,
    val now: String? = null,
    val load: Load? = null,
    val memory: Memory? = null,
    val storage: Storage? = null,
    val termux: TermuxPaths? = null,
    val caps: Caps? = null,
    val features: Features? = null,
    val tools_detected: Int = 0,
)

@Serializable
data class Load(
    val jobs_running: Int = 0,
    val jobs_queued: Int = 0,
    val loadavg: List<Double> = emptyList(),
)

@Serializable
data class Memory(val total_mb: Long = 0, val free_mb: Long = 0)

@Serializable
data class Storage(val home_free_mb: Long = 0, val shared_available: Boolean = false)

@Serializable
data class TermuxPaths(val home: String? = null, val prefix: String? = null)

@Serializable
data class Caps(
    val max_concurrent_jobs: Int = 0,
    val queue_depth: Int = 0,
    val log_ring_mb: Double = 0.0,
)

@Serializable
data class Features(
    val termux_api: Boolean = false,
    val runit: Boolean = false,
    val scheduler: Boolean = false,
    val tool_schema_errors: List<String> = emptyList(),   // §7.2 (bridge v0.4.0+)
    val service_registry: Boolean = false,                // protocol 1.1 (§14, bridge v0.5.0+)
    val service_errors: List<String> = emptyList(),       // malformed ~/.trmx/services/*.json
)

@Serializable
data class JobError(val code: String = "", val message: String = "")

/** POST /v1/jobs request body (TRMX-P/1 §3.2; type "argv" only in V1). */
@Serializable
data class SubmitRequest(
    val name: String,
    val type: String,          // always "argv" in V1 — no default so it is always encoded
    val argv: List<String>,
    val cwd: String? = null,   // nulls are encoded explicitly, matching the fixture shape
    val env: Map<String, String>? = null,
    val timeout_s: Long? = null,
    val idempotency_key: String? = null,
)

/** POST /v1/jobs/{id}/cancel request body. */
@Serializable
data class CancelRequest(
    val grace_ms: Long?,
    val force: Boolean,
)

/** GET /v1/files entry (PROTOCOL §6.2). */
@Serializable
data class FileEntry(
    val name: String = "",
    val type: String = "",        // file | dir | symlink | other
    val size: Long = 0,
    val mtime: String? = null,
    val mode: String? = null,
    val target: String? = null,   // set for symlinks (display path)
)

@Serializable
data class FileListResponse(
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val next_offset: Int? = null,
)

@Serializable
data class FileStatResponse(
    val path: String = "",
    val entry: FileEntry? = null,
)

/** POST /v1/files operation request (PROTOCOL §6.3). */
@Serializable
data class FileOpRequest(
    val op: String,                       // mkdir|touch|rename|move|copy|delete
    val path: String,
    val recursive: Boolean? = null,
    val confirm: Boolean? = null,
    val new_name: String? = null,
    val dest_dir: String? = null,
)

@Serializable
data class FileOpResponse(
    val ok: Boolean = false,
    val entries_moved: Long? = null,
)

/** POST /v1/jobs response: {"job_id":…, "status":…} + Idempotent-Replay header. */
@Serializable
data class SubmitResponse(
    val job_id: String = "",
    val status: String = "",
)

/** Submit + whether it was an idempotent replay (Idempotent-Replay header). */
data class SubmitOutcome(
    val response: SubmitResponse,
    val replayed: Boolean,
)


@Serializable
data class JobSummary(
    val job_id: String,
    val name: String = "",
    val type: String = "",
    val tool: String? = null,
    val argv: List<String>? = null,
    val script: String? = null,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val status: String = "",
    val created_at: String? = null,
    val started_at: String? = null,
    val ended_at: String? = null,
    val pid: Long? = null,
    val pgid: Long? = null,
    val exit_code: Long? = null,
    val signal: Long? = null,
    val cancel_requested: Boolean = false,
    val cancel_reason: String? = null,
    val error: JobError? = null,
    val timeout_s: Long? = null,
    val progress_pct: Double? = null,
    val progress_detail: String? = null,
    val stdout_bytes: Long = 0,
    val stderr_bytes: Long = 0,
    val log_seq: Long = 0,
    val log_truncated: Boolean = false,
    val idempotency_key: String? = null,
)

@Serializable
data class JobsPage(
    val jobs: List<JobSummary> = emptyList(),
    val next_cursor: String? = null,
)

// ---- Tool Registry (PROTOCOL §7, bridge v0.4.0) --------------------------

/**
 * x_trmx UI vocabulary (Phase 9.5, additive & optional — schemas without it
 * render exactly as before; the bridge passes it through untouched per the
 * unknown-field tolerance rule). Rendering hints only: validation and argv
 * synthesis NEVER depend on x_trmx.
 */
@Serializable
data class XTrmx(
    val secret: Boolean = false,      // masked input (bridge-side log redaction arrives in Phase 10)
    val widget: String? = null,       // render hint: slider|segmented|chips|toggle|textarea
    val unit: String? = null,         // suffix inside the field, e.g. "MiB"
    val artifact: Boolean = false,    // this arg's value is a job output (artifact card)
)

/** One form field spec (§7.2). `default` is a raw JsonElement (string/bool/int). */
@Serializable
data class ToolArg(
    val name: String = "",
    val label: String = "",
    val type: String = "string",      // string|int|float|bool|enum|path|url
    val required: Boolean = false,
    val help: String? = null,
    val enum: List<String>? = null,
    val default: JsonElement? = null,
    val pattern: String? = null,
    val argv: List<String>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val path_kind: String? = null,    // file|dir
    val mkdir: Boolean = false,       // dir args: created at submit if missing (bridge >= 0.4.2)
    val x_trmx: XTrmx? = null,        // optional UI hints (never affect validation)
)

@Serializable
data class ToolExample(
    val label: String = "",
    val args: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class ToolSchema(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val binary: String = "",
    val pkg: String? = null,          // Termux package hint (app-side install)
    val risk_tier: String = "safe",   // safe|confirm|destructive
    val progress_regex: String? = null,
    val fixed_argv: List<String> = emptyList(),
    val args: List<ToolArg> = emptyList(),
    val examples: List<ToolExample> = emptyList(),
)

// ---- Services (protocol 1.1, §14, bridge v0.5.0) ---------------------------

/** One service: definition + live status derived from the bound job. */
@Serializable
data class ServiceStatus(
    val id: String = "",
    val name: String = "",
    val tool: String = "",
    val args: Map<String, JsonElement> = emptyMap(),
    val autostart: Boolean = false,
    val created_at: String? = null,
    val state: String = "stopped",        // running|stopped
    val job_id: String? = null,           // active job while running
    val last_job_id: String? = null,
    val last_status: String? = null,      // COMPLETED|FAILED|CANCELLED|LOST
    val last_exit_code: Int? = null,
    /** set only by the delete event payload */
    val deleted: Boolean = false,
)

@Serializable
data class ServicesResponse(
    val services: List<ServiceStatus> = emptyList(),
)

/** POST /v1/services body: create/replace a definition. */
@Serializable
data class ServiceDefRequest(
    val id: String,
    val name: String,
    val tool: String,
    val args: Map<String, JsonElement> = emptyMap(),
    val autostart: Boolean = false,
)

/** POST /v1/services/{id}/autostart body. */
@Serializable
data class AutostartRequest(val enabled: Boolean)

/** DELETE /v1/services/{id} response. */
@Serializable
data class ServiceAck(val ok: Boolean = false, val id: String = "")

// ---- AI backend config (protocol 1.1, §15, bridge v0.5.1+) ------------------

/** GET /v1/ai/config element: MASKED view — the raw key never crosses the wire. */
@Serializable
data class AiCliConfig(
    val command: List<String> = emptyList(),
    val timeout_s: Int = 0,
)

@Serializable
data class AiHttpConfig(
    val provider: String = "",
    val endpoint: String = "",
    val model: String = "",
    val api_key_set: Boolean = false,
    val api_key_env: String? = null,
    val timeout_s: Int = 0,
)

@Serializable
data class AiConfig(
    val exists: Boolean = false,
    val mode: String = "cli",          // cli | http_api
    val cli: AiCliConfig? = null,
    val http_api: AiHttpConfig? = null,
)

/** POST /v1/ai/config body: merge-patch; null = keep the saved value. */
@Serializable
data class AiCliUpdate(
    val command: List<String>? = null,
    val timeout_s: Int? = null,
)

@Serializable
data class AiHttpUpdate(
    val provider: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
    /** tri-state: null = keep the saved key, "" = clear, non-empty = set */
    val api_key: String? = null,
    val api_key_env: String? = null,
    val timeout_s: Int? = null,
)

@Serializable
data class AiConfigUpdate(
    val mode: String? = null,
    val cli: AiCliUpdate? = null,
    val http_api: AiHttpUpdate? = null,
)

/** GET /v1/tools element: ToolStatus (§7.1). */
@Serializable
data class ToolStatus(
    val schema: ToolSchema,
    val installed: Boolean = false,
    val version: String? = null,
)

@Serializable
data class ToolsResponse(
    val tools: List<ToolStatus> = emptyList(),
)

/** POST /v1/jobs with type "tool" (§7.2) — argv is synthesized bridge-side. */
@Serializable
data class ToolSubmitRequest(
    val name: String,
    val type: String = "tool",
    val tool: String,
    val args: Map<String, JsonElement>,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val timeout_s: Long? = null,
    val idempotency_key: String? = null,
)

/** TRMX-P/1 error envelope: {"error":{code,message,field,details}} */
@Serializable
data class ErrorEnvelope(
    val error: BridgeError = BridgeError(),
)

@Serializable
data class BridgeError(
    val code: String = "",
    val message: String = "",
    val field: String? = null,
)
