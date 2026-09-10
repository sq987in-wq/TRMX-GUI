package dev.trmx.gui.ui

/*
 * Phase 9 Toolbox: live tool cards from the bridge registry (PROTOCOL §7),
 * one-tap pkg install for missing binaries, recipes (saved forms) with
 * share/import, and the entry point to chains.
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.store.Recipe

@Composable
fun ToolboxScreen(
    state: dev.trmx.gui.ToolsState,
    recipes: List<Recipe>,
    submitForm: dev.trmx.gui.SubmitFormState,
    onRefresh: () -> Unit,
    onOpenTool: (String) -> Unit,
    onInstall: (ToolStatus) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onDeleteRecipe: (String) -> Unit,
    onShareRecipe: (Recipe) -> Unit,
    onImportRecipe: (android.net.Uri) -> Unit,
    onOpenChains: () -> Unit,
    onSubmitName: (String) -> Unit,
    onSubmitArgv: (String) -> Unit,
    onSubmitCwd: (String) -> Unit,
    onSubmitTimeout: (String) -> Unit,
    onSubmitJob: () -> Unit,
    onDismissSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportRecipe(uri)
    }
    var confirmInstall by remember { mutableStateOf<ToolStatus?>(null) }
    // Manual argv submission moved here from Home (UX-audit P1): it is an
    // expert action, not a primary goal — argv-first stays (ADR-001).
    var showSubmit by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Toolbox", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.loading) CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRefresh) { Text("scan ⟳") }
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
        if (state.schemaErrors.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Some user schemas in ~/.trmx/tools/ were skipped:",
                         fontWeight = FontWeight.Bold,
                         style = MaterialTheme.typography.bodySmall)
                    state.schemaErrors.take(3).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             fontSize = 10.sp)
                    }
                }
            }
        }

        if (!state.loading && state.tools.isEmpty() && state.error == null) {
            Text("No tools — tap “scan ⟳”.", color = MaterialTheme.colorScheme.secondary)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSubmit = true },
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("⚡ Custom command", fontWeight = FontWeight.Bold,
                     style = MaterialTheme.typography.bodyLarge)
                Text("Run any argv directly — one line, one argument. No shell.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        state.tools.forEach { t -> ToolCard(t, onOpenTool) { confirmInstall = t } }

        // ---- recipes -----------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp),
                   verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recipes (${recipes.size})", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        importPicker.launch(arrayOf("application/json", "*/*"))
                    }) { Text("import") }
                }
                if (recipes.isEmpty()) {
                    Text("Save a filled tool form as a recipe — it becomes a " +
                         "one-tap shortcut on the home screen.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                recipes.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenRecipe(r.id) }) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge)
                            Text(r.toolId, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = { onShareRecipe(r) }) { Text("share") }
                        TextButton(onClick = { onDeleteRecipe(r.id) }) {
                            Text("delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        Button(onClick = onOpenChains, modifier = Modifier.fillMaxWidth()) {
            Text("⛓ Chains — visual pipelines")
        }
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

    confirmInstall?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Install ${t.schema.pkg ?: t.schema.binary}?") },
            text = {
                Text("Runs `pkg install -y ${t.schema.pkg ?: t.schema.binary}` as a " +
                     "normal job with a live console. The toolbox rescans " +
                     "automatically when it finishes.")
            },
            confirmButton = {
                TextButton(onClick = { onInstall(t); confirmInstall = null }) {
                    Text("install") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = null }) { Text("cancel") }
            },
        )
    }
}

@Composable
private fun ToolCard(t: ToolStatus, onOpenTool: (String) -> Unit, onInstall: () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = t.installed) { onOpenTool(t.schema.id) }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.schema.name, fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.bodyLarge)
            Text(t.schema.description, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.installed) {
                    // UX-audit P2: clean status instead of risk-tier badges —
                    // the tier still drives the RUN confirmation in the form.
                    Text("● Ready", color = TrmxColors.Running,
                         style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.Medium)
                    t.version?.let {
                        Text("  ·  $it", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("● Setup required", color = TrmxColors.Queued,
                         style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onInstall) { Text("install") }
                }
            }
        }
    }
}
