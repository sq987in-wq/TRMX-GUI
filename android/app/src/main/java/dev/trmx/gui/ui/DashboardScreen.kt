package dev.trmx.gui.ui

/*
 * Home = Command Center (UX-audit P1–P3, ADR-012/013), per the
 * home_dashboard blueprint: compact status pill → hero workspace (idle) →
 * 4 prominent intent cards for the FULL runtime → active & recent task
 * OUTCOME cards → quick run (recipes). Built exclusively from Trmx
 * components + Tokens — zero ad-hoc styling.
 */

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.DashboardState
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.tools.JobLabels
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun DashboardScreen(
    state: DashboardState,
    recipes: List<Recipe>,
    onOpenDiagnostics: () -> Unit,
    onJobClick: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenIntent: (String) -> Unit,
    onOpenCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TRMX", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            StatusPill(state, onOpenDiagnostics)
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        if (state.jobs.isEmpty()) {
            HeroWorkspace()
        }

        IntentGrid(onOpenIntent = onOpenIntent, onOpenCustom = onOpenCustom)

        if (state.jobs.isNotEmpty()) {
            Text("Active & recent",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
            state.jobs.take(10).forEach { job ->
                TaskOutcomeCard(job, onJobClick)
            }
        }

        if (recipes.isNotEmpty()) {
            QuickRunCard(recipes, onOpenRecipe)
        }
    }
}

/** Compact health surface; tap opens the Diagnostics sheet. */
@Composable
private fun StatusPill(state: DashboardState, onOpenDiagnostics: () -> Unit) {
    val connected = state.error == null
    val running = state.info?.load?.jobs_running ?: 0
    val (label, color) = when {
        !connected -> "● connection error" to P.Danger
        state.refreshing -> "○ refreshing" to P.Accent
        running > 0 -> "● $running running" to P.Accent
        else -> "● connected" to P.Steel
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = P.Surface,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.clickable(onClick = onOpenDiagnostics),
    ) {
        Text(
            "$label  ⚙",
            color = color,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Idle Home: an inviting workspace instead of a dead task list. */
@Composable
private fun HeroWorkspace() {
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Sp.l),
            verticalArrangement = Arrangement.spacedBy(Sp.xs),
        ) {
            Text(
                "Your Linux runtime,\non this phone.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text(
                "Run tools, scripts and services locally — no cloud, no root. " +
                    "Start with a goal:",
                style = MaterialTheme.typography.bodyMedium,
                color = P.TextSecondary)
        }
    }
}

/**
 * Intent cards for the FULL runtime (never just a downloader): download,
 * convert, arbitrary commands, long-running services — equal footing.
 */
@Composable
private fun IntentGrid(
    onOpenIntent: (String) -> Unit,
    onOpenCustom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            IntentCard(
                icon = "⬇", title = "Download media", sub = "yt-dlp · video & audio",
                modifier = Modifier.weight(1f),
            ) { onOpenIntent("yt-dlp") }
            IntentCard(
                icon = "▶", title = "Convert / transcode", sub = "ffmpeg · any format",
                modifier = Modifier.weight(1f),
            ) { onOpenIntent("ffmpeg") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
            IntentCard(
                icon = "⚡", title = "Run a command", sub = "any argv · scripts",
                modifier = Modifier.weight(1f),
            ) { onOpenCustom() }
            IntentCard(
                icon = "◎", title = "Start a service", sub = "serve a folder over HTTP",
                modifier = Modifier.weight(1f),
            ) { onOpenIntent("http-server") }
        }
    }
}

@Composable
private fun IntentCard(
    icon: String,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TrmxCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.xs),
        ) {
            Text(icon, fontSize = 20.sp)
            Text(title, fontWeight = FontWeight.Medium,
                 style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary)
        }
    }
}

/** Saved recipes: the fastest path from goal to filled form. */
@Composable
private fun QuickRunCard(recipes: List<Recipe>, onOpenRecipe: (String) -> Unit) {
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.s),
        ) {
            Text("Quick run", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            recipes.take(4).forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecipe(r.id) },
                ) {
                    Text("▸", color = P.Accent)
                    Spacer(Modifier.width(Sp.s))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.toolId, style = MaterialTheme.typography.bodySmall,
                             color = P.TextSecondary)
                    }
                    Text("›", color = P.TextMuted)
                }
            }
        }
    }
}

/** One task = one outcome card: human label, status verb, J-ID copyable. */
@Composable
private fun TaskOutcomeCard(job: JobSummary, onJobClick: (String) -> Unit) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onJobClick(job.job_id) },
    ) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JobLabels.taskLabel(job),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    JobLabels.statusVerb(job.status),
                    color = TrmxColors.status(job.status),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JobLabels.subtitle(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CopyableId(job.job_id)
            }
            if (job.progress_pct != null) {
                LinearProgressIndicator(
                    progress = { (job.progress_pct / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = P.Accent, trackColor = P.SurfaceHighest)
                job.progress_detail?.let {
                    Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                         color = P.TextMuted)
                }
            } else {
                job.progress_detail?.let {
                    Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                         color = P.TextMuted)
                }
            }
        }
    }
}
