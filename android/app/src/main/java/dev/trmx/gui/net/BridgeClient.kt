package dev.trmx.gui.net

/*
 * Data-plane client for TRMX-P/1 (loopback HTTP to trmx-bridge).
 * Pure JVM (OkHttp + kotlinx.serialization, no Android imports) so it is
 * fully unit-testable with MockWebServer.
 *
 * Every request carries BOTH required headers (PROTOCOL.md §4):
 *   Authorization: Bearer <token>
 *   X-TRMX-Protocol: 1
 * OkHttp never sends an Origin header (that would be 403 ORIGIN_DENIED).
 */

import dev.trmx.gui.model.CancelRequest
import dev.trmx.gui.model.ErrorEnvelope
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.JobsPage
import dev.trmx.gui.model.SubmitOutcome
import dev.trmx.gui.model.SubmitRequest
import dev.trmx.gui.model.SubmitResponse
import dev.trmx.gui.model.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface BridgeResult<out T> {
    data class Success<T>(val data: T) : BridgeResult<T>
    data class HttpError(val status: Int, val code: String, val message: String) : BridgeResult<Nothing>
    data class NetworkError(val cause: Throwable) : BridgeResult<Nothing>
}

class BridgeClient(
    private val baseUrl: String = "http://127.0.0.1:27342",
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    suspend fun systemInfo(): BridgeResult<SystemInfo> =
        get("/v1/system/info") { body, _ -> jsonFormat.decodeFromString(SystemInfo.serializer(), body) }

    suspend fun listJobs(
        limit: Int = 50,
        status: String? = null,
        before: String? = null,
    ): BridgeResult<JobsPage> {
        var path = "/v1/jobs?limit=$limit"
        status?.let { path += "&status=$it" }
        before?.let { path += "&before=$it" }
        return get(path) { body, _ -> jsonFormat.decodeFromString(JobsPage.serializer(), body) }
    }

    suspend fun getJob(jobId: String): BridgeResult<JobSummary> =
        get("/v1/jobs/$jobId") { body, _ -> jsonFormat.decodeFromString(JobSummary.serializer(), body) }

    suspend fun submitJob(request: SubmitRequest): BridgeResult<SubmitOutcome> =
        call("POST", "/v1/jobs",
             jsonFormat.encodeToString(SubmitRequest.serializer(), request).toRequestBody(JSON)) { body, headers ->
            val resp = jsonFormat.decodeFromString(SubmitResponse.serializer(), body)
            SubmitOutcome(resp, "true".equals(headers["Idempotent-Replay"], ignoreCase = true))
        }

    suspend fun cancelJob(jobId: String, graceMs: Long? = null, force: Boolean = false): BridgeResult<JobSummary> =
        call("POST", "/v1/jobs/$jobId/cancel",
             jsonFormat.encodeToString(CancelRequest.serializer(), CancelRequest(graceMs, force))
                 .toRequestBody(JSON)) { body, _ ->
            jsonFormat.decodeFromString(JobSummary.serializer(), body)
        }

    // ---- internals -----------------------------------------------------

    private suspend fun <T> get(
        path: String,
        parse: (body: String, headers: Headers) -> T,
    ): BridgeResult<T> = call("GET", path, null, parse)

    private suspend fun <T> call(
        method: String,
        path: String,
        body: RequestBody?,
        parse: (body: String, headers: Headers) -> T,
    ): BridgeResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .method(method, body)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val bodyText = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    BridgeResult.Success(parse(bodyText, resp.headers))
                } else {
                    val err = runCatching {
                        jsonFormat.decodeFromString(ErrorEnvelope.serializer(), bodyText).error
                    }.getOrNull()
                    BridgeResult.HttpError(
                        resp.code,
                        err?.code ?: "HTTP_${resp.code}",
                        err?.message ?: bodyText.take(200))
                }
            }
        } catch (e: IOException) {
            BridgeResult.NetworkError(e)
        } catch (e: Exception) {
            BridgeResult.NetworkError(e)
        }
    }

    companion object {
        const val PROTOCOL_HEADER = "X-TRMX-Protocol"
        const val PROTOCOL_VERSION = "1"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val jsonFormat: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            encodeDefaults = true   // wire shape: optional fields are sent explicitly (env: null, …)
        }
    }
}
