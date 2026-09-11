package dev.trmx.gui.ui

/*
 * Dynamic tool form (Ph 9 → UX-audit P4 rebuild). Schema-driven fields with
 * M3 floating labels ON the field outline, FilterChip enums, bounded
 * sliders, x_trmx secrets/units, vector icons everywhere — and a sticky
 * full-width primary action. Errors only after touch (Ph 9.5); the argv
 * preview stays the honesty centerpiece.
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import dev.trmx.gui.ui.Tokens.Palette as P

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

    Column(modifier = Modifier.fillMaxSize()) {
        TrmxTopBar(
            title = schema.name,
            onBack = onBack,
            subtitle = if (state.stepIndex != null)
                "editing chain step ${state.stepIndex + 1}" else null,
            actions = {
                // Save-recipe lives in the header (P4): the sticky bar is
                // reserved for the ONE primary action.
                if (state.stepIndex == null) {
                    IconButton(onClick = { saveRecipe = true }) {
                        Icon(Icons.Outlined.StarBorder, contentDescription = "save recipe",
                             tint = P.TextSecondary)
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.m),   // 16 dp grid (P4)
        ) {
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }

            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                if (schema.description.isNotBlank()) {
                    Text(schema.description, style = MaterialTheme.typography.bodyMedium,
                         color = P.TextSecondary)
                }
            }

            schema.args.forEach { a -> Field(a, state, onEdit, onBrowsePath) }

            if (schema.examples.isNotEmpty()) {
                Text("Try:", style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary)
                ActionFlowRow {
                    schema.examples.take(3).forEach { ex ->
                        FilterChip(selected = false, onClick = { onExample(ex) },
                                   colors = TrmxChipColors(),
                                   label = { Text(ex.label) })
                    }
                }
            }

            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("will run on the phone:", style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                    Text(preview.joinToString(" ") { shellWord(it) },
                         fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (preview.any { it.startsWith("~/") }) {
                        Text("paths are resolved against the Termux home by the bridge",
                             style = MaterialTheme.typography.bodySmall, fontSize = 10.sp,
                             color = P.TextSecondary)
                    }
                }
            }
        }

        // Sticky primary action (P4): full-width, 48 dp, ice-cyan fill,
        // pure black bold text — always visible without scrolling. A
        // premature tap still marks every field touched (Ph 9.5).
        Surface(
            color = P.Background,
            border = BorderStroke(1.dp, P.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(Sp.m)) {
                if (state.stepIndex == null) {
                    TrmxButton(
                        label = if (schema.risk_tier == "safe") "Run" else "Run — confirm first",
                        onClick = {
                            if (schema.risk_tier == "safe") onSubmit() else confirmRun = true
                        },
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        leading = if (state.submitting) {
                            {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                    color = P.OnAccent,
                                )
                            }
                        } else {
                            { Icon(Icons.Filled.PlayArrow, contentDescription = null,
                                   tint = P.OnAccent) }
                        },
                    )
                } else {
                    TrmxButton(
                        label = "Save to step",
                        onClick = onSubmit,
                        enabled = submittable && !state.submitting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
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
                TrmxTextField(value = title, onValueChange = { title = it },
                              label = "recipe name")
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
    state: ToolFormState,
    onEdit: (String, FieldValue) -> Unit,
    onBrowsePath: (String) -> Unit,
) {
    val v = state.values[a.name] ?: FieldValue()
    val touched = a.name in state.touched
    val err = FormEngine.visibleError(a, v, touched)
    val unit = a.x_trmx?.unit
    var secretRevealed by rememberSaveable(a.name) { mutableStateOf(false) }
    val widget = FormEngine.widgetFor(a)
    val fieldLabel = a.label.ifEmpty { a.name } + if (a.required) " *" else ""
    val autoMkdir = a.mkdir && a.path_kind == "dir"
    val helpText = when {
        a.help != null && autoMkdir -> a.help + " (folder is created if missing)"
        a.help != null -> a.help
        autoMkdir -> "folder is created if missing"
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        // Text fields carry their label INSIDE the outline (M3 floating
        // label, P4); the other widgets keep an external label row.
        if (widget != "field") {
            Text(fieldLabel, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
        }

        when (widget) {
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = v.bool, onCheckedChange = { onEdit(a.name, v.copy(bool = it)) })
                Spacer(Modifier.width(Sp.s))
                Text(if (v.bool) "on" else "off",
                     style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary)
            }
            "segmented", "chips" -> ActionFlowRow {
                (a.enum ?: emptyList()).forEach { option ->
                    FilterChip(
                        selected = v.text == option,
                        onClick = { onEdit(a.name, FieldValue(text = option)) },
                        colors = TrmxChipColors(),
                        label = { Text(option) },
                    )
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
            else -> TrmxTextField(
                value = if (a.type == "bool") "" else v.text,
                onValueChange = { if (a.type != "bool") onEdit(a.name, v.copy(text = it)) },
                label = fieldLabel,
                modifier = Modifier.fillMaxWidth(),
                isError = err != null,
                supportingText = {
                    when {
                        err != null -> Text(err, color = MaterialTheme.colorScheme.error)
                        helpText != null -> Text(helpText)
                    }
                },
                trailingIcon = when {
                    a.type == "path" -> {
                        {
                            IconButton(onClick = { onBrowsePath(a.name) }) {
                                Icon(Icons.Outlined.Folder, contentDescription = "browse",
                                     tint = P.TextSecondary)
                            }
                        }
                    }
                    a.x_trmx?.secret == true -> {
                        {
                            IconButton(onClick = { secretRevealed = !secretRevealed }) {
                                Icon(
                                    if (secretRevealed) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = "reveal",
                                    tint = P.TextSecondary)
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

        // helper/error text for non-field widgets (fields use supportingText)
        if (widget != "field") {
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            } else if (helpText != null) {
                Text(helpText, style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary)
            }
        }
    }
}

/** Shared chip styling (P4): dark fill, cyan active container. */
@Composable
fun TrmxChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = P.FieldFill,
    labelColor = P.TextPrimary,
    selectedContainerColor = P.AccentContainer,
    selectedLabelColor = P.OnAccentContainer,
    selectedLeadingIconColor = P.Accent,
)

/** Quote for display only — the preview strip, not real shell parsing. */
private fun shellWord(w: String): String =
    if (w.contains(' ') || w.contains('\"')) "\"$w\"" else w
