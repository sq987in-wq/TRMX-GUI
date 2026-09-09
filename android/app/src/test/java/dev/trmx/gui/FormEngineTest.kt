package dev.trmx.gui

import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolSchema
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.FormEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 9 form engine: validation + argv preview + payload must mirror the
 * bridge's §7.2 rules (the bridge re-validates; this is the UX copy).
 */
class FormEngineTest {

    private val schema = ToolSchema(
        id = "t", name = "T", binary = "t",
        fixed_argv = listOf("--newline"),
        args = listOf(
            ToolArg(name = "url", label = "URL", type = "url", required = true),
            ToolArg(name = "format", label = "Quality", type = "enum",
                    enum = listOf("mp4", "mkv", "best"), default = Json.parseToJsonElement("\"mp4\""),
                    argv = listOf("-f", "{value}")),
            ToolArg(name = "audio", label = "Audio only", type = "bool",
                    default = Json.parseToJsonElement("false"), argv = listOf("-x")),
            ToolArg(name = "rate", label = "Rate", type = "string",
                    pattern = "^\\d+[KM]$", argv = listOf("-r", "{value}")),
            ToolArg(name = "tries", label = "Tries", type = "int", min = 1.0, max = 9.0,
                    argv = listOf("-t", "{value}")),
            ToolArg(name = "outdir", label = "Out", type = "path", path_kind = "dir",
                    argv = listOf("-P", "{value}")),
        ))

    @Test
    fun `initial values come from schema defaults`() {
        val v = FormEngine.initialValues(schema)
        assertEquals("mp4", v["format"]!!.text)
        assertEquals(false, v["audio"]!!.bool)
        assertEquals("", v["url"]!!.text)
    }

    @Test
    fun `validation mirrors the bridge rules`() {
        val a = schema.args.associateBy { it.name }
        assertNull(FormEngine.validate(a["url"]!!, FieldValue(text = "https://x.example/a")))
        assertEquals("required", FormEngine.validate(a["url"]!!, FieldValue()))
        assertEquals("must be an http(s):// URL",
                     FormEngine.validate(a["url"]!!, FieldValue(text = "ftp://x")))
        assertEquals("must be one of [mp4, mkv, best]",
                     FormEngine.validate(a["format"]!!, FieldValue(text = "avi")))
        assertEquals("must match ^\\d+[KM]$",
                     FormEngine.validate(a["rate"]!!, FieldValue(text = "500X")))
        assertEquals("must be an integer",
                     FormEngine.validate(a["tries"]!!, FieldValue(text = "three")))
        assertEquals("must be ≤ 9",
                     FormEngine.validate(a["tries"]!!, FieldValue(text = "99")))
        assertNull(FormEngine.validate(a["rate"]!!, FieldValue()))      // optional blank = fine
        assertNull(FormEngine.validate(a["audio"]!!, FieldValue(bool = true)))
    }

    @Test
    fun `preview argv mirrors synthesis order and bool semantics`() {
        val v = mapOf(
            "url" to FieldValue(text = "https://x.example/a"),
            "format" to FieldValue(text = "mkv"),
            "audio" to FieldValue(bool = false),        // -> contributes nothing
            "tries" to FieldValue(text = "3"),
        ) + FormEngine.initialValues(schema).filterKeys { it !in setOf("url", "format", "audio", "tries") }
        val argv = FormEngine.previewArgv(schema, v)
        assertEquals(listOf("t", "--newline", "https://x.example/a", "-f", "mkv", "-t", "3"), argv)
        val argv2 = FormEngine.previewArgv(schema, v + ("audio" to FieldValue(bool = true)))
        assertTrue(argv2.contains("-x"))
    }

    @Test
    fun `args payload carries only effective values and true bools`() {
        val v = mapOf(
            "url" to FieldValue(text = "https://x.example/a"),
            "format" to FieldValue(text = "best"),
            "audio" to FieldValue(bool = false),
        ) + FormEngine.initialValues(schema).filterKeys { it !in setOf("url", "format", "audio") }
        val payload = FormEngine.argsPayload(schema, v)
        // this test schema has NO outdir default (unlike the real yt-dlp
        // fixture), so a blank outdir is rightly omitted
        assertEquals(setOf("url", "format"), payload.keys)
        assertEquals("\"best\"", payload["format"].toString())
        assertFalse(payload.containsKey("audio"))
        assertTrue(FormEngine.isSubmittable(schema, v))
        assertFalse(FormEngine.isSubmittable(schema, v - "url"))
    }
}
