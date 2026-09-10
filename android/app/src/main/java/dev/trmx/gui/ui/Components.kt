package dev.trmx.gui.ui

/*
 * Shared UI primitives (UX-audit round, ADR-012).
 *
 * ActionFlowRow — action button clusters WRAP to the next line instead of
 *   squeezing the last button into a vertical sliver. That sliver was the
 *   "Sto / p / brid / ge" bug: a plain Row measures children left to right
 *   and the last child gets only the remaining width, so its label wrapped
 *   mid-word. Every button cluster in the app goes through this wrapper.
 *
 *   Deliberately a hand-rolled Layout (~45 lines) rather than foundation's
 *   FlowRow: FlowRow is experimental + inline, and passing a composable
 *   lambda through its content slot cost three CI rounds (the offline
 *   sandbox cannot compile-check Compose — ADR-006). Stable MeasureScope/
 *   Placeable APIs only, exact spacing control, works on every version.
 * CopyableId — monospace id with a copy affordance. Raw J-IDs stop being
 *   the primary identity of a task but stay one tap away for fail-loud bug
 *   reports.
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** Button cluster that wraps instead of squeezing (see file header). */
@Composable
fun ActionFlowRow(
    modifier: Modifier = Modifier,
    horizontalGap: Dp = Sp.s,
    verticalGap: Dp = Sp.s,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val hGap = with(density) { horizontalGap.roundToPx() }
    val vGap = with(density) { verticalGap.roundToPx() }
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        // Loose child constraints: each button takes its natural width.
        val child = constraints.copy(minWidth = 0, minHeight = 0)
        val rows = mutableListOf<MutableList<Placeable>>()
        val widths = mutableListOf<Int>()
        var row = mutableListOf<Placeable>()
        var rowWidth = 0
        for (m in measurables) {
            val p = m.measure(child)
            val needed = if (row.isEmpty()) p.width else rowWidth + hGap + p.width
            if (row.isNotEmpty() && needed > constraints.maxWidth) {
                rows += row
                widths += rowWidth
                row = mutableListOf()
                rowWidth = 0
            }
            if (row.isNotEmpty()) rowWidth += hGap
            rowWidth += p.width
            row += p
        }
        if (row.isNotEmpty()) {
            rows += row
            widths += rowWidth
        }
        fun rowHeight(r: List<Placeable>) = r.maxOf { it.height }
        val height = rows.foldIndexed(0) { i, acc, r ->
            acc + rowHeight(r) + if (i > 0) vGap else 0
        }
        val width = maxOf(widths.maxOrNull() ?: 0, constraints.minWidth)
        layout(width, height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            for (r in rows) {
                var x = 0
                for (p in r) {
                    p.placeRelative(x, y)
                    x += p.width + hGap
                }
                y += rowHeight(r) + vGap
            }
        }
    }
}

/** Small monospace id line that copies itself on tap. */
@Composable
fun CopyableId(id: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = modifier.clickable { clipboard.setText(AnnotatedString(id)) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            id,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            " ⧉",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
