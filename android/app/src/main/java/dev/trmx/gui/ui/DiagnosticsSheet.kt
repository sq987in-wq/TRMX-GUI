package dev.trmx.gui.ui

/*
 * Diagnostics bottom sheet (UX-audit P1, ADR-012): the deep bridge
 * metrics (version, protocol, uptime, load, memory, storage, caps,
 * Termux prefix) live here instead of dominating Home. Opened from the
 * Home status pill — one tap for quick health, two for the deep view.
 * Stop bridge / Re-run setup are deliberate actions and belong here,
 * not on the primary screen.
 *
 * Data source unchanged: GET /v1/system/info remains the cold-connect
 * health probe (§2.1) — only its presentation moved.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.DashboardState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: DashboardState,
    onRefresh: () -> Unit,
    onStopBridge: () -> Unit,
    onRerunWizard: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = Sp.m)
                .padding(bottom = Sp.l)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Sp.m),
        ) {
            Text("Diagnostics", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }

            val info = state.info
            if (info != null) {
                BridgeInfoCard(info)
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.refreshing) "loading bridge info…" else "no bridge info yet",
                        modifier = Modifier.padding(14.dp))
                }
            }

            ActionFlowRow {
                Button(onClick = onRefresh, enabled = !state.refreshing) {
                    Text(if (state.refreshing) "Refreshing…" else "Refresh")
                }
                OutlinedButton(onClick = onRerunWizard) { Text("Re-run setup") }
                OutlinedButton(onClick = onStopBridge) { Text("Stop bridge") }
            }

            Text(
                "Everything runs on the phone: jobs + live output, file manager " +
                    "with open/share, all through the local bridge (TRMX-P/1).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BridgeInfoCard(info: dev.trmx.gui.model.SystemInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Sp.m), verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
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
             modifier = Modifier.width(130.dp),
             color = MaterialTheme.colorScheme.secondary)
        Text(v, fontFamily = FontFamily.Monospace)
    }
}
