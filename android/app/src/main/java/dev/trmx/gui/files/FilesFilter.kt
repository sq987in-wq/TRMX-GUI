package dev.trmx.gui.files

/*
 * Pure view-transforms for the Files browser (JVM-testable). The bridge's
 * §6.2 listing is intentionally complete (iterdir, nothing filtered) —
 * hiding dotfiles by default and ordering for display are client-side
 * presentation choices (UX-audit P0/P2, ADR-012).
 */

import dev.trmx.gui.model.FileEntry

object FilesFilter {

    /** Entries the user sees: dotfiles only when the toggle is on. */
    fun visible(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> =
        if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }

    /**
     * Display order (UX-audit P2): folders first, then everything else,
     * each alphabetical — a file manager, not an `ls -la` dump.
     */
    fun sortForDisplay(entries: List<FileEntry>): List<FileEntry> =
        entries.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
}
