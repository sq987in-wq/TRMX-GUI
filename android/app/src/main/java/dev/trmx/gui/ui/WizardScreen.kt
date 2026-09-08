package dev.trmx.gui.ui

/*
 * First-run wizard: the three consents (Termux's security boundary —
 * CONTROL-PLANE.md §1), then the intent-driven install sequence with
 * per-step status and honest failure states.
 */

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.wizard.WizardState
import dev.trmx.gui.wizard.WizardStep

@Composable
fun WizardScreen(
    state: WizardState,
    termuxInstalled: Boolean,
    onConsentTermux: (Boolean) -> Unit,
    onConsentRunCommand: (Boolean) -> Unit,
    onConsentAllowExternal: (Boolean) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onBegin: () -> Unit,
    onSkipWait: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("TRMX", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            if (state.step == WizardStep.WELCOME) "Set up the Termux backend"
            else "Setting up the Termux backend",
            style = MaterialTheme.typography.bodyLarge,
        )

        if (state.step == WizardStep.WELCOME) {
            WelcomeBody(
                state, termuxInstalled,
                onConsentTermux, onConsentRunCommand, onConsentAllowExternal,
                onBaseUrlChanged, onBegin)
        } else {
            ProgressBody(state, onSkipWait, onRetry, onReset)
        }
    }
}

// ---- welcome / consents ------------------------------------------------

@Composable
private fun WelcomeBody(
    state: WizardState,
    termuxInstalled: Boolean,
    onConsentTermux: (Boolean) -> Unit,
    onConsentRunCommand: (Boolean) -> Unit,
    onConsentAllowExternal: (Boolean) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onBegin: () -> Unit,
) {
    if (!termuxInstalled) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Termux is not installed", fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.error)
                Text(
                    "Install Termux from GitHub Releases (termux.dev). Do not mix sources: " +
                        "GitHub and F-Droid builds must never be combined — same app, " +
                        "different signatures.")
            }
        }
    }

    ConsentCard(
        title = "1 · Termux is installed and opened",
        body = "Termux must have been launched at least once so its environment exists.",
        checked = state.consentTermux,
        onCheckedChange = onConsentTermux)

    ConsentCard(
        title = "2 · RUN_COMMAND permission granted",
        body = "Android App info → Permissions → Additional permissions → allow " +
            "\"Termux:Run Command\" for TRMX.",
        checked = state.consentRunCommand,
        onCheckedChange = onConsentRunCommand,
        action = { OpenAppDetailsButton() })

    ConsentCard(
        title = "3 · allow-external-apps enabled",
        body = "In Termux, edit ~/.termux/termux.properties and add the line below, " +
            "then run termux-reload-settings and reopen Termux.",
        checked = state.consentAllowExternal,
        onCheckedChange = onConsentAllowExternal,
        action = { CopyLineButton("allow-external-apps=true") })

    OutlinedTextField(
        value = state.baseUrl,
        onValueChange = onBaseUrlChanged,
        label = { Text("Install source (base URL)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        supportingText = {
            Text("Where install.sh and the bridge are fetched from (SHA256-verified).")
        })

    state.failure?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }

    Button(
        onClick = onBegin,
        enabled = state.allConsentsGiven,
        modifier = Modifier.fillMaxWidth()) {
        Text("Install backend & connect")
    }
    Text(
        "This installs Python, downloads the TRMX bridge (~/.trmx) and starts it. " +
            "Nothing runs as root; everything stays inside Termux.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ConsentCard(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Text(title, fontWeight = FontWeight.Bold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
private fun OpenAppDetailsButton() {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                       Uri.fromParts("package", context.packageName, null)))
        }
    }) { Text("Open TRMX app details") }
}

@Composable
private fun CopyLineButton(line: String) {
    val clipboard = LocalClipboardManager.current
    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(line)) }) {
        Text("Copy:  $line", fontFamily = FontFamily.Monospace)
    }
}

// ---- install sequence ----------------------------------------------------

@Composable
private fun ProgressBody(
    state: WizardState,
    onSkipWait: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    val stepNumber = when (state.step) {
        WizardStep.INSTALL_PY -> 1
        WizardStep.INSTALL -> 2
        WizardStep.PAIR -> 3
        WizardStep.START -> 4
        WizardStep.HANDSHAKE -> 5
        else -> 5
    }
    Text("Step $stepNumber of 5 — ${state.step.name}", fontWeight = FontWeight.Bold)

    if (state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Text(state.detail.ifEmpty { "working…" })
        }
    } else {
        Text(state.detail)
    }

    if (state.skippable) {
        TextButton(onClick = onSkipWait) {
            Text("Python is already installed — continue")
        }
    }

    state.failure?.let { fail ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup problem", fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.error)
                Text(fail, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("Retry this step") }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = onReset) { Text("Start over") }
                }
            }
        }
    }

    // Escape hatch: if intents cannot reach Termux, pair manually.
    if (state.step == WizardStep.HANDSHAKE && state.failure != null && state.token.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Manual pairing (if Termux is not running our commands)",
                     fontWeight = FontWeight.Bold)
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.token)) }) {
                    Text("Copy pairing token", fontFamily = FontFamily.Monospace)
                }
                Text(
                    "In Termux, run:\n" +
                        "  ~/.trmx/trmx stop\n" +
                        "  ~/.trmx/trmx pair <paste-token>\n" +
                        "  ~/.trmx/trmx start\n" +
                        "then tap “Retry this step” here.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
