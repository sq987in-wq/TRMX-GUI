package dev.trmx.gui.ui

/*
 * Job detail: the full job record (TRMX-P/1 §3.4 shape), cancel with
 * confirmation, auto-refresh every 2 s while the job is active.
 * Live output streaming arrives in Phase 6.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.JobDetailState
import dev.trmx.gui.job.JobOutputState

private val ACTIVE = setOf("QUEUED", "RUNNING", "CANCELLING")

@Composable
fun JobDetailScreen(
    state: JobDetailState,
    output: JobOutputState?,
    onBack: () -> Unit,
    onCancelJob: (String) -> Unit,
    onReplayOutput: () -> Unit,
) {
    var confirmCancel by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            OutlinedButton(onClick = onBack) { Text("← Jobs") }
            Spacer(Modifier.width(12.dp))
            Text("Job ${state.jobId}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        val job = state.job
        state.error?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(12.dp),
                     color = MaterialTheme.colorScheme.error)
            }
        }

        if (job != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(job.name, fontWeight = FontWeight.Bold,
                             modifier = Modifier.weight(1f))
                        Text(
                            job.status,
                            color = statusColor(job.status),
                            fontWeight = FontWeight.Bold)
                    }
                    Section("argv") {
                        Text(
                            job.argv?.joinToString("\n") ?: "—",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    KV("type", job.type)
                    job.cwd?.let { KV("cwd", it) }
                    KV("created", job.created_at ?: "—")
                    KV("started", job.started_at ?: "—")
                    KV("ended", job.ended_at ?: "—")
                    job.pid?.let { KV("pid", it.toString()) }
                    job.exit_code?.let { KV("exit code", it.toString()) }
                    job.signal?.let { KV("signal", it.toString()) }
                    job.timeout_s?.let { KV("timeout_s", it.toString()) }
                    job.error?.let { KV("error", "${it.code}: ${it.message}") }
                    KV("stdout / stderr", "${job.stdout_bytes} / ${job.stderr_bytes} bytes")
                    KV("log frames", job.log_seq.toString() + if (job.log_truncated) " (ring evicted older frames)" else "")
                    if (job.cancel_requested) {
                        Text("cancel requested${job.cancel_reason?.let { " ($it)" } ?: ""}",
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            output?.let { OutputConsole(it, onReplayOutput) }

            if (job.status in ACTIVE) {
                Button(
                    onClick = { confirmCancel = true },
                    enabled = !state.cancelling,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.cancelling) "Cancelling…" else "Cancel job")
                }
            }
        } else if (state.error == null) {
            Text("loading…")
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel job?") },
            text = {
                Text(
                    "The bridge sends SIGTERM to the process group, then SIGKILL " +
                        "after a 5 s grace period. The job record is kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancelJob(state.jobId)
                }) { Text("Cancel job") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep running") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelSmall,
         color = MaterialTheme.colorScheme.secondary)
    content()
}

@Composable
private fun KV(k: String, v: String) {
    Row {
        Text(k, fontFamily = FontFamily.Monospace,
             modifier = Modifier.width(120.dp),
             color = MaterialTheme.colorScheme.secondary)
        Text(v, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun statusColor(status: String): Color = when (status) {
    "RUNNING" -> Color(0xFF4CAF50)
    "QUEUED" -> Color(0xFFFFC107)
    "COMPLETED" -> Color(0xFF2196F3)
    "FAILED" -> Color(0xFFF44336)
    "CANCELLED" -> Color(0xFF9E9E9E)
    "CANCELLING" -> Color(0xFFFF9800)
    "LOST" -> Color(0xFFFF9800)
    else -> Color.Gray
}


// ---- live output console (Phase 6) ---------------------------------------

@Composable
private fun OutputConsole(
    output: JobOutputState,
    onReplay: () -> Unit,
) {
    var filter by remember { mutableStateOf("all") }   // all | stdout | stderr
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val visible = when (filter) {
        "stdout" -> output.lines.filter { it.kind == "stdout" }
        "stderr" -> output.lines.filter { it.kind == "stderr" }
        else -> output.lines
    }
    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.size - 1)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val live = output.error == null && !output.ended
                Text(
                    when {
                        output.error != null -> "⚠ ${output.error}"
                        output.ended -> "ended"
                        else -> "live"
                    },
                    color = if (live) Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (output.evicted) {
                Text(
                    "older frames were evicted from the bridge's ring — showing the replayable window",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("all", "stdout", "stderr").forEach { f ->
                    TextButton(onClick = { filter = f }) {
                        Text(
                            f,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (filter == f) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary)
                    }
                }
                TextButton(onClick = { autoScroll = !autoScroll }) {
                    Text(
                        if (autoScroll) "auto-scroll ✓" else "auto-scroll ✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)) {
                items(visible) { line ->
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = when {
                            line.isStderr -> Color(0xFFFF8A80)
                            line.kind == "status" -> MaterialTheme.colorScheme.secondary
                            else -> Color.Unspecified
                        })
                }
            }
            TextButton(onClick = onReplay) { Text("replay from start") }
        }
    }
}
