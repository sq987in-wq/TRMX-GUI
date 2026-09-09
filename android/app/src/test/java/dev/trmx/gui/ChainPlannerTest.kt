package dev.trmx.gui

import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolSchema
import dev.trmx.gui.tools.ChainDef
import dev.trmx.gui.tools.ChainPlanner
import dev.trmx.gui.tools.ChainStep
import dev.trmx.gui.tools.PREV_FILE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 chain planning (ADR-010): refs, outputs, validation. */
class ChainPlannerTest {

    private fun schema(id: String, hasOutput: Boolean) = ToolSchema(
        id = id, name = id, binary = id,
        args = if (hasOutput) listOf(
            ToolArg(name = "input", type = "path", path_kind = "file"),
            ToolArg(name = "output", type = "path", path_kind = "file", argv = listOf("-o", "{value}")),
        ) else listOf(
            ToolArg(name = "url", type = "url"),
            ToolArg(name = "outdir", type = "path", path_kind = "dir"),
        ))

    private val schemas = mapOf(
        "yt-dlp" to schema("yt-dlp", hasOutput = false),
        "ffmpeg" to schema("ffmpeg", hasOutput = true),
    )

    @Test
    fun `only output-file tools expose a chainable output`() {
        assertTrue(ChainPlanner.stepHasOutput(schemas["ffmpeg"]!!))
        assertEquals(false, ChainPlanner.stepHasOutput(schemas["yt-dlp"]!!))
        assertNull(ChainPlanner.outputArg(schemas["yt-dlp"]!!))
    }

    @Test
    fun `validate rejects empty, unknown tools and bad refs`() {
        val empty = ChainDef("c1", "empty")
        assertEquals(listOf("a chain needs at least one step"),
                     ChainPlanner.validate(empty, schemas))

        val unknown = ChainDef("c1", "u", steps = listOf(ChainStep("ghost")))
        assertTrue(ChainPlanner.validate(unknown, schemas)[0].contains("unknown tool 'ghost'"))

        val firstRef = ChainDef("c1", "f", steps = listOf(
            ChainStep("ffmpeg", args = mapOf("input" to JsonPrimitive(PREV_FILE),
                                             "output" to JsonPrimitive("~/o.mp4")))))
        assertTrue(ChainPlanner.validate(firstRef, schemas)[0].contains("cannot use $PREV_FILE"))

        val badPrev = ChainDef("c1", "b", steps = listOf(
            ChainStep("yt-dlp"),
            ChainStep("ffmpeg", args = mapOf("input" to JsonPrimitive(PREV_FILE)))))
        assertTrue(ChainPlanner.validate(badPrev, schemas).any { it.contains("produces no known output") })

        val good = ChainDef("c1", "g", steps = listOf(
            ChainStep("ffmpeg", args = mapOf("output" to JsonPrimitive("~/a.mp4"))),
            ChainStep("ffmpeg", args = mapOf("input" to JsonPrimitive(PREV_FILE),
                                             "output" to JsonPrimitive("~/b.mp4")))))
        assertTrue(ChainPlanner.validate(good, schemas).isEmpty())
    }

    @Test
    fun `resolveArgs substitutes refs and outputsUpTo walks them forward`() {
        val def = ChainDef("c1", "chain", steps = listOf(
            ChainStep("ffmpeg", args = mapOf("output" to JsonPrimitive("~/a.mp4"))),
            ChainStep("ffmpeg", args = mapOf("input" to JsonPrimitive("$PREV_FILE"),
                                             "output" to JsonPrimitive("~/b.mp4"))),
            ChainStep("ffmpeg", args = mapOf("input" to JsonPrimitive("~/x/$PREV_FILE"),
                                             "output" to JsonPrimitive("~/c.mp4"))),
        ))
        val resolved = ChainPlanner.resolveArgs(def.steps[1], "~/a.mp4")
        assertEquals("~/a.mp4", resolved["input"]!!.let { (it as JsonPrimitive).content })

        val outputs = ChainPlanner.outputsUpTo(def, schemas, 3)
        assertEquals(mapOf(0 to "~/a.mp4", 1 to "~/b.mp4"), outputs)
    }
}
