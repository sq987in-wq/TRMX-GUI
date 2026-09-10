package dev.trmx.gui.ui

/*
 * New-job dialog. The argv editor is deliberately line-based — one line =
 * one argument. There is no shell parsing, quoting or escaping: no
 * injection surface by construction (argv-first, ADR-001).
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.trmx.gui.SubmitFormState

@Composable
fun SubmitDialog(
    state: SubmitFormState,
    onName: (String) -> Unit,
    onArgv: (String) -> Unit,
    onCwd: (String) -> Unit,
    onTimeout: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New job") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    colors = TrmxFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.argvText,
                    onValueChange = onArgv,
                    label = { Text("Arguments — one per line") },
                    placeholder = {
                        Text("ffmpeg\n-i ~/in.mkv\n~/out.mp4", fontFamily = FontFamily.Monospace)
                    },
                    minLines = 4,
                    colors = TrmxFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.cwd,
                    onValueChange = onCwd,
                    label = { Text("Working directory (optional, default ~)") },
                    singleLine = true,
                    colors = TrmxFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = state.timeoutText,
                    onValueChange = onTimeout,
                    label = { Text("Timeout in seconds (optional)") },
                    singleLine = true,
                    colors = TrmxFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Text(
                    "Each line is passed to the bridge as exactly one argument — " +
                        "no shell is involved.",
                    style = MaterialTheme.typography.bodySmall)
                state.errors.forEach { err ->
                    Text(
                        "${err.field}: ${err.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !state.submitting) {
                Text(if (state.submitting) "Submitting…" else "Run")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
