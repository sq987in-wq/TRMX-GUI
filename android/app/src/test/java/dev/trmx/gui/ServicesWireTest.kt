package dev.trmx.gui

import dev.trmx.gui.model.ServiceAck
import dev.trmx.gui.model.ServiceDefRequest
import dev.trmx.gui.model.ServiceStatus
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
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
 * Protocol 1.1 wire tests: /v1/services against MockWebServer. The list
 * payload is byte-identical to fixtures/v1/services.list.response.json and
 * the create body is JSON-equal to fixtures/v1/services.def.request.json.
 */
class ServicesWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    // byte-identical to fixtures/v1/services.list.response.json
    private val listFixture = """
{
  "services": [
    {
      "id": "web-server",
      "name": "Local HTTP Server",
      "tool": "http-server",
      "args": { "port": 8000, "dir": "~" },
      "autostart": true,
      "created_at": "2026-09-11T10:00:00Z",
      "state": "running",
      "job_id": "J-4",
      "last_job_id": "J-4",
      "last_status": "RUNNING",
      "last_exit_code": null
    },
    {
      "id": "media-fetch",
      "name": "Media Fetch",
      "tool": "yt-dlp",
      "args": { "url": "https://example.com/watch?v=xyz", "outdir": "~/downloads" },
      "autostart": false,
      "created_at": "2026-09-11T11:30:00Z",
      "state": "stopped",
      "job_id": null,
      "last_job_id": "J-7",
      "last_status": "COMPLETED",
      "last_exit_code": 0
    }
  ]
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

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body)
            .setHeader("Content-Type", "application/json"))
    }

    @Test
    fun `list parses the normative fixture`() = runTest {
        enqueue(listFixture)
        val r = client.listServices()
        assertTrue(r is BridgeResult.Success)
        val out = r as BridgeResult.Success
        assertEquals(2, out.data.services.size)
        val web = out.data.services[0]
        assertEquals("web-server", web.id)
        assertEquals("running", web.state)
        assertEquals("J-4", web.job_id)
        assertTrue(web.autostart)
        assertEquals(8000, web.args["port"]!!.jsonPrimitive.content.toInt())
        val fetch = out.data.services[1]
        assertEquals("stopped", fetch.state)
        assertNull(fetch.job_id)
        assertEquals(0, fetch.last_exit_code)
    }

    @Test
    fun `create body matches the normative fixture`() = runTest {
        enqueue("""{"id":"web-server","name":"Local HTTP Server","tool":"http-server","state":"stopped"}""")
        val r = client.createService(ServiceDefRequest(
            id = "web-server", name = "Local HTTP Server", tool = "http-server",
            args = mapOf(
                "port" to Json.parseToJsonElement("8000"),
                "dir" to Json.parseToJsonElement("\"~\""))))
        assertTrue(r is BridgeResult.Success)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/services", req.path)
        val sent = BridgeClient.jsonFormat.parseToJsonElement(req.body.readUtf8())
        // byte-identical to fixtures/v1/services.def.request.json
        val expected = Json.parseToJsonElement("""
{
  "id": "web-server",
  "name": "Local HTTP Server",
  "tool": "http-server",
  "args": { "port": 8000, "dir": "~" },
  "autostart": false
}
""")
        assertEquals(expected, sent)
    }

    @Test
    fun `start stop restart and autostart hit their routes and parse status`() = runTest {
        val status = """{"id":"web-server","name":"Local HTTP Server","tool":"http-server","state":"running","job_id":"J-9","autostart":true}"""
        enqueue(status)
        assertTrue(client.startService("web-server") is BridgeResult.Success)
        enqueue(status)
        assertTrue(client.stopService("web-server") is BridgeResult.Success)
        enqueue(status)
        assertTrue(client.restartService("web-server") is BridgeResult.Success)
        enqueue("""{"id":"web-server","name":"Local HTTP Server","tool":"http-server","state":"stopped","autostart":true}""")
        val r = client.setServiceAutostart("web-server", true)
        assertTrue(r is BridgeResult.Success)
        assertTrue((r as BridgeResult.Success).data.autostart)

        var req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/services/web-server/start", req.path)
        req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/services/web-server/stop", req.path)
        req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/services/web-server/restart", req.path)
        req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/services/web-server/autostart", req.path)
        // autostart body carries the flag
        assertEquals("""{"enabled":true}""",
                     BridgeClient.jsonFormat.parseToJsonElement(req.body.readUtf8()).toString())
    }

    @Test
    fun `delete parses the ack`() = runTest {
        enqueue("""{"ok":true,"id":"web-server"}""")
        val r = client.deleteService("web-server")
        assertTrue(r is BridgeResult.Success)
        val out = r as BridgeResult.Success
        assertTrue(out.data.ok)
        assertEquals("web-server", out.data.id)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", req.method)
        assertEquals("/v1/services/web-server", req.path)
    }

    @Test
    fun `service-running surfaces as a typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"error":{"code":"SERVICE_RUNNING","message":"service 'web-server' is already running (job J-9)"}}""")
            .setHeader("Content-Type", "application/json"))
        val r = client.startService("web-server")
        assertTrue(r is BridgeResult.HttpError)
        val err = r as BridgeResult.HttpError
        assertEquals(409, err.status)
        assertEquals("SERVICE_RUNNING", err.code)
    }

    @Test
    fun `service-status decodes the delete event payload`() {
        // protocol 1.1 event: {"id": "...", "deleted": true}
        val ev = BridgeClient.jsonFormat.decodeFromString(
            ServiceStatus.serializer(),
            """{"id":"web-server","deleted":true}""")
        assertTrue(ev.deleted)
        assertEquals("web-server", ev.id)
    }
}
