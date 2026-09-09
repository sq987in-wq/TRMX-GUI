package dev.trmx.gui

import dev.trmx.gui.model.ToolSubmitRequest
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Phase 9 wire tests: /v1/tools against MockWebServer. The tool-submit body
 * is asserted JSON-equal to the normative fixture (fixtures/v1/
 * jobs.submit.request.tool.json).
 */
class ToolsWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    private val toolsListBody = """
{
  "tools": [
    { "schema": { "id": "yt-dlp", "name": "Video Downloader (yt-dlp)",
                  "description": "Download videos from thousands of sites.",
                  "binary": "yt-dlp", "risk_tier": "safe",
                  "fixed_argv": ["--newline"],
                  "args": [ { "name": "url", "label": "Video URL", "type": "url", "required": true } ],
                  "examples": [] },
      "installed": true, "version": "yt-dlp 2026.01.01" },
    { "schema": { "id": "ffmpeg", "name": "Convert / Transcode (ffmpeg)",
                  "description": "Re-encode video and audio files.",
                  "binary": "ffmpeg", "risk_tier": "confirm", "args": [] },
      "installed": false, "version": null }
  ]
}
"""

    // byte-identical to fixtures/v1/jobs.submit.request.tool.json
    private val toolFixture = """
{
  "name": "Download: Cats documentary",
  "type": "tool",
  "tool": "yt-dlp",
  "args": {
    "url": "https://example.com/watch?v=xyz",
    "format": "mp4",
    "outdir": "~/downloads"
  },
  "cwd": "~",
  "env": null,
  "timeout_s": 7200,
  "idempotency_key": "8f14e45f-6ea5-4bdc-b0b7-9b2f1c7d3e9a"
}
"""

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
    fun `list parses the registry shape`() = runTest {
        server.enqueue(MockResponse().setBody(toolsListBody)
            .setHeader("Content-Type", "application/json"))
        val r = client.listTools()
        assertTrue("expected success, got $r", r is BridgeResult.Success)
        val tools = (r as BridgeResult.Success).data.tools
        assertEquals(2, tools.size)
        val ytdlp = tools.first { it.schema.id == "yt-dlp" }
        assertTrue(ytdlp.installed)
        assertEquals("yt-dlp 2026.01.01", ytdlp.version)
        assertEquals("safe", ytdlp.schema.risk_tier)
        assertEquals(1, ytdlp.schema.args.size)
        val ffmpeg = tools.first { it.schema.id == "ffmpeg" }
        assertEquals(false, ffmpeg.installed)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/tools", req.path)
        assertEquals("1", req.getHeader("X-TRMX-Protocol"))
    }

    @Test
    fun `refresh POSTs an empty body`() = runTest {
        server.enqueue(MockResponse().setBody(toolsListBody)
            .setHeader("Content-Type", "application/json"))
        val r = client.refreshTools()
        assertTrue(r is BridgeResult.Success)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/tools/refresh", req.path)
        assertEquals("{}", req.body.readUtf8())
    }

    @Test
    fun `tool submit body matches the normative fixture`() = runTest {
        server.enqueue(MockResponse().setBody("""{"job_id": "J-1", "status": "QUEUED"}""")
            .setHeader("Content-Type", "application/json")
            .setHeader("Idempotent-Replay", "true"))
        val request = ToolSubmitRequest(
            name = "Download: Cats documentary",
            tool = "yt-dlp",
            args = mapOf(
                "url" to JsonPrimitive("https://example.com/watch?v=xyz"),
                "format" to JsonPrimitive("mp4"),
                "outdir" to JsonPrimitive("~/downloads")),
            cwd = "~",
            env = null,
            timeout_s = 7200,
            idempotency_key = "8f14e45f-6ea5-4bdc-b0b7-9b2f1c7d3e9a")
        val r = client.submitToolJob(request)
        assertTrue(r is BridgeResult.Success)
        val out = r as BridgeResult.Success
        assertEquals("J-1", out.data.response.job_id)
        assertTrue(out.data.replayed)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/jobs", req.path)
        val sent = BridgeClient.jsonFormat.parseToJsonElement(req.body.readUtf8())
        val expected = Json.parseToJsonElement(toolFixture)
        assertEquals(expected, sent)
    }

    @Test
    fun `arg-invalid surfaces as a typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"ARG_INVALID","message":"arg 'url': must be an http(s):// URL","field":"url"}}""")
            .setHeader("Content-Type", "application/json"))
        val r = client.submitToolJob(ToolSubmitRequest(
            name = "x", tool = "yt-dlp", args = mapOf("url" to JsonPrimitive("nope"))))
        assertTrue(r is BridgeResult.HttpError)
        val err = r as BridgeResult.HttpError
        assertEquals(400, err.status)
        assertEquals("ARG_INVALID", err.code)
    }
}
