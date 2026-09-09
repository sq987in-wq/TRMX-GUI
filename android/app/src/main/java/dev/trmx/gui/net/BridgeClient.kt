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
import dev.trmx.gui.model.FileListResponse
import dev.trmx.gui.model.FileOpRequest
import dev.trmx.gui.model.FileOpResponse
import dev.trmx.gui.model.FileStatResponse
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

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

    // ---- files (PROTOCOL §6) ----------------------------------------------

    suspend fun listFiles(path: String, offset: Int = 0): BridgeResult<FileListResponse> =
        get("/v1/files?path=${enc(path)}&offset=$offset") { body, _ ->
            jsonFormat.decodeFromString(FileListResponse.serializer(), body)
        }

    suspend fun statFile(path: String): BridgeResult<FileStatResponse> =
        get("/v1/files?path=${enc(path)}&stat=1") { body, _ ->
            jsonFormat.decodeFromString(FileStatResponse.serializer(), body)
        }

    suspend fun fileOp(request: FileOpRequest): BridgeResult<FileOpResponse> =
        call("POST", "/v1/files",
             // §6.3 wire shape: optional fields are omitted when not
             // applicable (fixtures/v1/files.ops.request.json), unlike the
             // jobs plane's explicit nulls.
             opsJson.encodeToString(FileOpRequest.serializer(), request).toRequestBody(JSON),
             extraHeaders = null) { body, _ ->
            jsonFormat.decodeFromString(FileOpResponse.serializer(), body)
        }

    /**
     * Streamed download to [dest]; Content-Length verified when present.
     * [onProgress] receives (bytesCopied, totalOrNull) on the IO thread —
     * callers throttle before touching UI state.
     */
    suspend fun downloadFile(
        path: String,
        dest: File,
        onProgress: ((bytes: Long, total: Long?) -> Unit)? = null,
    ): BridgeResult<Long> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl + "/v1/files/content?path=" + enc(path))
                .header("Authorization", "Bearer $token")
                .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
                .get()
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    val body = resp.body ?: throw IOException("empty body")
                    if (!resp.isSuccessful) {
                        val text = body.string()
                        val err = runCatching {
                            jsonFormat.decodeFromString(ErrorEnvelope.serializer(), text).error
                        }.getOrNull()
                        BridgeResult.HttpError(resp.code, err?.code ?: "HTTP_${resp.code}",
                                                err?.message ?: text.take(200))
                    } else {
                        dest.parentFile?.mkdirs()
                        val expected = resp.headers["Content-Length"]?.toLongOrNull()
                        var copied = 0L
                        FileOutputStream(dest).use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    copied += n
                                    onProgress?.invoke(copied, expected)
                                }
                            }
                        }
                        if (expected != null && copied != expected) {
                            dest.delete()
                            BridgeResult.NetworkError(
                                IOException("truncated download: $copied of $expected bytes"))
                        } else {
                            BridgeResult.Success(copied)
                        }
                    }
                }
            } catch (e: IOException) {
                BridgeResult.NetworkError(e)
            } catch (e: Exception) {
                BridgeResult.NetworkError(e)
            }
        }

    /**
     * Streaming upload from [src] (PROTOCOL §6.5): temp-then-rename on the
     * bridge side, so a partial upload never leaves a partial file.
     * X-TRMX-Sha256 is computed over the file and verified by the bridge.
     * [onProgress] receives bytesSent (content length is known up front).
     */
    suspend fun uploadFile(
        path: String,
        src: File,
        overwrite: Boolean = false,
        onProgress: ((bytes: Long) -> Unit)? = null,
    ): BridgeResult<Unit> =
        withContext(Dispatchers.IO) {
            val sha = sha256Of(src)
            val body = if (onProgress != null) ProgressRequestBody(OCTET_STREAM, src, onProgress)
                       else src.asRequestBody(OCTET_STREAM)
            callRaw("PUT",
                    "/v1/files/content?path=" + enc(path) +
                        "&overwrite=${if (overwrite) "1" else "0"}",
                    body, mapOf("X-TRMX-Sha256" to sha)) { _, _ -> }
        }

    // ---- internals -----------------------------------------------------

    private suspend fun <T> get(
        path: String,
        parse: (body: String, headers: Headers) -> T,
    ): BridgeResult<T> = call("GET", path, null, parse = parse)

    private suspend fun <T> call(
        method: String,
        path: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>? = null,
        parse: (body: String, headers: Headers) -> T,   // last: trailing-lambda call sites
    ): BridgeResult<T> = callRaw(method, path, body, extraHeaders, parse)

    private suspend fun <T> callRaw(
        method: String,
        path: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>?,
        parse: (body: String, headers: Headers) -> T,
    ): BridgeResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .apply { extraHeaders?.forEach { (k, v) -> header(k, v) } }
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
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** URL-encode a path for a query param (space → %20, not +; ~ is
         *  RFC 3986 unreserved and stays bare — matches the fixtures' wire
         *  shape, e.g. path=~%2Fdownloads). A literal "%7E" in a name is
         *  encoded as %257E by URLEncoder, so this replace is unambiguous. */
        fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8")
                .replace("+", "%20")
                .replace("%7E", "~")

        /** §6.3 op requests: omit optional fields instead of sending nulls. */
        val opsJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        val jsonFormat: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            encodeDefaults = true   // wire shape: optional fields are sent explicitly (env: null, …)
        }
    }
}

/**
 * RequestBody that streams [file] in 64 KiB chunks and reports bytes as
 * they hit the wire (callers throttle before touching UI state).
 */
private class ProgressRequestBody(
    private val mime: MediaType?,
    private val file: File,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mime
    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        var sent = 0L
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent)
            }
        }
    }
}
