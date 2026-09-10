package dev.trmx.gui.ui

/*
 * Terminal-luxe design system (Phase 9.5, ADR-011).
 *
 * Identity: a premium dark terminal — deep-slate surfaces, terminal-green
 * primary, sky-blue secondary, amber tertiary for confirm-tier accents.
 * Data (paths, argv, sizes) stays monospace; everything else uses the
 * full Material 3 type scale. Spacing on a strict 4/8/16/24 grid.
 */

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---- palette --------------------------------------------------------------

private val Green = Color(0xFF34D399)          // terminal green (brand)
private val GreenDim = Color(0xFF0F3D2E)
private val GreenBright = Color(0xFFA7F3D0)
private val Sky = Color(0xFF7DD3FC)            // secondary accent
private val SkyDim = Color(0xFF113148)
private val SkyBright = Color(0xFFBAE6FD)
private val Amber = Color(0xFFFBBF24)          // tertiary / confirm accents
private val Slate950 = Color(0xFF0B1220)       // app background
private val Slate900 = Color(0xFF0F172A)       // surface
private val Slate800 = Color(0xFF1E293B)       // surface variant / containers
private val Slate700 = Color(0xFF334155)       // outlines
private val Slate300 = Color(0xFFCBD5E1)       // on-surface
private val Slate400 = Color(0xFF94A3B8)       // on-surface variant
private val Red = Color(0xFFF87171)
private val RedDim = Color(0xFF3F1D1D)

/** Terminal-luxe dark scheme (the only scheme in v1 — dark by identity). */
private val TerminalLuxeScheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF042417),
    primaryContainer = GreenDim,
    onPrimaryContainer = GreenBright,
    secondary = Sky,
    onSecondary = Color(0xFF07293B),
    secondaryContainer = SkyDim,
    onSecondaryContainer = SkyBright,
    tertiary = Amber,
    onTertiary = Color(0xFF3A2B06),
    tertiaryContainer = Color(0xFF3F2F0A),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = Slate950,
    onBackground = Slate300,
    surface = Slate900,
    onSurface = Slate300,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    surfaceContainer = Color(0xFF141E30),
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Color(0xFF243247),
    outline = Slate700,
    outlineVariant = Color(0xFF1A2436),
    error = Red,
    onError = Color(0xFF2B0B0B),
    errorContainer = RedDim,
    onErrorContainer = Color(0xFFFECACA),
)

// ---- spacing / shape tokens -----------------------------------------------

/** Strict spacing grid: 4 / 8 / 16 / 24. */
object Sp {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
}

/** Shared field corner — the signature of the form overhaul. */
val FieldShape = RoundedCornerShape(12.dp)

private val TerminalLuxeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---- semantic status colors (single source of truth) -----------------------

object TrmxColors {
    val Running = Green
    val Completed = Sky
    val Failed = Red
    val Cancelled = Color(0xFF94A3B8)
    val Queued = Amber
    val TierSafe = Green
    val TierConfirm = Amber
    val TierDestructive = Red
    val Symlink = Color(0xFF5EEAD4)

    fun status(s: String): Color = when (s) {
        "RUNNING", "STARTING" -> Running
        "COMPLETED" -> Completed
        "FAILED" -> Failed
        "CANCELLED", "LOST" -> Cancelled
        "QUEUED" -> Queued
        "CANCELLING" -> Amber
        else -> Cancelled
    }

    fun tier(t: String): Color = when (t) {
        "safe" -> TierSafe
        "confirm" -> TierConfirm
        "destructive" -> TierDestructive
        else -> Cancelled
    }
}

// ---- shared field colors (UX-audit P2) ------------------------------------

/**
 * One source of truth for every text field in the app: explicit dark
 * container + matching border/text/cursor colors, so no platform or
 * library default can ever paint a light field on the slate surface
 * (the "harsh bright-white input boxes" report — root cause was the
 * light XML theme, fixed in themes.xml; this is the belt-and-braces).
 */
@Composable
fun TrmxFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun TRMXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalLuxeScheme,
        typography = Typography(),
        shapes = TerminalLuxeShapes,
        content = content,
    )
}
