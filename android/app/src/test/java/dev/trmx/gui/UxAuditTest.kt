package dev.trmx.gui

import dev.trmx.gui.files.FilesFilter
import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolSchema
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.FormEngine
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-audit P0 regression tests: wire typing (the "must be an integer"
 * bug — int/float args MUST be native JSON numbers, §7.2), legacy
 * string-encoded payloads, locale-stable slider text, dotfile filtering.
 */
class UxAuditTest {

    private val schema = ToolSchema(
        id = "enc", name = "Encoder", binary = "ffmpeg",
        args = listOf(
            ToolArg(name = "url", type = "url", required = true),
            ToolArg(name = "crf", type = "int", min = 0.0, max = 51.0,
                    default = JsonPrimitive(23)),
            ToolArg(name = "fps", type = "float", min = 0.0, max = 240.0),
            ToolArg(name = "note", type = "string"),
        ))

    // ---- P0: native numbers on the wire ----------------------------------

    @Test
    fun `int and float args are sent as native JSON numbers`() {
        val values = mapOf(
            "url" to FieldValue(text = "https://x.example/a"),
            "crf" to FieldValue(text = "23"),          // untouched default
            "fps" to FieldValue(text = "29.97"),
        )
        val payload = FormEngine.argsPayload(schema, values)
        // JsonPrimitive(23).toString() == "23" (no quotes) — a string would
        // be "\"23\"", which the bridge's isinstance(int) check rejects.
        assertEquals("23", payload["crf"].toString())
        assertEquals("29.97", payload["fps"].toString())
        assertTrue(payload["crf"] is JsonPrimitive &&
                   !(payload["crf"] as JsonPrimitive).isString)
        assertTrue(payload["fps"] is JsonPrimitive &&
                   !(payload["fps"] as JsonPrimitive).isString)
    }

    @Test
    fun `unparseable numerics stay loud on the wire as strings`() {
        val payload = FormEngine.argsPayload(
            schema, mapOf("url" to FieldValue(text = "https://x.example/a"),
                          "crf" to FieldValue(text = "high")))
        // not silently coerced to 0 — the bridge rejects it, as it should
        assertEquals("\"high\"", payload["crf"].toString())
    }

    // ---- P0: legacy payloads (chains/recipes saved as strings) -----------

    @Test
    fun `legacy string-encoded int and float args are re-typed`() {
        val legacy = mapOf(
            "url" to JsonPrimitive("https://x.example/a"),
            "crf" to JsonPrimitive("23"),              // string, pre-fix shape
            "fps" to JsonPrimitive("29.97"),
            "note" to JsonPrimitive("keep me"),
        )
        val fixed = FormEngine.coerceLegacyArgs(schema, legacy)
        assertEquals("23", fixed["crf"].toString())
        assertFalse((fixed["crf"] as JsonPrimitive).isString)
        assertEquals("29.97", fixed["fps"].toString())
        assertFalse((fixed["fps"] as JsonPrimitive).isString)
        // strings stay strings, unknown keys pass through untouched
        assertEquals("\"keep me\"", fixed["note"].toString())
        assertEquals("7", FormEngine.coerceLegacyArgs(
            schema, mapOf("alien" to JsonPrimitive(7)))["alien"].toString())
        // unparseable strings are left for the bridge to reject loudly
        assertEquals("\"nope\"", FormEngine.coerceLegacyArgs(
            schema, mapOf("crf" to JsonPrimitive("nope")))["crf"].toString())
        // already-native numbers pass through
        assertEquals("23", FormEngine.coerceLegacyArgs(
            schema, mapOf("crf" to JsonPrimitive(23)))["crf"].toString())
    }

    // ---- P0: locale-stable slider text -----------------------------------

    @Test
    fun `slider text is locale stable for int and float`() {
        assertEquals("23", FormEngine.sliderValueText("int", 23.0f))
        assertEquals("12.50", FormEngine.sliderValueText("float", 12.5f))
        assertEquals("12.50", FormEngine.sliderValueText("float", 12.499999f))
    }

    // ---- P0: dotfiles default-hidden --------------------------------------

    private fun entry(name: String, type: String = "file") =
        FileEntry(name = name, type = type, size = 1)

    @Test
    fun `dotfiles are hidden by default and shown with the toggle`() {
        val entries = listOf(
            entry(".trmx"), entry("downloads", "dir"),
            entry(".bashrc"), entry("video.mp4"), entry(".hidden-dir", "dir"),
        )
        val hidden = FilesFilter.visible(entries, showHidden = false)
        assertEquals(listOf("downloads", "video.mp4"), hidden.map { it.name })
        assertEquals(entries, FilesFilter.visible(entries, showHidden = true))
    }

    @Test
    fun `display sort puts folders first, then alphabetical`() {
        val entries = listOf(
            entry("video.mp4"), entry("downloads", "dir"), entry("abc.txt"),
            entry("Archive", "dir"), entry("zz-dir", "dir"),
        )
        val sorted = FilesFilter.sortForDisplay(entries)
        // folders first (case-insensitive alpha), then files (alpha)
        assertEquals(listOf("Archive", "downloads", "zz-dir", "abc.txt", "video.mp4"),
                     sorted.map { it.name })
    }
}
