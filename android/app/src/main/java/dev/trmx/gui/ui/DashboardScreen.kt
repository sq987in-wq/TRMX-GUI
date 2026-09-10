package dev.trmx.gui.ui

/*
 * Home = Command Center (UX-audit P1+P2, ADR-012).
 *   1. status pill (connected / N running / error) → opens Diagnostics
 *   2. hero workspace when idle — an inviting start, not a dead task list
 *   3. INTENT cards for the full runtime (never just a downloader):
 *      Download media · Convert/transcode · Run a command · Start a service
 *   4. tasks with human labels (JobLabels), J-ID secondary copyable
 *   5. quick run (saved recipes)
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
import androidx.compose.material3.Card
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
            Text("TRMX", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            StatusPill(state, onOpenDiagnostics)
        }

        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.primary)
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
            TasksCard(state.jobs, onJobClick)
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
        !connected -> "● connection error" to MaterialTheme.colorScheme.error
        state.refreshing -> "○ refreshing" to TrmxColors.Running
        running > 0 -> "● $running running" to TrmxColors.Running
        else -> "● connected" to TrmxColors.Completed
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier.clickable(onClick = onOpenDiagnostics),
    ) {
        Text(
            "$label  ⚙",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Idle Home: an inviting workspace instead of a dead task list (P2). */
@Composable
private fun HeroWorkspace() {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Intent-based action cards for the FULL runtime (P2): TRMX is not a
 * downloader — download, convert, arbitrary commands and long-running
 * services are one tap away, on equal footing.
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
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.xs),
        ) {
            Text(icon, fontSize = 20.sp)
            Text(title, fontWeight = FontWeight.Medium,
                 style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Saved recipes: the fastest path from goal to filled form. */
@Composable
private fun QuickRunCard(recipes: List<Recipe>, onOpenRecipe: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.s),
        ) {
            Text("Quick run", fontWeight = FontWeight.Bold)
            recipes.take(4).forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecipe(r.id) },
                ) {
                    Text("▸", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Sp.s))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.toolId, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Tasks: human label first, status verb, relative time, J-ID copyable. */
@Composable
private fun TasksCard(jobs: List<JobSummary>, onJobClick: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${jobs.size}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            jobs.take(20).forEach { job -> TaskRow(job, onJobClick) }
        }
    }
}

@Composable
private fun TaskRow(job: JobSummary, onJobClick: (String) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onJobClick(job.job_id) },
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            CopyableId(job.job_id)
        }
        if (job.progress_pct != null) {
            LinearProgressIndicator(
                progress = { (job.progress_pct / 100.0).toFloat() },
                modifier = Modifier.fillMaxWidth())
            job.progress_detail?.let {
                Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            job.progress_detail?.let {
                Text("  $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
