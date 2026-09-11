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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    onEditDescription: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenToolbox: () -> Unit,
    onOpenJob: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TrmxTopBar(
            title = "Schema Builder",
            subtitle = "describe it — the LLM writes the schema",
            onBack = onBack,
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
