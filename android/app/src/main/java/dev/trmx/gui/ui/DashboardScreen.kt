package dev.trmx.gui.ui

/*
 * Home = Command Center (UX-audit P1, ADR-012). Inverted hierarchy:
 *   1. status pill (connected / N running / error) → opens Diagnostics
 *   2. quick run — saved recipes, one tap to a filled form
 *   3. tasks — human outcome labels (JobLabels), J-ID secondary copyable
 * The bridge metrics dump, Refresh, Stop bridge and Re-run setup moved to
 * the Diagnostics sheet; the manual argv dialog moved to the Toolbox
 * ("Custom command"). The job list still updates via SSE events.
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

        if (recipes.isNotEmpty()) {
            QuickRunCard(recipes, onOpenRecipe)
        }

        TasksCard(state.jobs, onJobClick)
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
            if (jobs.isEmpty()) {
                Text(
                    "Nothing has run yet — open Tools and pick one.",
                    style = MaterialTheme.typography.bodyMedium,
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
