package dev.trmx.gui

import dev.trmx.gui.job.SubmitValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitValidatorTest {

    @Test
    fun `minimal valid job passes`() {
        val errors = SubmitValidator.validate(
            name = "", argv = listOf("echo", "hi"), timeoutS = null, cwd = "")
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `argv is required`() {
        val errors = SubmitValidator.validate("", emptyList(), null, "")
        assertEquals(1, errors.size)
        assertEquals("argv", errors[0].field)
    }

    @Test
    fun `argv length caps are mirrored`() {
        val tooMany = (1..257).map { "a" }
        assertTrue(SubmitValidator.validate("", tooMany, null, "")
            .any { it.field == "argv" && it.message.contains("256") })

        val tooLongArg = listOf("x".repeat(8193))
        assertTrue(SubmitValidator.validate("", tooLongArg, null, "")
            .any { it.field == "argv" && it.message.contains("8192") })

        val tooMuchTotal = (1..20).map { "x".repeat(4000) }   // 80_000 > 65_536
        assertTrue(SubmitValidator.validate("", tooMuchTotal, null, "")
            .any { it.field == "argv" && it.message.contains("total") })
    }

    @Test
    fun `name cap is mirrored`() {
        val errors = SubmitValidator.validate("n".repeat(201), listOf("ls"), null, "")
        assertEquals(1, errors.size)
        assertEquals("name", errors[0].field)
    }

    @Test
    fun `timeout bounds are mirrored`() {
        assertEquals(1, SubmitValidator.validate("", listOf("ls"), 0L, "").size)
        assertEquals(1, SubmitValidator.validate("", listOf("ls"), 2_592_001L, "").size)
        assertTrue(SubmitValidator.validate("", listOf("ls"), 2_592_000L, "").isEmpty())
        assertTrue(SubmitValidator.validate("", listOf("ls"), null, "").isEmpty())
    }

    @Test
    fun `argv text is line-based - no shell parsing`() {
        assertEquals(
            listOf("ffmpeg", "-i", "file with spaces.mkv", "~/out.mp4"),
            SubmitValidator.parseArgvText("ffmpeg\n-i\nfile with spaces.mkv\n~/out.mp4\n\n"))
        // quotes are NOT syntax — they stay part of the argument
        assertEquals(
            listOf("echo", "\"hello\""),
            SubmitValidator.parseArgvText("echo\n\"hello\""))
        // CRLF tolerated
        assertEquals(listOf("a", "b"), SubmitValidator.parseArgvText("a\r\nb"))
    }
}
