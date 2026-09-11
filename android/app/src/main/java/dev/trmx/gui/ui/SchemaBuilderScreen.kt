package dev.trmx.gui.ui

/*
 * AI Schema Builder (AI round, ADR-014). One text field, one sticky
 * primary action — the phone-side trmx-ai wrapper does the rest:
 * description -> LLM -> validated schema -> ~/.trmx/tools/ai-*.json,
 * and the event-wired rescan makes the tool appear in the Toolbox.
 * Composed exclusively from the P4 foundation (Tokens, TrmxCard,
 * TrmxTopBar, TrmxTextField, TrmxButton, TrmxChipColors).
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.trmx.gui.AiConfigState
import dev.trmx.gui.SchemaBuilderState
import dev.trmx.gui.ui.Tokens.Palette as P

private val EXAMPLES = listOf(
    "Download a video with yt-dlp and save an audio-only copy",
    "Convert and resize images with ImageMagick mogrify",
    "Clone a git repository to a folder",
)

/** Example prompts shown as chips (P4 FilterChip styling). */
@Composable
fun SchemaBuilderScreen(
    state: SchemaBuilderState,
    aiToolInstalled: Boolean?,
    config: AiConfigState,
    onLoadConfig: () -> Unit,
    onSaveConfig: () -> Unit,
    onSetAiMode: (String) -> Unit,
    onSetAiProvider: (String) -> Unit,
    onSetAiEndpoint: (String) -> Unit,
    onSetAiModel: (String) -> Unit,
    onSetAiApiKey: (String) -> Unit,
    onForgetAiKey: () -> Unit,
    onSetAiCommand: (String) -> Unit,
    onEditDescription: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenJob: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfig by remember { mutableStateOf(false) }

    // Native backend settings (protocol 1.1, §15): edit ~/.trmx/ai.json
    // through the bridge instead of hand-editing it in Termux.
    if (showConfig) {
        AiConfigEditor(
            state = config,
            onSetMode = onSetAiMode,
            onSetProvider = onSetAiProvider,
            onSetEndpoint = onSetAiEndpoint,
            onSetModel = onSetAiModel,
            onSetApiKey = onSetAiApiKey,
            onForgetKey = onForgetAiKey,
            onSetCommand = onSetAiCommand,
            onSave = onSaveConfig,
            onBack = { showConfig = false },
            // CRITICAL (bug round): without the Scaffold inner padding the
            // sticky Save renders UNDER the NavigationBar — the button
            // "disappears". Every screen-level composable must consume its
            // modifier; the editor is no exception.
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        TrmxTopBar(
            title = "Schema Builder",
            subtitle = "describe it — the LLM writes the schema",
            onBack = onBack,
            actions = {
                IconButton(onClick = {
                    // Load once; re-opening must NOT clobber unsaved edits
                    // (state lives in the ViewModel and survives back).
                    if (!config.loaded) onLoadConfig()
                    showConfig = true
                }) {
                    Icon(Icons.Outlined.Tune, contentDescription = "AI backend settings",
                         tint = P.TextSecondary)
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.m),   // 16 dp grid
        ) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                             tint = P.Accent, modifier = Modifier.size(20.dp))
                        Text("Build a tool with AI",
                             style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Describe a command-line tool in plain words. A local LLM " +
                        "drafts a TRMX tool schema, the wrapper validates it against " +
                        "the protocol, and installs it to your toolbox.",
                        style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
                    Text(
                        "AI-generated tools start at the confirm tier — edit " +
                        "~/.trmx/tools/ai-*.json on the phone to relax that.",
                        style = MaterialTheme.typography.bodySmall, color = P.TextMuted)
                }
            }

            // availability honesty: without trmx-ai the primary action is a
            // dead end, so say so instead (fail loud, never silently).
            if (aiToolInstalled == false) {
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "trmx-ai is not installed on the phone — re-run the TRMX " +
                        "installer (Diagnostics → Install, or the first-run wizard) " +
                        "to add it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            TrmxTextField(
                value = state.description,
                onValueChange = onEditDescription,
                label = "What should the tool do?",
                singleLine = false,
                minLines = 4,
                isError = state.error != null,
                supportingText = {
                    when {
                        state.error != null ->
                            Text(state.error, color = MaterialTheme.colorScheme.error)
                        else -> Text("name the binary and what it should do")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Try:", style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
            ActionFlowRow {
                EXAMPLES.forEach { ex ->
                    FilterChip(
                        selected = state.description == ex,
                        onClick = { onEditDescription(ex) },
                        colors = TrmxChipColors(),
                        label = { Text(ex, maxLines = 1) },
                    )
                }
            }

            state.result?.let { result ->
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                        Text(result, style = MaterialTheme.typography.bodyMedium,
                             textAlign = TextAlign.Start)
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                            TrmxButton(label = "Open Toolbox", onClick = onOpenToolbox)
                            state.jobId?.let { id ->
                                TextButton(onClick = { onOpenJob(id) }) { Text("view job") }
                            }
                        }
                    }
                }
            }

            state.jobId?.let { id ->
                if (state.result == null) {
                    Text("job $id running — live output under Jobs",
                         style = MaterialTheme.typography.bodySmall, color = P.TextMuted)
                }
            }
        }

        // Sticky primary action (P4 pattern): full-width 48 dp, ice-cyan.
        Surface(color = P.Background, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(Sp.m)) {
                TrmxButton(
                    label = if (state.submitting) "Building…" else "Generate Tool",
                    onClick = onSubmit,
                    enabled = state.description.isNotBlank() && !state.submitting,
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
                        { Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                               tint = P.OnAccent) }
                    },
                )
            }
        }
    }
}

/** Providers for the Cloud API mode chips (§15). */
private val AI_PROVIDERS = listOf("groq", "openai", "gemini", "openai_compatible")

/**
 * Native editor for ~/.trmx/ai.json (protocol 1.1, §15): mode selector,
 * cloud fields (provider/model/endpoint/masked key), offline command —
 * saved through the bridge. The raw key never renders: only "saved"
 * state + a fresh input.
 */
@Composable
private fun AiConfigEditor(
    state: AiConfigState,
    onSetMode: (String) -> Unit,
    onSetProvider: (String) -> Unit,
    onSetEndpoint: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSetApiKey: (String) -> Unit,
    onForgetKey: () -> Unit,
    onSetCommand: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var keyVisible by remember { mutableStateOf(false) }
    val cloud = state.mode == "http_api"

    // modifier carries the Scaffold innerPadding — the sticky Save sits
    // ABOVE the bottom NavigationBar because of it.
    Column(modifier = modifier.fillMaxSize()) {
        TrmxTopBar(
            title = "AI Backend",
            subtitle = "~/.trmx/ai.json",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.m),
        ) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text("Backend mode", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text(
                        "offline keeps everything on the phone; cloud sends the " +
                        "description to the configured API (no multi-GB models).",
                        style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
                    ActionFlowRow {
                        FilterChip(
                            selected = !cloud,
                            onClick = { onSetMode("cli") },
                            colors = TrmxChipColors(),
                            label = { Text("Offline (Ollama)") },
                        )
                        FilterChip(
                            selected = cloud,
                            onClick = { onSetMode("http_api") },
                            colors = TrmxChipColors(),
                            label = { Text("Cloud API") },
                        )
                    }
                }
            }

            if (cloud) {
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                        Text("Cloud API", style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                        Text("Provider", style = MaterialTheme.typography.bodySmall,
                             color = P.TextSecondary)
                        ActionFlowRow {
                            AI_PROVIDERS.forEach { p ->
                                FilterChip(
                                    selected = state.provider == p,
                                    onClick = { onSetProvider(p) },
                                    colors = TrmxChipColors(),
                                    label = { Text(p) },
                                )
                            }
                        }
                        TrmxTextField(
                            value = state.model,
                            onValueChange = onSetModel,
                            label = "Model",
                            singleLine = true,
                            supportingText = {
                                Text("e.g. llama-3.3-70b-versatile (groq) · gpt-4o-mini " +
                                     "(openai) · gemini-2.0-flash (gemini)")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TrmxTextField(
                            value = state.endpoint,
                            onValueChange = onSetEndpoint,
                            label = "Endpoint (optional)",
                            singleLine = true,
                            supportingText = {
                                Text(if (state.provider == "openai_compatible")
                                         "required for openai_compatible"
                                     else "leave empty for the ${state.provider} default")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TrmxTextField(
                            value = state.apiKeyInput,
                            onValueChange = onSetApiKey,
                            label = if (state.apiKeySet) "API key (a key is saved)" else "API key",
                            singleLine = true,
                            visualTransformation =
                                if (keyVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                        contentDescription = "reveal",
                                        tint = P.TextSecondary)
                                }
                            },
                            supportingText = {
                                Text(when {
                                         state.clearKey -> "the saved key will be CLEARED on save"
                                         state.apiKeySet -> "leave blank to keep the saved key"
                                         else -> "not saved yet — or use an env var in Termux"
                                     })
                            },
                            isError = state.clearKey,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.apiKeySet) {
                            TextButton(onClick = onForgetKey) {
                                Text(if (state.clearKey) "undo — keep the saved key"
                                     else "forget saved key")
                            }
                        }
                    }
                }
            } else {
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                        Text("Offline (CLI)", style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                        TrmxTextField(
                            value = state.command,
                            onValueChange = onSetCommand,
                            label = "Command",
                            singleLine = true,
                            supportingText = {
                                Text("any runner taking the prompt as its last " +
                                     "argument, e.g. ollama run llama3.2")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "install ollama in Termux: pkg install ollama && ollama pull llama3.2",
                            style = MaterialTheme.typography.bodySmall, color = P.TextMuted)
                    }
                }
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
            }
            if (state.loading) {
                Text("loading backend config…", style = MaterialTheme.typography.bodySmall,
                     color = P.TextMuted)
            }
        }

        // Sticky primary action (P4 pattern): full-width 48 dp save.
        Surface(color = P.Background, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.padding(Sp.m)) {
                TrmxButton(
                    label = if (state.saving) "Saving…" else "Save Configuration",
                    onClick = onSave,
                    enabled = state.loaded && !state.saving,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    leading = if (state.saving) {
                        {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp),
                                color = P.OnAccent,
                            )
                        }
                    } else null,
                )
            }
        }
    }
}
