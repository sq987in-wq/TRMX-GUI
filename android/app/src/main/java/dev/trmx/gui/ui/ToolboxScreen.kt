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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.ui.Tokens.Palette as P

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
    onOpenSchemaBuilder: () -> Unit,
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
            TrmxButton(label = "Scan",
                       leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                       onClick = onRefresh, kind = TrmxButtonKind.Secondary)
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
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column {
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
            Text("No tools — tap Scan.", color = P.TextSecondary)
        }

        TrmxCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showSubmit = true },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
                Icon(Icons.Outlined.Terminal, contentDescription = null,
                     tint = P.Accent, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("Custom command", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text("Run any argv directly — one line, one argument. No shell.",
                         style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                }
            }
        }

        state.tools.forEach { t -> ToolCard(t, onOpenTool) { confirmInstall = t } }

        // ---- recipes -----------------------------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recipes (${recipes.size})", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TrmxButton(label = "import",
                               onClick = { importPicker.launch(arrayOf("application/json", "*/*")) },
                               kind = TrmxButtonKind.Secondary)
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

        TrmxCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSchemaBuilder,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                     tint = P.Accent, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("AI Schema Builder", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text("Describe a tool in plain words — a local LLM writes the schema.",
                         style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                }
            }
        }

        TrmxButton(label = "Chains — visual pipelines",
                   leading = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                   onClick = onOpenChains, modifier = Modifier.fillMaxWidth(),
                   kind = TrmxButtonKind.Secondary)
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
    TrmxCard(modifier = Modifier.fillMaxWidth(),
             onClick = if (t.installed) ({ onOpenTool(t.schema.id) }) else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            Text(t.schema.name, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Text(t.schema.description, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.installed) {
                    // UX-audit P2: clean status instead of risk-tier badges —
                    // the tier still drives the RUN confirmation in the form.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(TrmxColors.Running, CircleShape))
                        Spacer(Modifier.width(Sp.xs))
                        Text("Ready", color = TrmxColors.Running,
                             style = MaterialTheme.typography.bodySmall,
                             fontWeight = FontWeight.Medium)
                    }
                    t.version?.let {
                        Text("  ·  $it", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(TrmxColors.Queued, CircleShape))
                        Spacer(Modifier.width(Sp.xs))
                        Text("Setup required", color = TrmxColors.Queued,
                             style = MaterialTheme.typography.bodySmall,
                             fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.weight(1f))
                    TrmxButton(label = "install", onClick = onInstall)
                }
            }
        }
    }
}
