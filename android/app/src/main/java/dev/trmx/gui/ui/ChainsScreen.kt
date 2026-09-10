package dev.trmx.gui.ui

/*
 * Phase 9 Chains (ADR-010): linear visual pipelines of tool jobs.
 * Planning UI + live run view; AppViewModel orchestrates over the existing
 * §3/§7 wire (the bridge knows nothing about chains).
 */

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.trmx.gui.ChainsState
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.tools.ChainPlanner

@Composable
fun ChainsScreen(
    state: ChainsState,
    tools: List<ToolStatus>,
    jobs: List<JobSummary>,
    onNew: () -> Unit,
    onEditDef: (String) -> Unit,
    onDeleteDef: (String) -> Unit,
    onRun: (dev.trmx.gui.tools.ChainDef) -> Unit,
    onResume: () -> Unit,
    onStopRun: () -> Unit,
    onSetTitle: (String) -> Unit,
    onAddStep: (String) -> Unit,
    onRemoveStep: (Int) -> Unit,
    onEditStep: (Int) -> Unit,
    onSaveDef: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addStepDialog by remember { mutableStateOf(false) }
    val schemas = tools.associate { it.schema.id to it.schema }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chains", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onNew) { Text("+ new chain") }
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        // ---- live run --------------------------------------------------
        state.run?.let { run ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(run.def.title, fontWeight = FontWeight.Bold,
                             modifier = Modifier.weight(1f))
                        Text(when (run.status) {
                            "RUNNING" -> "▶ RUNNING"
                            "COMPLETED" -> "✓ COMPLETED"
                            "FAILED" -> "✗ FAILED"
                            else -> "⏸ PAUSED"
                        }, color = when (run.status) {
                            "RUNNING" -> Color(0xFF4CAF50)
                            "COMPLETED" -> Color(0xFF2196F3)
                            "FAILED" -> MaterialTheme.colorScheme.error
                            else -> Color(0xFFFF9800)
                        }, fontWeight = FontWeight.Bold)
                    }
                    run.def.steps.forEachIndexed { i, step ->
                        val schema = schemas[step.toolId]
                        val job = run.stepJobIds[i]?.let { id -> jobs.firstOrNull { it.job_id == id } }
                        val label = "${i + 1}. ${ChainPlanner.stepTitle(step, schema)}"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    job != null -> job.status
                                    run.status == "COMPLETED" -> "done"
                                    i < run.currentStep -> "done"
                                    i == run.currentStep && run.status == "RUNNING" -> "running…"
                                    i == run.currentStep && run.status == "FAILED" -> "failed"
                                    i == run.currentStep -> "paused"
                                    else -> "waiting"
                                },
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = when {
                                    job?.status == "COMPLETED" || (run.status == "COMPLETED") -> Color(0xFF2196F3)
                                    i == run.currentStep && run.status == "FAILED" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.secondary
                                },
                                modifier = Modifier.width(80.dp))
                            Text(label, modifier = Modifier.weight(1f),
                                 style = MaterialTheme.typography.bodyMedium)
                        }
                        job?.progress_pct?.let { pct ->
                            LinearProgressIndicator(
                                progress = { (pct / 100.0).toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(start = 88.dp))
                        }
                    }
                    run.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             color = if (run.status == "FAILED")
                                 MaterialTheme.colorScheme.error
                             else MaterialTheme.colorScheme.tertiary)
                    }
                    if (run.status == "PAUSED") {
                        ActionFlowRow {
                            Button(onClick = onResume) { Text("resume from step ${run.currentStep + 1}") }
                            OutlinedButton(onClick = onStopRun) { Text("stop") }
                        }
                    } else if (run.status == "RUNNING") {
                        OutlinedButton(onClick = onStopRun) { Text("stop orchestrating") }
                    }
                }
            }
        }

        // ---- builder ---------------------------------------------------
        state.editing?.let { def ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Building: ${def.title.ifBlank { "untitled chain" }}",
                         fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = def.title,
                        onValueChange = onSetTitle,
                        label = { Text("chain title") }, singleLine = true)
                    def.steps.forEachIndexed { i, step ->
                        val schema = schemas[step.toolId]
                        val canChain = ChainPlanner.stepHasOutput(schema ?: return@forEachIndexed)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.", modifier = Modifier.width(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ChainPlanner.stepTitle(step, schema),
                                     style = MaterialTheme.typography.bodyMedium)
                                if (!canChain && i < def.steps.size - 1) {
                                    Text("no known output — cannot be chained from",
                                         fontSize = 10.sp,
                                         color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            TextButton(onClick = { onEditStep(i) }) { Text("✎") }
                            TextButton(onClick = { onRemoveStep(i) }) {
                                Text("✕", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    OutlinedButton(onClick = { addStepDialog = true }) { Text("+ add step") }
                    Button(onClick = onSaveDef, modifier = Modifier.fillMaxWidth()) {
                        Text("save chain")
                    }
                }
            }
        }

        // ---- saved chains ---------------------------------------------
        if (state.defs.isNotEmpty() && state.editing == null) {
            Text("Saved chains", fontWeight = FontWeight.Bold)
        }
        state.defs.forEach { def ->
            if (state.editing?.id == def.id) return@forEach
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(def.title, style = MaterialTheme.typography.bodyLarge)
                        Text("${def.steps.size} steps: " +
                             def.steps.joinToString(" → ") { it.toolId },
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    Button(onClick = { onRun(def) }) { Text("▶ run") }
                    TextButton(onClick = { onEditDef(def.id) }) { Text("✎ edit") }
                    TextButton(onClick = { onDeleteDef(def.id) }) {
                        Text("delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (addStepDialog) {
        AlertDialog(
            onDismissRequest = { addStepDialog = false },
            title = { Text("Add a step") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tools.filter { it.installed }.forEach { t ->
                        TextButton(onClick = { onAddStep(t.schema.id); addStepDialog = false }) {
                            Text(t.schema.name)
                        }
                    }
                    if (tools.none { it.installed }) {
                        Text("No installed tools.", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addStepDialog = false }) { Text("cancel") } },
        )
    }
}
