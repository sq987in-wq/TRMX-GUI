package dev.trmx.gui

import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Data-plane client against a real local HTTP server (MockWebServer).
 * The embedded payloads are byte-identical copies of the normative
 * fixtures in fixtures/v1/ — tests/spec_conformance.py verifies that
 * they stay in sync with the fixture files.
 */
class BridgeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BridgeClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "test-token-0123456789abcdefghijklmnop")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `system info handshake sends both required headers and parses the fixture`() = runTest {
        server.enqueue(MockResponse().setBody(SYSTEM_INFO_FIXTURE).setHeader("Content-Type", "application/json"))
        val r = client.systemInfo()
        assertTrue("expected success, got $r", r is BridgeResult.Success)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/system/info", req.path)
        assertEquals("Bearer test-token-0123456789abcdefghijklmnop",
                     req.getHeader("Authorization"))
        assertEquals("1", req.getHeader("X-TRMX-Protocol"))
        assertEquals(null, req.getHeader("Origin"))  // would be ORIGIN_DENIED

        val info = (r as BridgeResult.Success).data
        assertEquals("1.0.0", info.bridge_version)
        assertEquals(listOf(1), info.protocol_versions)
        assertEquals(2, info.load!!.jobs_running)
        assertEquals(7420L, info.memory!!.total_mb)
        assertEquals(10240L, info.storage!!.home_free_mb)
        assertEquals(4, info.caps!!.max_concurrent_jobs)
        assertTrue(info.features!!.runit)
    }

    @Test
    fun `job list parses the fixture`() = runTest {
        server.enqueue(MockResponse().setBody(JOBS_LIST_FIXTURE).setHeader("Content-Type", "application/json"))
        val r = client.listJobs()
        assertTrue(r is BridgeResult.Success)
        val page = (r as BridgeResult.Success).data
        assertEquals(2, page.jobs.size)
        assertEquals("J-1A2B", page.jobs[0].job_id)
        assertEquals("COMPLETED", page.jobs[0].status)
        assertEquals(0L, page.jobs[0].exit_code)
        assertEquals("RUNNING", page.jobs[1].status)
        assertEquals(12901L, page.jobs[1].pid)
        assertNull(page.next_cursor)
    }

    @Test
    fun `auth failure is surfaced as HttpError with the protocol error code`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody(ERROR_FIXTURE)
                .setHeader("Content-Type", "application/json"))
        val r = client.systemInfo()
        assertTrue(r is BridgeResult.HttpError)
        val err = r as BridgeResult.HttpError
        assertEquals(401, err.status)
        assertEquals("ARG_INVALID", err.code)
    }

    @Test
    fun `connection refused is a NetworkError not a crash`() = runTest {
        val port = server.port
        server.shutdown()
        val dead = BridgeClient(baseUrl = "http://127.0.0.1:$port", token = "t")
        val r = dead.systemInfo()
        assertTrue(r is BridgeResult.NetworkError)
    }

    companion object {
        // byte-identical to fixtures/v1/system.info.response.json
        private val SYSTEM_INFO_FIXTURE = """
{
  "bridge_version": "1.0.0",
  "protocol_versions": [1],
  "uptime_s": 3600,
  "now": "2026-09-07T13:47:02.481Z",
  "load": { "jobs_running": 2, "jobs_queued": 0, "loadavg": [0.42, 0.35, 0.31] },
  "memory": { "total_mb": 7420, "free_mb": 2180 },
  "storage": { "home_free_mb": 10240, "shared_available": true },
  "termux": { "home": "/data/data/com.termux/files/home", "prefix": "/data/data/com.termux/files/usr" },
  "caps": { "max_concurrent_jobs": 4, "queue_depth": 32, "log_ring_mb": 2 },
  "features": { "termux_api": true, "runit": true, "scheduler": false },
  "tools_detected": 17
}
"""

        // byte-identical to fixtures/v1/jobs.list.response.json
        private val JOBS_LIST_FIXTURE = """
{
  "jobs": [
    {
      "job_id": "J-1A2B",
      "name": "Download: Cats documentary",
      "type": "tool",
      "tool": "yt-dlp",
      "argv": ["yt-dlp", "--newline", "-f", "mp4", "-P", "~/downloads", "https://example.com/watch?v=xyz"],
      "script": null,
      "cwd": "~/downloads",
      "env": null,
      "status": "COMPLETED",
      "created_at": "2026-09-07T13:40:11.002Z",
      "started_at": "2026-09-07T13:40:11.480Z",
      "ended_at": "2026-09-07T13:47:02.481Z",
      "pid": null,
      "pgid": null,
      "exit_code": 0,
      "signal": null,
      "cancel_requested": false,
      "cancel_reason": null,
      "error": null,
      "timeout_s": 7200,
      "progress_pct": 100.0,
      "progress_detail": "[download] 100% of 123.45MiB in 06:51",
      "stdout_bytes": 20481,
      "stderr_bytes": 0,
      "log_seq": 4823,
      "log_truncated": false,
      "idempotency_key": "8f14e45f-6ea5-4bdc-b0b7-9b2f1c7d3e9a"
    },
    {
      "job_id": "J-1A2C",
      "name": "Nightly backup",
      "type": "argv",
      "argv": ["tar", "czf", "~/backups/home-2026-09-07.tgz", "~/projects"],
      "script": null,
      "cwd": "~",
      "env": null,
      "status": "RUNNING",
      "created_at": "2026-09-07T13:46:00.101Z",
      "started_at": "2026-09-07T13:46:00.512Z",
      "ended_at": null,
      "pid": 12901,
      "pgid": 12901,
      "exit_code": null,
      "signal": null,
      "cancel_requested": false,
      "cancel_reason": null,
      "error": null,
      "timeout_s": null,
      "progress_pct": null,
      "progress_detail": null,
      "stdout_bytes": 0,
      "stderr_bytes": 132,
      "log_seq": 12,
      "log_truncated": false,
      "idempotency_key": null
    }
  ],
  "next_cursor": null
}
"""

        // byte-identical to fixtures/v1/error.response.json
        private val ERROR_FIXTURE = """
{
  "error": {
    "code": "ARG_INVALID",
    "message": "Value 'mp9' is not one of the allowed enum values.",
    "field": "args.format",
    "details": { "allowed": ["mp4", "mkv", "best"] }
  }
}
"""
    }
}
