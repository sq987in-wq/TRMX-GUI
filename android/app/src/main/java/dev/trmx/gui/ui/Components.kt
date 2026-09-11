package dev.trmx.gui.ui

/*
 * TRMX component foundation (UX-audit P3, ADR-013): the reusable widgets
 * every screen is built from. Adding a future screen (AI schema builder,
 * services, daemon settings) means composing these — zero ad-hoc styling.
 *
 *   TrmxTopBar    — M3 top app bar, token colors, back arrow + title
 *   TrmxCard      — elevated container: Surface color, 1 dp outline, Radius.l
 *   TrmxButton    — Primary (ice-cyan) / Secondary (outline) / Ghost / Danger
 *   TrmxTextField — dark input field, tokens + FieldShape
 *   ActionFlowRow — action cluster that wraps whole buttons, never squeezes
 *   CopyableId    — monospace id that copies on tap
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ui.Tokens.Palette as P

// ---- TrmxTopBar ------------------------------------------------------------

/** Standard screen header: back arrow (optional), title, optional subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrmxTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(subtitle,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "back")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = P.Background,
            titleContentColor = P.TextPrimary,
            navigationIconContentColor = P.TextSecondary,
            actionIconContentColor = P.TextSecondary,
        ),
    )
}

// ---- TrmxCard ---------------------------------------------------------------

/**
 * Elevated container on the OLED ground: Surface color + 1 dp outline +
 * Radius.l. Border-defined elevation (no shadows on black).
 */
@Composable
fun TrmxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: Boolean = true,
    contentPadding: Dp = Tokens.Space.m,   // exactly 16 dp (P4 contract)
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = P.Surface,
        contentColor = P.TextPrimary,
    )
    val stroke = if (border) BorderStroke(1.dp, P.Border) else null
    val shape = RoundedCornerShape(Tokens.Radius.l)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = stroke,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = stroke,
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}


// ---- TrmxButton -------------------------------------------------------------

enum class TrmxButtonKind { Primary, Secondary, Ghost, Danger }

/**
 * The button. Primary = ice-cyan fill (the ONE loud element per screen);
 * Secondary = quiet outline; Ghost = text-only; Danger = semantic red.
 */
@Composable
fun TrmxButton(
    label: String,
    onClick: () -> Unit,
    kind: TrmxButtonKind = TrmxButtonKind.Primary,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Tokens.Radius.s)
    when (kind) {
        TrmxButtonKind.Primary -> Button(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = P.Accent, contentColor = P.OnAccent,
                disabledContainerColor = P.SurfaceHigh, disabledContentColor = P.TextMuted),
        ) { ButtonContent(leading, label, bold = true) }

        TrmxButtonKind.Secondary -> OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp,
                if (enabled) P.BorderStrong else P.Border),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = P.TextPrimary,
                disabledContentColor = P.TextMuted),
        ) { ButtonContent(leading, label) }

        TrmxButtonKind.Ghost -> TextButton(
            onClick = onClick, enabled = enabled, modifier = modifier,
            colors = ButtonDefaults.textButtonColors(
                contentColor = P.TextSecondary,
                disabledContentColor = P.TextMuted),
        ) { ButtonContent(leading, label) }

        TrmxButtonKind.Danger -> OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = shape,
            border = BorderStroke(1.dp, if (enabled) P.Danger.copy(alpha = 0.55f) else P.Border),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = P.Danger,
                disabledContentColor = P.TextMuted),
        ) { ButtonContent(leading, label) }
    }
}

@Composable
private fun ButtonContent(leading: (@Composable () -> Unit)?, label: String,
                          bold: Boolean = false) {
    if (leading != null) {
        leading()
        Spacer(Modifier.width(Sp.xs))
    }
    Text(label, fontWeight = if (bold) FontWeight.Bold else null)
}

/** Icon-only button for compact toolbars (up / refresh / overflow). */
@Composable
fun TrmxIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = P.TextSecondary,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

// ---- TrmxTextField ----------------------------------------------------------

/**
 * The text field: dark SurfaceHigh container, token borders/cursor,
 * FieldShape. Thin wrapper over M3 OutlinedTextField so every parameter
 * (visual transformation, keyboard options, trailing icons) stays
 * available — one place to restyle the world.
 */
@Composable
fun TrmxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        shape = FieldShape,
        colors = TrmxFieldColors(),
        label = { Text(label) },   // floats ON the outline border (M3) — the P4 fix
        supportingText = supportingText,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
    )
}

// ---- ActionFlowRow ----------------------------------------------------------

/** Button cluster that wraps whole buttons instead of squeezing (Ph P0). */
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

// ---- navigation --------------------------------------------------------------

/** Active state: pill with deep-contrast container + ice-cyan icon (P4). */
@Composable
fun TrmxNavItemColors() = androidx.compose.material3.NavigationBarItemDefaults.colors(
    selectedIconColor = P.Accent,
    selectedTextColor = P.Accent,
    unselectedIconColor = P.TextMuted,
    unselectedTextColor = P.TextMuted,
    indicatorColor = P.AccentContainer,
)

// ---- CopyableId -------------------------------------------------------------

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
            color = P.TextMuted,
        )
        Spacer(Modifier.width(Sp.xs))
        Icon(Icons.Outlined.ContentCopy, contentDescription = "copy",
             tint = P.TextMuted, modifier = Modifier.size(12.dp))
    }
}
