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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ChainsState
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.tools.ChainPlanner
import dev.trmx.gui.ui.Tokens.Palette as P

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
            TrmxButton(label = "+ new chain", onClick = onNew,
                       kind = TrmxButtonKind.Secondary)
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        // ---- educational empty state: automation canvas (P4) ------------
        if (state.defs.isEmpty() && state.editing == null && state.run == null) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Sp.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Automate Multi-Step Pipelines",
                         style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold,
                         textAlign = TextAlign.Center)
                    Text(
                        "Chain Termux tools into reusable workflows with zero shell scripting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary,
                        textAlign = TextAlign.Center)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Sp.s),
                    ) {
                        PipelineNode(Icons.Outlined.Download, "Ingest")
                        Connector(Modifier.weight(1f))
                        PipelineNode(Icons.Outlined.Build, "Transform")
                        Connector(Modifier.weight(1f))
                        PipelineNode(Icons.Outlined.SaveAlt, "Output")
                    }
                    TrmxButton(label = "Create First Pipeline",
                               onClick = onNew, modifier = Modifier.fillMaxWidth().height(48.dp))
                }
            }
        }

        // ---- live run --------------------------------------------------
        state.run?.let { run ->
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(run.def.title, fontWeight = FontWeight.Bold,
                             modifier = Modifier.weight(1f))
                        Text(when (run.status) {
                            "RUNNING" -> "RUNNING"
                            "COMPLETED" -> "COMPLETED"
                            "FAILED" -> "FAILED"
                            else -> "PAUSED"
                        }, color = when (run.status) {
                            "RUNNING" -> TrmxColors.Running
                            "COMPLETED" -> TrmxColors.Completed
                            "FAILED" -> MaterialTheme.colorScheme.error
                            else -> TrmxColors.Queued
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
                                    job?.status == "COMPLETED" || (run.status == "COMPLETED") -> TrmxColors.Completed
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
                            TrmxButton(label = "resume from step ${run.currentStep + 1}",
                                       onClick = onResume)
                            TrmxButton(label = "stop", onClick = onStopRun,
                                       kind = TrmxButtonKind.Secondary)
                        }
                    } else if (run.status == "RUNNING") {
                        TrmxButton(label = "stop orchestrating", onClick = onStopRun,
                                   kind = TrmxButtonKind.Secondary)
                    }
                }
            }
        }

        // ---- builder ---------------------------------------------------
        state.editing?.let { def ->
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text("Building: ${def.title.ifBlank { "untitled chain" }}",
                         fontWeight = FontWeight.Bold)
                    TrmxTextField(value = def.title, onValueChange = onSetTitle,
                                  label = "chain title")
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
                            TrmxIconButton(icon = Icons.Outlined.Edit,
                                           contentDescription = "edit step",
                                           onClick = { onEditStep(i) })
                            TrmxIconButton(icon = Icons.Outlined.Close,
                                           contentDescription = "remove step",
                                           onClick = { onRemoveStep(i) },
                                           tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TrmxButton(label = "+ add step", onClick = { addStepDialog = true },
                               kind = TrmxButtonKind.Secondary)
                    TrmxButton(label = "save chain", onClick = onSaveDef,
                               modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // ---- saved chains ---------------------------------------------
        if (state.defs.isNotEmpty() && state.editing == null) {
            Text("Saved chains", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
        }
        state.defs.forEach { def ->
            if (state.editing?.id == def.id) return@forEach
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(def.title, style = MaterialTheme.typography.bodyLarge)
                        Text("${def.steps.size} steps: " +
                             def.steps.joinToString(" → ") { it.toolId },
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.secondary)
                    }
                    TrmxButton(label = "Run", onClick = { onRun(def) },
                               leading = { Icon(Icons.Filled.PlayArrow, contentDescription = null,
                                                tint = P.OnAccent) })
                    TextButton(onClick = { onEditDef(def.id) }) { Text("edit") }
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

/** One node of the pipeline illustration: bordered icon tile + label. */
@Composable
private fun PipelineNode(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = P.FieldFill,
            border = androidx.compose.foundation.BorderStroke(1.dp, P.BorderStrong),
        ) {
            Icon(icon, contentDescription = label, tint = P.Accent,
                 modifier = Modifier.padding(Sp.m).size(22.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
    }
}

/** Crisp vector connector line between nodes (weight set by the Row). */
@Composable
private fun Connector(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.height(1.dp).background(P.BorderStrong)
    )
}
