package dev.trmx.gui.ui

/*
 * TRMX design tokens (UX-audit P3, ADR-013) — the SINGLE source of truth
 * for every visual constant in the app. Screens and components may not
 * hardcode colors, radii or spacing; they reference Tokens.
 *
 * Palette: True-OLED pitch black ground + deep-contrast elevated surfaces
 * with 1 px outlines. Modern-tech accents: Electric Ice-Cyan (focus,
 * primary actions) + Steel Blue (calm/secondary). NO green/mint, NO
 * orange/amber anywhere (explicitly forbidden). Crisp white primary text,
 * muted slate metadata, semantic red ONLY for critical failures.
 *
 * NOTE: hex values derive from the written design directives (the
 * blueprint package did not persist into the workspace — see ADR-013);
 * centralizing them here makes a fidelity pass a one-file change.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Tokens {

    /** Ground + elevated surfaces (OLED). */
    object Palette {
        val Background = Color(0xFF000000)     // true OLED black (ground)
        val Surface = Color(0xFF0B111A)        // cards (P4 contract)
        val FieldFill = Color(0xFF0A0E17)      // input field fill (P4 contract)
        val SurfaceHigh = Color(0xFF121824)    // sheets / secondary surfaces
        val SurfaceHighest = Color(0xFF1A2232) // menus, pressed states
        val Border = Color(0xFF1E293B)         // 1 px outlines (P4 contract)
        val BorderStrong = Color(0xFF2A3550)   // focused/active outlines

        /** Text. */
        val TextPrimary = Color(0xFFFFFFFF)    // crisp white
        val TextSecondary = Color(0xFF94A3B8)  // muted slate (P4 contract)
        val TextMuted = Color(0xFF5F6B80)      // faint metadata

        /** Electric Ice-Cyan — focus + primary actions. */
        val Accent = Color(0xFF22D3EE)
        val OnAccent = Color(0xFF00252B)
        val AccentContainer = Color(0xFF07303A)
        val OnAccentContainer = Color(0xFFA5F3FC)

        /** Steel Blue — calm secondary / completed. */
        val Steel = Color(0xFF6FA3D8)
        val SteelDim = Color(0xFF4E6E96)
        val SteelContainer = Color(0xFF12263D)
        val OnSteelContainer = Color(0xFFC9E2F7)

        /** Semantic red — critical failures ONLY. */
        val Danger = Color(0xFFF87171)
        val OnDanger = Color(0xFF2B0B0B)
        val DangerContainer = Color(0xFF3F1D1D)
        val OnDangerContainer = Color(0xFFFECACA)
    }

    /** Spacing grid (4-based; the Ph 9.5 scale, unchanged). */
    object Space {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
    }

    /** Corner radii. */
    object Radius {
        val s = 8.dp
        val m = 12.dp   // fields (the established FieldShape)
        val l = 16.dp   // cards
    }
}
