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

import dev.trmx.gui.model.ErrorEnvelope
import dev.trmx.gui.model.JobsPage
import dev.trmx.gui.model.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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
        get("/v1/system/info") { json -> jsonFormat.decodeFromString(SystemInfo.serializer(), json) }

    suspend fun listJobs(limit: Int = 50): BridgeResult<JobsPage> =
        get("/v1/jobs?limit=$limit") { json -> jsonFormat.decodeFromString(JobsPage.serializer(), json) }

    // ---- internals -----------------------------------------------------

    private suspend fun <T> get(
        path: String,
        parse: (String) -> T,
    ): BridgeResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    BridgeResult.Success(parse(body))
                } else {
                    val err = runCatching {
                        jsonFormat.decodeFromString(ErrorEnvelope.serializer(), body).error
                    }.getOrNull()
                    BridgeResult.HttpError(
                        resp.code,
                        err?.code ?: "HTTP_${resp.code}",
                        err?.message ?: body.take(200))
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

        val jsonFormat: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}
