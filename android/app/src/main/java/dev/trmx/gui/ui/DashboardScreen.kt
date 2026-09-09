package dev.trmx.gui.ui

/*
 * Dashboard: bridge status + system info + job list, manual refresh,
 * job submission (Phase 5). Live streaming output is Phase 6.
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.trmx.gui.DashboardState
import dev.trmx.gui.SubmitFormState
import dev.trmx.gui.model.JobSummary

private val STATUS_COLORS = mapOf(
    "RUNNING" to Color(0xFF4CAF50),
    "QUEUED" to Color(0xFFFFC107),
    "CANCELLING" to Color(0xFFFF9800),
    "COMPLETED" to Color(0xFF2196F3),
    "FAILED" to Color(0xFFF44336),
    "CANCELLED" to Color(0xFF9E9E9E),
    "LOST" to Color(0xFFFF9800),
)

@Composable
fun DashboardScreen(
    state: DashboardState,
    submitForm: SubmitFormState,
    onRefresh: () -> Unit,
    onStopBridge: () -> Unit,
    onRerunWizard: () -> Unit,
    onJobClick: (String) -> Unit,
    onOpenSubmit: () -> Unit,
    onDismissSubmit: () -> Unit,
    onSubmitName: (String) -> Unit,
    onSubmitArgv: (String) -> Unit,
    onSubmitCwd: (String) -> Unit,
    onSubmitTimeout: (String) -> Unit,
    onSubmitJob: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTools: () -> Unit = {},
) {
    var showSubmit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRMX", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            val connected = state.error == null
            Text(
                if (connected) "● connected" else "● connection error",
                color = if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error)
            Spacer(Modifier.weight(1f))
            Button(onClick = { onOpenSubmit(); showSubmit = true }) { Text("+ New job") }
        }

        state.notice?.let { Banner(it) }
        state.error?.let { Banner(it, error = true) }

        val info = state.info
        if (info != null) {
            InfoCard(info)
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.refreshing) "loading bridge info…" else "no bridge info yet",
                    modifier = Modifier.padding(14.dp))
            }
        }

        JobsCard(state.jobs, onJobClick)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !state.refreshing) {
                Text(if (state.refreshing) "Refreshing…" else "Refresh")
            }
            Button(onClick = onOpenFiles) { Text("Files") }
            Button(onClick = onOpenTools) { Text("Tools") }
            OutlinedButton(onClick = onRerunWizard) { Text("Re-run setup") }
            OutlinedButton(onClick = onStopBridge) { Text("Stop bridge") }
        }
        Text(
            "Everything runs on the phone: jobs + live output, file manager " +
                "with open/share, all through the local bridge (TRMX-P/1).",
            style = MaterialTheme.typography.bodySmall)
    }

    if (showSubmit) {
        SubmitDialog(
            state = submitForm,
            onName = onSubmitName,
            onArgv = onSubmitArgv,
            onCwd = onSubmitCwd,
            onTimeout = onSubmitTimeout,
            onSubmit = onSubmitJob,
            onDismiss = {
                showSubmit = false
                onDismissSubmit()
            },
        )
    }
}

@Composable
private fun Banner(text: String, error: Boolean = false) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoCard(info: dev.trmx.gui.model.SystemInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Bridge", fontWeight = FontWeight.Bold)
            KeyValue("version", info.bridge_version)
            KeyValue("protocol", info.protocol_versions.joinToString(", "))
            KeyValue("uptime", "${info.uptime_s / 60} min")
            info.load?.let {
                KeyValue("jobs", "running ${it.jobs_running} · queued ${it.jobs_queued}")
            }
            info.memory?.let {
                KeyValue("memory", "free ${it.free_mb} / ${it.total_mb} MB")
            }
            info.storage?.let {
                KeyValue("home storage", "free ${it.home_free_mb} MB")
            }
            info.caps?.let {
                KeyValue("limits", "concurrency ${it.max_concurrent_jobs} · queue ${it.queue_depth}")
            }
            info.termux?.prefix?.let { KeyValue("termux prefix", it) }
        }
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row {
        Text(k, fontFamily = FontFamily.Monospace,
             modifier = Modifier.width(130.dp), color = MaterialTheme.colorScheme.secondary)
        Text(v, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun JobsCard(jobs: List<JobSummary>, onJobClick: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Jobs (${jobs.size}) — tap for details", fontWeight = FontWeight.Bold)
            if (jobs.isEmpty()) {
                Text("No jobs yet — submit one with “+ New job”.",
                     style = MaterialTheme.typography.bodyMedium)
            }
            jobs.take(20).forEach { job ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJobClick(job.job_id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.job_id, fontFamily = FontFamily.Monospace,
                             modifier = Modifier.width(80.dp))
                        Text(
                            job.status,
                            color = STATUS_COLORS[job.status] ?: Color.Gray,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(110.dp))
                        Text(job.name.ifEmpty { job.argv?.joinToString(" ").orEmpty() },
                             style = MaterialTheme.typography.bodyMedium)
                    }
                    if (job.progress_pct != null) {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { (job.progress_pct / 100.0).toFloat() },
                                modifier = Modifier.fillMaxWidth())
                            Text("  ${job.progress_pct}% " +
                                     (job.progress_detail ?: ""),
                                 fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                 color = MaterialTheme.colorScheme.secondary)
                        }
                    } else {
                        job.progress_detail?.let {
                            Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
