package dev.trmx.gui.tools

/*
 * Human task labels (UX-audit P1, ADR-012): raw J-IDs stop being the
 * primary identity of a task. Pure functions, JVM-testable.
 *
 * The "outcome" vocabulary is deliberately honest: we know the tool, the
 * status and the timestamps — not whether the video actually plays. So
 * labels read "Video Downloader (yt-dlp) · done · 3 min ago", never
 * "Video downloaded successfully".
 */

import dev.trmx.gui.model.JobSummary
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object JobLabels {

    /**
     * Primary label: the name the app controls at submit (tool display
     * name, "chain — step", or the user's own name), else a sane fallback
     * chain: binary → tool id → type → "job".
     */
    fun taskLabel(job: JobSummary): String =
        job.name.ifBlank {
            job.argv?.firstOrNull()?.substringAfterLast('/')?.ifBlank { null }
                ?: job.tool
                ?: job.type.ifBlank { null }
                ?: "job"
        }

    /** Lowercase verb for the status chip ("running", "done", "failed"). */
    fun statusVerb(status: String): String = when (status) {
        "RUNNING" -> "running"
        "STARTING" -> "starting"
        "QUEUED" -> "queued"
        "COMPLETED" -> "done"
        "FAILED" -> "failed"
        "CANCELLED" -> "cancelled"
        "CANCELLING" -> "cancelling"
        "LOST" -> "lost"
        else -> status.lowercase(Locale.ROOT)
    }

    /**
     * "3 min ago"-style relative time from an ISO-8601 timestamp.
     * Null/unparseable → null (caller omits the segment, never shows junk).
     */
    fun relativeTime(iso: String?, nowMs: Long = System.currentTimeMillis()): String? {
        if (iso == null) return null
        val t = runCatching {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        val s = (nowMs - t) / 1000
        return when {
            s in 0L until 60L -> "just now"    // Long needs a Long range — no Int coercion
            s < 0 && s > -3600 -> "just now"       // same-device clock edge
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 7 * 86_400 -> "${s / 86_400} d ago"
            else -> formatDate(iso)
        }
    }

    /** Secondary line: "yt-dlp · 3 min ago" (what ran, when). */
    fun subtitle(job: JobSummary, nowMs: Long = System.currentTimeMillis()): String {
        val parts = mutableListOf<String>()
        (job.tool ?: job.type.takeIf { it.isNotBlank() })?.let { parts += it }
        relativeTime(job.created_at ?: job.started_at, nowMs)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun formatDate(iso: String): String = runCatching {
        DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(OffsetDateTime.parse(iso))
    }.getOrDefault(iso.take(10))
}
