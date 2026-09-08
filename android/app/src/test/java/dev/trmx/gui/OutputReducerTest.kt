package dev.trmx.gui

import dev.trmx.gui.job.OutputReducer
import dev.trmx.gui.net.SseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputReducerTest {

    private val r = OutputReducer

    @Test
    fun `text frames become console lines and advance lastSeq`() {
        var s = r.initial()
        s = r.apply(s, SseFrame("stdout", 1L, """{"job_id":"J-1","seq":1,"text":"hello\n"}"""))
        s = r.apply(s, SseFrame("stderr", 2L, """{"job_id":"J-1","seq":2,"text":"warn\n"}"""))
        assertEquals(2, s.lines.size)
        assertEquals("hello\n", s.lines[0].text)
        assertTrue(s.lines[0].kind == "stdout" && !s.lines[0].isStderr)
        assertTrue(s.lines[1].isStderr)
        assertEquals(2L, s.lastSeq)
        assertFalse(s.ended)
    }

    @Test
    fun `status frames render as marker lines and terminal status ends the stream`() {
        var s = r.initial()
        s = r.apply(s, SseFrame("status", 5L,
            """{"job_id":"J-1","seq":5,"status":"RUNNING","exit_code":null}"""))
        assertFalse(s.ended)
        s = r.apply(s, SseFrame("status", 6L,
            """{"job_id":"J-1","seq":6,"status":"COMPLETED","exit_code":0}"""))
        assertTrue(s.ended)
        assertEquals("— COMPLETED (exit 0) —", s.lines.last().text)
        assertEquals(6L, s.lastSeq)
    }

    @Test
    fun `evicted info frames set the honesty flag`() {
        var s = r.initial()
        s = r.apply(s, SseFrame("info", 3L,
            """{"job_id":"J-1","seq":3,"type":"evicted","resume_from_seq":4}"""))
        assertTrue(s.evicted)
        assertEquals(0, s.lines.size)   // info frames are not console lines
    }

    @Test
    fun `unknown event types are ignored, not fatal`() {
        var s = r.initial()
        s = r.apply(s, SseFrame("future-thing", 9L, """{"whatever":1}"""))
        assertEquals(0, s.lines.size)
        assertEquals(9L, s.lastSeq)   // id still advances resume bookkeeping
    }

    @Test
    fun `malformed data does not crash - frame is dropped`() {
        var s = r.initial()
        s = r.apply(s, SseFrame("stdout", 4L, "not json at all"))
        assertEquals(0, s.lines.size)
        assertEquals(4L, s.lastSeq)
    }

    @Test
    fun `line budget is enforced from the front`() {
        var s = r.initial()
        repeat(1200) { n ->
            s = r.apply(s, SseFrame("stdout", (n + 1).toLong(),
                """{"job_id":"J-1","seq":${n + 1},"text":"line $n\n"}"""))
        }
        assertTrue(s.lines.size <= OutputReducer.MAX_LINES)
        assertEquals(1200L, s.lastSeq)
        // oldest lines were dropped, newest kept
        assertTrue(s.lines.first().seq > 1)
        assertTrue(s.lines.last().text.contains("line 1199"))
    }
}
