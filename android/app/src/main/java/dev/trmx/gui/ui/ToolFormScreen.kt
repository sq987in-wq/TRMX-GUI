package dev.trmx.gui.ui

/*
 * Phase 9 dynamic tool form, Phase 9.5 executive overhaul (ADR-011):
 * terminal-luxe styling, errors only after touch, path pickers as
 * trailing icons INSIDE the field, segmented enums, bounded sliders,
 * x_trmx secrets/units. The live argv preview stays the centerpiece.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ToolFormState
import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.FormEngine

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

    val submittable = FormEngine.isSubmittable(schema, state.values)
    val preview = FormEngine.previewArgv(schema, state.values)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.s + Sp.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Toolbox") }
            Spacer(Modifier.width(Sp.s))
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
                 color = TrmxColors.tier(schema.risk_tier),
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Sp.s + Sp.xs)) {
                schema.description.let {
                    if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        schema.args.forEach { a ->
            Field(a, state, onEdit, onBrowsePath)
        }

        if (schema.examples.isNotEmpty()) {
            Text("Try:", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                schema.examples.take(3).forEach { ex ->
                    FilterChip(selected = false, onClick = { onExample(ex) },
                               label = { Text("▸ ${ex.label}") })
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Sp.s + Sp.xs)) {
                Text("will run on the phone:", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(preview.joinToString(" ") { shellWord(it) },
                     fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (preview.any { it.startsWith("~/") }) {
                    Text("paths are resolved against the Termux home by the bridge",
                         style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Wraps instead of squeezing the last button (UX-audit P0).
        ActionFlowRow {
            // Always enabled: a premature tap marks every field touched and
            // surfaces its validation error (Ph 9.5) instead of a dead button.
            Button(
                onClick = { if (schema.risk_tier == "safe") onSubmit() else confirmRun = true },
                enabled = !state.submitting && state.stepIndex == null,
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Sp.xs))
                }
                Text(if (schema.risk_tier == "safe") "RUN ▶" else "RUN ▶ (confirm)")
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
        Spacer(Modifier.width(Sp.xs))
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
                                  label = { Text("recipe name") }, singleLine = true,
                                  shape = FieldShape)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Field(
    a: ToolArg,
    state: ToolFormState,
    onEdit: (String, FieldValue) -> Unit,
    onBrowsePath: (String) -> Unit,
) {
    val v = state.values[a.name] ?: FieldValue()
    val touched = a.name in state.touched
    val err = FormEngine.visibleError(a, v, touched)
    val unit = a.x_trmx?.unit
    var secretRevealed by rememberSaveable(a.name) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.label.ifEmpty { a.name }, fontWeight = FontWeight.Medium,
                 style = MaterialTheme.typography.bodyMedium)
            if (a.required) {
                Text(" *", color = MaterialTheme.colorScheme.tertiary,
                     fontWeight = FontWeight.Bold)
            }
        }

        when (FormEngine.widgetFor(a)) {
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = v.bool, onCheckedChange = { onEdit(a.name, v.copy(bool = it)) })
                Spacer(Modifier.width(Sp.s))
                Text(if (v.bool) "on" else "off",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "segmented" -> SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                (a.enum ?: emptyList()).forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = v.text == option,
                        onClick = { onEdit(a.name, FieldValue(text = option)) },
                        shape = SegmentedButtonDefaults.itemShape(i, a.enum?.size ?: 0),
                    ) { Text(option) }
                }
            }
            "chips" -> Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                (a.enum ?: emptyList()).forEach { option ->
                    FilterChip(selected = v.text == option,
                               onClick = { onEdit(a.name, FieldValue(text = option)) },
                               label = { Text(option) })
                }
            }
            "slider" -> {
                val min = (a.min ?: 0.0).toFloat()
                val max = (a.max ?: 100.0).toFloat()
                val current = (v.text.toFloatOrNull() ?: min).coerceIn(min, max)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = current,
                        onValueChange = {
                            onEdit(a.name, FieldValue(text =
                                FormEngine.sliderValueText(a.type, it)))
                        },
                        valueRange = min..max,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sp.s))
                    Text((v.text.ifBlank { current.toString() }) + (unit?.let { " $it" } ?: ""),
                         fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> OutlinedTextField(
                value = if (a.type == "bool") "" else v.text,
                onValueChange = { if (a.type != "bool") onEdit(a.name, v.copy(text = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = FieldShape,
                isError = err != null,
                supportingText = {
                    when {
                        err != null -> Text("⚠ $err",
                            color = MaterialTheme.colorScheme.error)
                        a.help != null -> Text(a.help)
                    }
                },
                trailingIcon = when {
                    a.type == "path" -> {
                        {
                            IconButton(onClick = { onBrowsePath(a.name) }) {
                                Text("📁")
                            }
                        }
                    }
                    a.x_trmx?.secret == true -> {
                        {
                            IconButton(onClick = { secretRevealed = !secretRevealed }) {
                                Text(if (secretRevealed) "🙈" else "👁")
                            }
                        }
                    }
                    else -> null
                },
                visualTransformation =
                    if (a.x_trmx?.secret == true && !secretRevealed) PasswordVisualTransformation()
                    else VisualTransformation.None,
                suffix = unit?.let { { Text(it) } },
                keyboardOptions = when (a.type) {
                    "int", "float" -> KeyboardOptions(keyboardType = KeyboardType.Number)
                    "url" -> KeyboardOptions(keyboardType = KeyboardType.Uri)
                    else -> KeyboardOptions.Default
                },
            )
        }

        // help text for non-field widgets (fields show it in supportingText)
        if (FormEngine.widgetFor(a) != "field" && a.help != null && err == null) {
            Text(a.help, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (err != null && FormEngine.widgetFor(a) != "field") {
            Text("⚠ $err", style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Quote for display only — the preview strip, not real shell parsing. */
private fun shellWord(w: String): String =
    if (w.contains(' ') || w.contains('"')) "\"$w\"" else w
