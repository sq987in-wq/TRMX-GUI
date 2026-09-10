package dev.trmx.gui

import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.tools.JobLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

/**
 * UX-audit P1: human task labels — the fallback chain for taskLabel,
 * status verbs, relative-time buckets, subtitle composition.
 */
class JobLabelsTest {

    private fun job(
        name: String = "",
        tool: String? = null,
        argv: List<String>? = null,
        type: String = "",
        status: String = "RUNNING",
        created_at: String? = null,
    ) = JobSummary(job_id = "J-7", name = name, tool = tool, argv = argv,
                   type = type, status = status, created_at = created_at)

    @Test
    fun `task label prefers the name the app controls at submit`() {
        assertEquals("Video Downloader (yt-dlp)",
                     JobLabels.taskLabel(job(name = "Video Downloader (yt-dlp)")))
        assertEquals("My chain — step 2",
                     JobLabels.taskLabel(job(name = "My chain — step 2")))
    }

    @Test
    fun `task label falls back to binary, tool, type, job`() {
        assertEquals("yt-dlp",
                     JobLabels.taskLabel(job(argv = listOf("/usr/bin/yt-dlp", "-x"))))
        assertEquals("ffmpeg", JobLabels.taskLabel(job(tool = "ffmpeg")))
        assertEquals("argv", JobLabels.taskLabel(job(type = "argv")))
        assertEquals("job", JobLabels.taskLabel(job()))
    }

    @Test
    fun `status verbs are lowercase and human`() {
        assertEquals("running", JobLabels.statusVerb("RUNNING"))
        assertEquals("done", JobLabels.statusVerb("COMPLETED"))
        assertEquals("failed", JobLabels.statusVerb("FAILED"))
        assertEquals("cancelled", JobLabels.statusVerb("CANCELLED"))
        assertEquals("queued", JobLabels.statusVerb("QUEUED"))
        assertEquals("cancelling", JobLabels.statusVerb("CANCELLING"))
        assertEquals("weird status", JobLabels.statusVerb("WEIRD STATUS"))
    }

    @Test
    fun `relative time buckets from iso timestamps`() {
        val now = OffsetDateTime.parse("2026-09-10T12:00:00Z").toInstant().toEpochMilli()
        assertEquals("just now", JobLabels.relativeTime("2026-09-10T11:59:40Z", now))
        assertEquals("5 min ago", JobLabels.relativeTime("2026-09-10T11:55:00Z", now))
        assertEquals("3 h ago", JobLabels.relativeTime("2026-09-10T09:00:00Z", now))
        assertEquals("2 d ago", JobLabels.relativeTime("2026-09-08T12:00:00Z", now))
        assertEquals("Sep 1", JobLabels.relativeTime("2026-09-01T12:00:00Z", now))
        assertNull(JobLabels.relativeTime(null, now))
        assertNull(JobLabels.relativeTime("not-a-date", now))
        // same-device clock edge: a slightly-future stamp reads as just now
        assertEquals("just now", JobLabels.relativeTime("2026-09-10T12:00:30Z", now))
    }

    @Test
    fun `subtitle joins tool and relative time`() {
        val now = OffsetDateTime.parse("2026-09-10T12:00:00Z").toInstant().toEpochMilli()
        assertEquals("yt-dlp · 5 min ago",
                     JobLabels.subtitle(job(tool = "yt-dlp",
                                            created_at = "2026-09-10T11:55:00Z"), now))
        assertEquals("argv", JobLabels.subtitle(job(type = "argv"), now))
    }
}
