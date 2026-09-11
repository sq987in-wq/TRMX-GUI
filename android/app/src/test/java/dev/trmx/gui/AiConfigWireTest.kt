package dev.trmx.gui

import dev.trmx.gui.model.AiCliUpdate
import dev.trmx.gui.model.AiConfig
import dev.trmx.gui.model.AiConfigUpdate
import dev.trmx.gui.model.AiHttpUpdate
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Protocol 1.1 wire tests: /v1/ai/config against MockWebServer. The GET
 * payload is byte-identical to fixtures/v1/ai.config.response.json; the
 * api_key tri-state (null=keep, ""=clear, value=set) is locked at the
 * encoding level.
 */
class AiConfigWireTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BridgeClient

    // byte-identical to fixtures/v1/ai.config.response.json
    private val configFixture = """
{
  "exists": true,
  "mode": "http_api",
  "cli": { "command": ["ollama", "run", "llama3.2"], "timeout_s": 180 },
  "http_api": {
    "provider": "groq",
    "endpoint": "",
    "model": "llama-3.3-70b-versatile",
    "api_key_set": true,
    "api_key_env": "GROQ_API_KEY",
    "timeout_s": 120
  }
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
    fun `get parses the normative fixture`() = runTest {
        enqueue(configFixture)
        val r = client.getAiConfig()
        assertTrue(r is BridgeResult.Success)
        val cfg = (r as BridgeResult.Success).data
        assertTrue(cfg.exists)
        assertEquals("http_api", cfg.mode)
        assertEquals(listOf("ollama", "run", "llama3.2"), cfg.cli?.command)
        val http = cfg.http_api!!
        assertEquals("groq", http.provider)
        assertEquals("llama-3.3-70b-versatile", http.model)
        assertTrue(http.api_key_set)
        assertEquals("GROQ_API_KEY", http.api_key_env)
        assertEquals(120, http.timeout_s)
    }

    @Test
    fun `update posts mode and provider with nulls meaning keep`() = runTest {
        enqueue(configFixture)
        val r = client.updateAiConfig(
            AiConfigUpdate(mode = "http_api",
                           http_api = AiHttpUpdate(provider = "groq",
                                                   model = "llama-3.3-70b-versatile")))
        assertTrue(r is BridgeResult.Success)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/ai/config", req.path)
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("http_api", sent["mode"]!!.jsonPrimitive.content)
        val http = sent["http_api"]!!.jsonObject
        assertEquals("groq", http["provider"]!!.jsonPrimitive.content)
        // tri-state: absent input encodes as null = KEEP the saved key
        assertEquals(JsonNull, http["api_key"])
        assertEquals(JsonNull, http["endpoint"])
    }

    @Test
    fun `clearing and setting the key encode distinctly`() = runTest {
        enqueue(configFixture)
        client.updateAiConfig(AiConfigUpdate(
            http_api = AiHttpUpdate(api_key = "")))          // clear
        var req = server.takeRequest(5, TimeUnit.SECONDS)!!
        var http = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["http_api"]!!.jsonObject
        assertEquals("", http["api_key"]!!.jsonPrimitive.content)

        enqueue(configFixture)
        client.updateAiConfig(AiConfigUpdate(
            http_api = AiHttpUpdate(api_key = "sk-new-key")))  // set
        req = server.takeRequest(5, TimeUnit.SECONDS)!!
        http = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["http_api"]!!.jsonObject
        assertEquals("sk-new-key", http["api_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cli command round-trips as a string list`() = runTest {
        enqueue(configFixture)
        client.updateAiConfig(AiConfigUpdate(
            mode = "cli",
            cli = AiCliUpdate(command = listOf("ollama", "run", "qwen2.5"))))
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val cli = sent["cli"]!!.jsonObject
        val cmd = cli["command"].toString()
        assertEquals("[\"ollama\",\"run\",\"qwen2.5\"]", cmd)
    }

    @Test
    fun `older bridge 404 surfaces as a typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"error":{"code":"NOT_FOUND","message":"no such route: /v1/ai/config"}}""")
            .setHeader("Content-Type", "application/json"))
        val r = client.getAiConfig()
        assertTrue(r is BridgeResult.HttpError)
        val err = r as BridgeResult.HttpError
        assertEquals(404, err.status)
        assertEquals("NOT_FOUND", err.code)
    }
}
