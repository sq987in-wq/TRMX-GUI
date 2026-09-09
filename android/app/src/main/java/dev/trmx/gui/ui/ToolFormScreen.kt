package dev.trmx.gui.ui

/*
 * Phase 9 dynamic tool form: rendered from the ToolSchema (PROTOCOL §7.2).
 * Every schema type gets a purpose-built control; a live argv preview strip
 * shows exactly what will run on the phone; risk tier drives the run gate.
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ToolFormState
import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.FormEngine

private val TIER_COLORS = mapOf(
    "safe" to Color(0xFF4CAF50),
    "confirm" to Color(0xFFFFC107),
    "destructive" to Color(0xFFF44336),
)

@Composable
fun ToolFormScreen(
    state: ToolFormState,
    onBack: () -> Unit,
    onEdit: (String, FieldValue) -> Unit,
    onBrowsePath: (String) -> Unit,
    onExample: (ToolExample) -> Unit,
    onSubmit: () -> Unit,
    onSaveRecipe: (String) -> Unit,
) {
    val schema = state.schema ?: return
    var confirmRun by remember { mutableStateOf(false) }
    var saveRecipe by remember { mutableStateOf(false) }

    val problems = FormEngine.problems(schema, state.values)
    val preview = FormEngine.previewArgv(schema, state.values)
    val submittable = problems.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Toolbox") }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(schema.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (state.stepIndex != null) {
                    Text("editing chain step ${state.stepIndex + 1}",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(schema.risk_tier.uppercase(),
                 color = TIER_COLORS[schema.risk_tier] ?: Color.Gray,
                 fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        schema.args.forEach { a ->
            val v = state.values[a.name] ?: FieldValue()
            Field(a, v, onEdit, onBrowsePath)
        }

        if (schema.examples.isNotEmpty()) {
            Text("Try:", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                schema.examples.take(3).forEach { ex ->
                    FilterChip(selected = false, onClick = { onExample(ex) },
                               label = { Text("▸ ${ex.label}") })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text("will run on the phone:", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.secondary)
                Text(preview.joinToString(" ") { shellWord(it) },
                     fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (preview.any { it.startsWith("~/") }) {
                    Text("paths are resolved against the Termux home by the bridge",
                         style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                         color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        if (problems.isNotEmpty()) {
            problems.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (state.submitting) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
            Button(
                onClick = { if (schema.risk_tier == "safe") onSubmit() else confirmRun = true },
                enabled = submittable && !state.submitting && state.stepIndex == null,
            ) {
                Text(when (schema.risk_tier) {
                    "safe" -> "RUN ▶"
                    else -> "RUN ▶ (confirm)"
                })
            }
            if (state.stepIndex == null) {
                OutlinedButton(onClick = { saveRecipe = true },
                               enabled = submittable && !state.submitting) {
                    Text("☆ save recipe")
                }
            } else {
                Button(onClick = onSubmit, enabled = submittable && !state.submitting) {
                    Text("save to step ✓")
                }
            }
        }
    }

    if (confirmRun) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Run ${schema.name}?") },
            text = {
                Text("The phone will execute:\n\n" +
                     preview.joinToString(" ") { shellWord(it) })
            },
            confirmButton = {
                TextButton(onClick = { confirmRun = false; onSubmit() }) { Text("run it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRun = false }) { Text("cancel") }
            },
        )
    }

    if (saveRecipe) {
        var title by remember { mutableStateOf(schema.name) }
        AlertDialog(
            onDismissRequest = { saveRecipe = false },
            title = { Text("Save recipe") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it },
                                  label = { Text("recipe name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) onSaveRecipe(title.trim())
                    saveRecipe = false
                }) { Text("save") }
            },
            dismissButton = {
                TextButton(onClick = { saveRecipe = false }) { Text("cancel") }
            },
        )
    }
}

@Composable
private fun Field(
    a: ToolArg,
    v: FieldValue,
    onEdit: (String, FieldValue) -> Unit,
    onBrowsePath: (String) -> Unit,
) {
    val err = FormEngine.validate(a, v)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(a.label.ifEmpty { a.name }, fontWeight = FontWeight.Medium,
             style = MaterialTheme.typography.bodyMedium)
        when (a.type) {
            "bool" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = v.bool, onCheckedChange = { onEdit(a.name, v.copy(bool = it)) })
                Spacer(Modifier.width(8.dp))
                Text(if (v.bool) "on" else "off",
                     style = MaterialTheme.typography.bodySmall)
            }
            "enum" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (a.enum ?: emptyList()).forEach { option ->
                    FilterChip(
                        selected = v.text == option,
                        onClick = { onEdit(a.name, FieldValue(text = option)) },
                        label = { Text(option) })
                }
            }
            "path" -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = v.text,
                    onValueChange = { onEdit(a.name, v.copy(text = it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = err != null,
                    placeholder = { Text("~/…") })
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onBrowsePath(a.name) }) { Text("📁") }
            }
            "int", "float" -> OutlinedTextField(
                value = v.text,
                onValueChange = { onEdit(a.name, v.copy(text = it)) },
                singleLine = true,
                isError = err != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number),
                placeholder = {
                    Text(a.default?.toString()?.removePrefix("\"") ?: "") })
            else -> OutlinedTextField(     // string | url
                value = v.text,
                onValueChange = { onEdit(a.name, v.copy(text = it)) },
                singleLine = true,
                isError = err != null,
                keyboardOptions = if (a.type == "url")
                    KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions.Default,
            )
        }
        a.help?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                 color = MaterialTheme.colorScheme.secondary)
        }
        if (err != null && v.text.isNotEmpty() || (err == "required")) {
            Text("⚠ $err", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Quote for display only — the preview strip, not real shell parsing. */
private fun shellWord(w: String): String =
    if (w.contains(' ') || w.contains('"')) "\"$w\"" else w
