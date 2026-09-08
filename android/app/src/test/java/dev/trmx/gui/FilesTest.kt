package dev.trmx.gui

import dev.trmx.gui.model.FileListResponse
import dev.trmx.gui.model.FileOpRequest
import dev.trmx.gui.model.FileOpResponse
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase 7 wire tests: list/op/download/upload against MockWebServer, with
 * byte-identical copies of fixtures/v1/files.* (conformance-checked).
 */
class FilesTest {

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
    fun `list parses the normative fixture - entries symlink and all`() = runTest {
        server.enqueue(MockResponse().setBody(FILES_LIST_FIXTURE)
            .setHeader("Content-Type", "application/json"))
        val r = client.listFiles("~/downloads")
        assertTrue("expected success, got $r", r is BridgeResult.Success)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/files?path=~%2Fdownloads&offset=0", req.path)
        assertEquals("1", req.getHeader("X-TRMX-Protocol"))

        val page = (r as BridgeResult.Success).data
        assertEquals("~/downloads", page.path)
        assertEquals(3, page.entries.size)
        val video = page.entries.first { it.name == "video.mp4" }
        assertEquals("file", video.type)
        assertEquals(104857600L, video.size)
        assertEquals("-rw-r--r--", video.mode)
        val music = page.entries.first { it.name == "music" }
        assertEquals("dir", music.type)
        val link = page.entries.first { it.name == "latest.mkv" }
        assertEquals("symlink", link.type)
        assertEquals("~/downloads/music/latest.mkv", link.target)
    }

    @Test
    fun `op request body matches the normative delete fixture`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok": true}""")
            .setHeader("Content-Type", "application/json"))
        val request = BridgeClient.jsonFormat.decodeFromString(
            FileOpRequest.serializer(), FILES_OPS_FIXTURE)
        val r = client.fileOp(request)
        assertTrue(r is BridgeResult.Success)
        assertTrue((r as BridgeResult.Success).data.ok)

        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/files", req.path)
        val sent = BridgeClient.jsonFormat.parseToJsonElement(req.body.readUtf8())
        val expected = BridgeClient.jsonFormat.parseToJsonElement(FILES_OPS_FIXTURE)
        assertEquals(expected, sent)
    }

    @Test
    fun `confirm-required surfaces as a typed error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"CONFIRM_REQUIRED","message":"recursive delete of a directory requires confirm:true"}}""")
            .setHeader("Content-Type", "application/json"))
        val r = client.fileOp(FileOpRequest(op = "delete", path = "~/x", recursive = true))
        assertTrue(r is BridgeResult.HttpError)
        val err = r as BridgeResult.HttpError
        assertEquals(400, err.status)
        assertEquals("CONFIRM_REQUIRED", err.code)
    }

    @Test
    fun `download streams the body to a file`() = runTest {
        server.enqueue(MockResponse().setBody("file-contents-123")
            .setHeader("Content-Type", "application/octet-stream")
            .setHeader("Content-Length", "17"))
        val tmp = File.createTempFile("trmx-dl", ".bin")
        try {
            val r = client.downloadFile("~/downloads/video.mp4", tmp)
            assertTrue(r is BridgeResult.Success)
            assertEquals(17L, (r as BridgeResult.Success).data)
            assertEquals("file-contents-123", tmp.readText())
            val req = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/files/content?path=~%2Fdownloads%2Fvideo.mp4", req.path)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `download not-a-file maps to the protocol error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"NOT_A_FILE","message":"cannot download a directory"}}""")
            .setHeader("Content-Type", "application/json"))
        val tmp = File.createTempFile("trmx-dl", ".bin")
        try {
            val r = client.downloadFile("~/music", tmp)
            assertTrue(r is BridgeResult.HttpError)
            assertEquals("NOT_A_FILE", (r as BridgeResult.HttpError).code)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `upload sends PUT with sha256 and the exact bytes`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok": true}""")
            .setHeader("Content-Type", "application/json"))
        val src = File.createTempFile("trmx-up", ".bin")
        try {
            src.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            val r = client.uploadFile("~/uploads/new.bin", src, overwrite = true)
            assertTrue("expected success, got $r", r is BridgeResult.Success)

            val req = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", req.method)
            assertEquals("/v1/files/content?path=~%2Fuploads%2Fnew.bin&overwrite=1", req.path)
            assertEquals(BridgeClient.sha256Of(src), req.getHeader("X-TRMX-Sha256"))
            assertEquals("5", req.getHeader("Content-Length"))
            assertTrue(req.body.readByteArray().contentEquals(byteArrayOf(1, 2, 3, 4, 5)))
        } finally {
            src.delete()
        }
    }

    @Test
    fun `upload path-exists maps to 409`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"error":{"code":"PATH_EXISTS","message":"already exists"}}""")
            .setHeader("Content-Type", "application/json"))
        val src = File.createTempFile("trmx-up", ".bin")
        try {
            src.writeBytes(byteArrayOf(9))
            val r = client.uploadFile("~/notes.txt", src, overwrite = false)
            assertTrue(r is BridgeResult.HttpError)
            assertEquals(409, (r as BridgeResult.HttpError).status)
            assertEquals("PATH_EXISTS", r.code)
        } finally {
            src.delete()
        }
    }

    companion object {
        // byte-identical to fixtures/v1/files.list.response.json
        private val FILES_LIST_FIXTURE = """
{
  "path": "~/downloads",
  "entries": [
    { "name": "video.mp4", "type": "file", "size": 104857600, "mtime": "2026-09-07T09:12:44Z", "mode": "-rw-r--r--", "target": null },
    { "name": "music", "type": "dir", "size": 4096, "mtime": "2026-09-06T18:02:10Z", "mode": "drwx------", "target": null },
    { "name": "latest.mkv", "type": "symlink", "size": 28, "mtime": "2026-09-06T18:02:10Z", "mode": "lrwxrwxrwx", "target": "~/downloads/music/latest.mkv" }
  ]
}
"""

        // byte-identical to fixtures/v1/files.ops.request.json
        private val FILES_OPS_FIXTURE = """
{
  "op": "delete",
  "path": "~/downloads/old-stuff",
  "recursive": true,
  "confirm": true
}
"""
    }
}
