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
