package dev.trmx.gui.files

/*
 * Pure view-transforms for the Files browser (JVM-testable). The bridge's
 * §6.2 listing is intentionally complete (iterdir, nothing filtered) —
 * hiding dotfiles by default is a client-side presentation choice,
 * reversible with the in-screen toggle (UX-audit P0, ADR-012).
 */

import dev.trmx.gui.model.FileEntry

object FilesFilter {

    /** Entries the user sees: dotfiles only when the toggle is on. */
    fun visible(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> =
        if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }
}
