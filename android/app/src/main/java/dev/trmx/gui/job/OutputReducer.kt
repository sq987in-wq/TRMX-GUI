package dev.trmx.gui.job

/*
 * Pure reducer for the job-output stream: turns SSE frames into console
 * state. No Android/coroutine dependencies — exhaustively unit-tested
 * against the normative fixture frames.
 *
 * Line budget: the bridge's ring is 2 MiB; the phone UI keeps the last
 * MAX_LINES / MAX_CHARS so long jobs never balloon memory.
 */

import dev.trmx.gui.model.JobError
import dev.trmx.gui.net.SseFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class OutputLine(val seq: Long, val kind: String, val text: String) {
    val isStderr: Boolean get() = kind == "stderr"
}

data class JobOutputState(
    val lines: List<OutputLine> = emptyList(),
    val lastSeq: Long = 0,
    val evicted: Boolean = false,   // bridge told us older frames were evicted
    val ended: Boolean = false,     // saw a terminal status frame
    val error: String? = null,
)

@Serializable
private data class TextFrame(val seq: Long = 0, val text: String = "")

@Serializable
private data class StatusFrame(
    val status: String = "",
    val exit_code: Long? = null,
    val signal: Long? = null,
    val error: JobError? = null,
    val ended_at: String? = null,
    val progress_pct: Double? = null,
    val progress_detail: String? = null,
)

@Serializable
private data class InfoFrame(val type: String = "", val resume_from_seq: Long? = null)

object OutputReducer {

    const val MAX_LINES = 1000
    const val MAX_CHARS = 200_000
    val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")

    private val json = Json { ignoreUnknownKeys = true }

    fun initial(): JobOutputState = JobOutputState()

    fun apply(state: JobOutputState, frame: SseFrame): JobOutputState {
        var s = state.copy(
            lastSeq = maxOf(state.lastSeq, frame.id ?: 0),
            error = null,
        )
        when (frame.event) {
            "stdout", "stderr" -> {
                val f = runCatching { json.decodeFromString(TextFrame.serializer(), frame.data) }
                    .getOrNull() ?: return s
                s = append(s, OutputLine(f.seq, frame.event, f.text))
            }
            "status" -> {
                val f = runCatching { json.decodeFromString(StatusFrame.serializer(), frame.data) }
                    .getOrNull() ?: return s
                val label = buildString {
                    append("— ").append(f.status)
                    f.exit_code?.let { append(" (exit ").append(it).append(')') }
                    append(" —")
                }
                s = append(s, OutputLine(frame.id ?: s.lastSeq, "status", label))
                if (f.status in TERMINAL) s = s.copy(ended = true)
            }
            "info" -> {
                val f = runCatching { json.decodeFromString(InfoFrame.serializer(), frame.data) }
                    .getOrNull() ?: return s
                if (f.type == "evicted") {
                    s = s.copy(evicted = true)
                }
            }
            else -> {}   // unknown future event types are ignored, not fatal
        }
        return s
    }

    private fun append(state: JobOutputState, line: OutputLine): JobOutputState {
        var lines = state.lines + line
        var chars = lines.sumOf { it.text.length }
        var i = 0
        while (i < lines.size &&
            (lines.size > MAX_LINES || chars > MAX_CHARS)
        ) {
            chars -= lines[i].text.length
            i++
        }
        if (i > 0) lines = lines.drop(i)
        return state.copy(lines = lines)
    }
}
