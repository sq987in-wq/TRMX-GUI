package dev.trmx.gui

import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolSchema
import dev.trmx.gui.tools.Artifact
import dev.trmx.gui.tools.Artifacts
import dev.trmx.gui.tools.ToolJobMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9.5: touched-state errors, x_trmx hints, artifact derivation. */
class PolishTest {

    // ---- touched-state errors ------------------------------------------------

    private val urlArg = ToolArg(name = "url", type = "url", required = true)

    @Test
    fun `errors stay invisible until the field is touched`() {
        val blank = dev.trmx.gui.tools.FieldValue()
        assertNull(dev.trmx.gui.tools.FormEngine.visibleError(urlArg, blank, touched = false))
        assertEquals("required",
                     dev.trmx.gui.tools.FormEngine.visibleError(urlArg, blank, touched = true))
        val bad = dev.trmx.gui.tools.FieldValue(text = "ftp://x")
        assertNull(dev.trmx.gui.tools.FormEngine.visibleError(urlArg, bad, touched = false))
        assertEquals("must be an http(s):// URL",
                     dev.trmx.gui.tools.FormEngine.visibleError(urlArg, bad, touched = true))
    }

    // ---- widget hints ----------------------------------------------------------

    @Test
    fun `widget selection honors x_trmx and sensible type defaults`() {
        val f = dev.trmx.gui.tools.FormEngine
        assertEquals("segmented",
                     f.widgetFor(ToolArg(name = "q", type = "enum", enum = listOf("a", "b"))))
        assertEquals("chips", f.widgetFor(ToolArg(name = "q", type = "enum",
                                                  enum = listOf("a", "b", "c", "d", "e"))))
        assertEquals("toggle", f.widgetFor(ToolArg(name = "b", type = "bool")))
        assertEquals("slider", f.widgetFor(ToolArg(name = "n", type = "int", min = 1.0, max = 9.0)))
        assertEquals("field", f.widgetFor(ToolArg(name = "n", type = "int", min = 0.0, max = 100000.0)))
        assertEquals("field", f.widgetFor(ToolArg(name = "s", type = "string")))
        // x_trmx.widget overrides everything
        assertEquals("textarea", f.widgetFor(ToolArg(
            name = "s", type = "string",
            x_trmx = dev.trmx.gui.model.XTrmx(widget = "textarea"))))
    }

    @Test
    fun `x_trmx survives schema decoding and defaults are sane`() {
        // single-line on purpose: the conformance suite treats multi-line
        // JSON raw strings as fixture candidates
        val schemaJson = "{\"id\":\"x\",\"name\":\"X\",\"binary\":\"x\"," +
            "\"risk_tier\":\"confirm\",\"args\":[" +
            "{\"name\":\"token\",\"type\":\"string\",\"x_trmx\":{\"secret\":true}}," +
            "{\"name\":\"size\",\"type\":\"int\",\"min\":1,\"max\":100," +
            "\"x_trmx\":{\"widget\":\"slider\",\"unit\":\"MiB\"}}," +
            "{\"name\":\"plain\",\"type\":\"string\"}]}"
        val schema = Json { ignoreUnknownKeys = true }.decodeFromString(
            dev.trmx.gui.model.ToolSchema.serializer(), schemaJson)
        val token = schema.args.first { it.name == "token" }
        assertTrue(token.x_trmx!!.secret)
        assertFalse(token.x_trmx!!.artifact)
        val size = schema.args.first { it.name == "size" }
        assertEquals("slider", size.x_trmx!!.widget)
        assertEquals("MiB", size.x_trmx!!.unit)
        assertNull(schema.args.first { it.name == "plain" }.x_trmx)   // optional, absent = null
    }

    // ---- artifact derivation ---------------------------------------------------

    private val schema = ToolSchema(
        id = "ffmpegish", name = "F", binary = "f",
        args = listOf(
            ToolArg(name = "input", type = "path", path_kind = "file"),
            ToolArg(name = "output", type = "path", path_kind = "file"),
            ToolArg(name = "outdir", type = "path", path_kind = "dir",
                    default = Json.parseToJsonElement("\"~/downloads\"")),
            ToolArg(name = "extra", type = "path", path_kind = "file",
                    x_trmx = dev.trmx.gui.model.XTrmx(artifact = true)),
        ))

    @Test
    fun `output args and x_trmx artifacts are exact outputs`() {
        assertEquals(listOf("output", "extra"), Artifacts.outputArgs(schema))
        val meta = Artifacts.metaFor(schema, mapOf(
            "output" to JsonPrimitive("~/out/a.mp4"),
            "outdir" to JsonPrimitive("~/videos"),
            "extra" to JsonPrimitive("~/out/extra.bin")))
        assertEquals(listOf("~/out/a.mp4", "~/out/extra.bin"), meta.outputs)
        assertEquals("~/videos", meta.outdir)
    }

    @Test
    fun `outdir falls back to the schema default`() {
        val meta = Artifacts.metaFor(schema, mapOf(
            "output" to JsonPrimitive("~/out/a.mp4")))
        assertEquals("~/downloads", meta.outdir)   // schema default, not sent
    }

    @Test
    fun `mtime heuristic compares at second precision`() {
        assertTrue(Artifacts.mtimeAtLeastAfter("2026-09-10T04:30:28Z",
                                               "2026-09-10T04:30:28.276Z"))
        assertFalse(Artifacts.mtimeAtLeastAfter("2026-09-10T04:30:27Z",
                                                "2026-09-10T04:30:28.276Z"))
        assertFalse(Artifacts.mtimeAtLeastAfter(null, "2026-09-10T04:30:28.276Z"))
    }

    @Test
    fun `collect merges exact outputs with detected files and dedupes`() {
        val meta = ToolJobMeta("ffmpegish", outputs = listOf("~/videos/a.mp4"),
                               outdir = "~/videos")
        val listing = listOf(
            FileEntry(name = "a.mp4", type = "file", size = 10, mtime = "2026-09-10T04:30:28Z"),
            FileEntry(name = "new.bin", type = "file", size = 20, mtime = "2026-09-10T04:31:00Z"),
            FileEntry(name = "old.txt", type = "file", size = 5, mtime = "2026-09-09T01:00:00Z"),
            FileEntry(name = "somedir", type = "dir", size = 4096, mtime = "2026-09-10T04:31:00Z"),
        )
        val arts = Artifacts.collect(meta, "2026-09-10T04:30:28.276Z", listing, "~/videos")
        assertEquals(2, arts.size)
        val exact = arts.first { it.exact }
        assertEquals("a.mp4", exact.name)
        assertEquals("~/videos/a.mp4", exact.path)
        assertNull(exact.size)                     // exact outputs don't need a stat
        val detected = arts.first { !it.exact }
        assertEquals("new.bin", detected.name)
        assertEquals("~/videos/new.bin", detected.path)
        assertEquals(20L, detected.size)
        // old file and directory are not artifacts
    }
}
