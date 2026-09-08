package dev.trmx.gui.net

/*
 * Minimal SSE client for TRMX-P/1 streams (PROTOCOL.md §4): job output
 * (stdout/stderr/status/info frames) and the global event stream.
 * Pure JVM (OkHttp + Okio) — fully unit-testable with MockWebServer.
 *
 * Wire format per frame (see fixtures/v1/jobs.output.stream.txt):
 *   event: stdout
 *   id: 4819
 *   data: {"job_id":"J-1A2B","seq":4819,"text":"…"}
 *   <blank line>
 * "retry:" lines and ": comment" pings are ignored. Stream ends at EOF
 * (the bridge closes the connection after a terminal status frame).
 *
 * Reconnection/resume is the COLLECTOR's job (from_seq query param), not
 * the transport's — deliberate, so resume policy is testable.
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class SseFrame(
    val event: String,
    val id: Long?,
    val data: String,
)

/** Non-2xx response while opening a stream (carries the HTTP status). */
class SseHttpException(val status: Int, val body: String) :
    IOException("SSE stream refused: HTTP $status ${body.take(120)}")

class SseClient(
    private val baseUrl: String = "http://127.0.0.1:27342",
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
) {

    fun stream(path: String): Flow<SseFrame> = flow {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header(BridgeClient.PROTOCOL_HEADER, BridgeClient.PROTOCOL_VERSION)
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = client.newCall(request)
        // wire coroutine cancellation to the blocking read
        val cancelHook = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw SseHttpException(resp.code, resp.body?.string() ?: "")
                }
                val source = resp.body?.source() ?: throw IOException("empty stream body")
                var event: String? = null
                var id: Long? = null
                val data = StringBuilder()
                fun resetBlock() {
                    event = null
                    id = null
                    data.setLength(0)
                }
                while (true) {
                    val line = source.readUtf8Line() ?: break   // EOF → flush below
                    when {
                        line.isEmpty() -> {
                            if (event != null || data.isNotEmpty()) {
                                emit(SseFrame(event ?: "message", id, data.toString()))
                                resetBlock()
                            }
                        }
                        line.startsWith(":") -> {}                    // ping / comment
                        line.startsWith("event:") ->
                            event = line.removePrefix("event:").trim()
                        line.startsWith("id:") ->
                            id = line.removePrefix("id:").trim().toLongOrNull()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").removePrefix(" "))
                        }
                        line.startsWith("retry:") -> {}               // we drive retries
                        else -> {}                                    // unknown field
                    }
                }
                // EOF: a stream may end right after the last data line with a
                // single newline (the normative fixtures do) — a pending frame
                // must still be delivered, not silently dropped.
                if (event != null || data.isNotEmpty()) {
                    emit(SseFrame(event ?: "message", id, data.toString()))
                }
            }
        } finally {
            cancelHook.dispose()
        }
    }.flowOn(Dispatchers.IO)
}
