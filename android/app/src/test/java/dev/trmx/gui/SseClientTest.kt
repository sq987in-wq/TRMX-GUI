package dev.trmx.gui

import dev.trmx.gui.net.SseClient
import dev.trmx.gui.net.SseFrame
import dev.trmx.gui.net.SseHttpException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * SSE wire parser against the normative stream fixtures (byte-identical
 * copies of fixtures/v1/jobs.output.stream.txt and events.stream.txt —
 * drift-checked by tests/spec_conformance.py).
 */
class SseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SseClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "test-token-0123456789abcdefghijklmnop")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses the normative job-output stream - events ids and data`() = runTest {
        server.enqueue(
            MockResponse().setBody(OUTPUT_STREAM_FIXTURE)
                .setHeader("Content-Type", "text/event-stream"))
        val frames = client.stream("/v1/jobs/J-1A2B/output?from_seq=4819&follow=1").toList()

        // pings and the retry line are skipped; six real frames remain
        assertEquals(
            listOf("stdout", "stdout", "stderr", "status", "stdout", "status"),
            frames.map { it.event })
        assertEquals(
            listOf(4819L, 4820L, 4821L, 4822L, 4823L, 4824L),
            frames.map { it.id })
        assertEquals(
            "{\"job_id\":\"J-1A2B\",\"seq\":4819,\"text\":\"[download]   1.2% of ~123.45MiB at 1.23MiB/s ETA 01:39\\n\"}",
            frames[0].data)
        assertTrue(frames[3].data.contains("\"status\":\"RUNNING\""))
        assertTrue(frames[5].data.contains("\"status\":\"COMPLETED\""))
    }

    @Test
    fun `parses the normative events stream - job updated and bridge stopping`() = runTest {
        server.enqueue(
            MockResponse().setBody(EVENTS_STREAM_FIXTURE)
                .setHeader("Content-Type", "text/event-stream"))
        val frames = client.stream("/v1/events").toList()

        assertEquals(
            listOf("job.updated", "job.updated", "bridge.stopping"),
            frames.map { it.event })
        assertEquals(listOf(1041L, 1042L, 1043L), frames.map { it.id })
        assertTrue(frames[0].data.contains("\"job_id\":\"J-1A2C\""))
        assertTrue(frames[2].data.contains("\"reason\":\"restart\""))
    }

    @Test
    fun `stream request carries auth and protocol headers`() = runTest {
        server.enqueue(
            MockResponse().setBody("event: stdout\nid: 1\ndata: {}\n\n")
                .setHeader("Content-Type", "text/event-stream"))
        client.stream("/v1/jobs/J-1/output").toList()
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-token-0123456789abcdefghijklmnop",
                     req.getHeader("Authorization"))
        assertEquals("1", req.getHeader("X-TRMX-Protocol"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
    }

    @Test
    fun `non-2xx surfaces as SseHttpException with the http status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        var thrown: SseHttpException? = null
        try {
            client.stream("/v1/jobs/J-NOPE/output").toList()
        } catch (e: SseHttpException) {
            thrown = e
        }
        assertEquals(404, thrown?.status)
    }

    @Test
    fun `multi-line data fields are joined with newlines`() = runTest {
        server.enqueue(
            MockResponse().setBody("event: x\nid: 7\ndata: line one\ndata: line two\n\n")
                .setHeader("Content-Type", "text/event-stream"))
        val frames = client.stream("/v1/events").toList()
        assertEquals(listOf(SseFrame("x", 7L, "line one\nline two")), frames)
    }

    companion object {
        // byte-identical to fixtures/v1/jobs.output.stream.txt
        private val OUTPUT_STREAM_FIXTURE = """retry: 3000

event: stdout
id: 4819
data: {"job_id":"J-1A2B","seq":4819,"text":"[download]   1.2% of ~123.45MiB at 1.23MiB/s ETA 01:39\n"}

event: stdout
id: 4820
data: {"job_id":"J-1A2B","seq":4820,"text":"[download]  42.1% of ~123.45MiB at 1.19MiB/s ETA 01:00\n"}

event: stderr
id: 4821
data: {"job_id":"J-1A2B","seq":4821,"text":"WARNING: fp parameter is deprecated\n"}

event: status
id: 4822
data: {"job_id":"J-1A2B","seq":4822,"status":"RUNNING","exit_code":null,"signal":null,"error":null,"ended_at":null,"progress_pct":42.1,"progress_detail":"[download]  42.1% of ~123.45MiB"}

: ping

event: stdout
id: 4823
data: {"job_id":"J-1A2B","seq":4823,"text":"[download] 100% of 123.45MiB in 06:51\n"}

event: status
id: 4824
data: {"job_id":"J-1A2B","seq":4824,"status":"COMPLETED","exit_code":0,"signal":null,"error":null,"ended_at":"2026-09-07T13:47:02.481Z","progress_pct":100.0,"progress_detail":"[download] 100% of 123.45MiB in 06:51"}
"""

        // byte-identical to fixtures/v1/events.stream.txt
        private val EVENTS_STREAM_FIXTURE = """retry: 3000

: ping

event: job.updated
id: 1041
data: {"job_id":"J-1A2C","name":"Nightly backup","type":"argv","argv":["tar","czf","~/backups/home-2026-09-07.tgz","~/projects"],"script":null,"cwd":"~","env":null,"status":"RUNNING","created_at":"2026-09-07T13:46:00.101Z","started_at":"2026-09-07T13:46:00.512Z","ended_at":null,"pid":12901,"pgid":12901,"exit_code":null,"signal":null,"cancel_requested":false,"cancel_reason":null,"error":null,"timeout_s":null,"progress_pct":null,"progress_detail":null,"stdout_bytes":0,"stderr_bytes":132,"log_seq":12,"log_truncated":false,"idempotency_key":null}

event: job.updated
id: 1042
data: {"job_id":"J-1A2B","name":"Download: Cats documentary","type":"tool","tool":"yt-dlp","argv":["yt-dlp","--newline","-f","mp4","-P","~/downloads","https://example.com/watch?v=xyz"],"script":null,"cwd":"~/downloads","env":null,"status":"COMPLETED","created_at":"2026-09-07T13:40:11.002Z","started_at":"2026-09-07T13:40:11.480Z","ended_at":"2026-09-07T13:47:02.481Z","pid":null,"pgid":null,"exit_code":0,"signal":null,"cancel_requested":false,"cancel_reason":null,"error":null,"timeout_s":7200,"progress_pct":100.0,"progress_detail":"[download] 100% of 123.45MiB in 06:51","stdout_bytes":20481,"stderr_bytes":0,"log_seq":4824,"log_truncated":false,"idempotency_key":"8f14e45f-6ea5-4bdc-b0b7-9b2f1c7d3e9a"}

event: bridge.stopping
id: 1043
data: {"reason":"restart","resume_hint_s":5}
"""
    }
}
