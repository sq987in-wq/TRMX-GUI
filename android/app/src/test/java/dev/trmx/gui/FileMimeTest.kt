package dev.trmx.gui

import dev.trmx.gui.files.FileMime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 8: extension → MIME mapping for open/share intents (ADR-008). */
class FileMimeTest {

    @Test
    fun `video types map correctly`() {
        assertEquals("video/mp4", FileMime.of("lecture.mp4"))
        assertEquals("video/x-matroska", FileMime.of("movie.mkv"))   // MimeTypeMap regularly lacks this
        assertEquals("video/webm", FileMime.of("clip.webm"))
    }

    @Test
    fun `audio image and document types map correctly`() {
        assertEquals("audio/flac", FileMime.of("song.flac"))
        assertEquals("image/jpeg", FileMime.of("photo.jpg"))
        assertEquals("image/png", FileMime.of("shot.png"))
        assertEquals("application/pdf", FileMime.of("paper.pdf"))
        assertEquals("text/plain", FileMime.of("notes.txt"))
        assertEquals("application/json", FileMime.of("data.json"))
    }

    @Test
    fun `mapping is case-insensitive`() {
        assertEquals("video/x-matroska", FileMime.of("Lecture.MKV"))
        assertEquals("image/png", FileMime.of("X.PNG"))
    }

    @Test
    fun `unknown extensions and dotless names fall back honestly`() {
        assertEquals(FileMime.FALLBACK, FileMime.of("weird.zzz"))
        assertEquals(FileMime.FALLBACK, FileMime.of("Makefile"))
        assertEquals(FileMime.FALLBACK, FileMime.of(""))
    }
}
