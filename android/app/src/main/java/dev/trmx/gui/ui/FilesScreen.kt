package dev.trmx.gui.ui

/*
 * File browser (Phase 7 + Phase 8): navigate the Termux home per
 * PROTOCOL §6 — list, mkdir, rename, delete (with the recursive+confirm
 * interlock), download into app storage, upload from the document picker,
 * and open/share via the FileProvider (download-then-open, ADR-008).
 * The bridge enforces the path policy; this screen is just a view of it.
 */

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.FileBrowserState
import dev.trmx.gui.files.FilesFilter
import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.tools.JobLabels

private val TYPE_GLYPH = mapOf(
    "dir" to "📁", "file" to "📄", "symlink" to "🔗", "other" to "•")

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FileBrowserState,
    onOpenPath: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onMakeDir: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (android.net.Uri, String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onShareFile: (FileEntry) -> Unit,
    /** Non-null = picker mode: tap a file (true) or use-the-folder (false). */
    pickFile: Boolean? = null,
    onPicked: (String) -> Unit = {},
    onCancelPick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var newDirDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var actionsTarget by remember { mutableStateOf<FileEntry?>(null) }
    var detailsTarget by remember { mutableStateOf<FileEntry?>(null) }

    // Dotfiles default-hidden (UX-audit P0): the §6.2 listing is complete by
    // design; hiding is a presentation choice, persisted across recompositions.
    // P2: folders first, human metadata — a file manager, not `ls -la`.
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val display = FilesFilter.sortForDisplay(FilesFilter.visible(state.entries, showHidden))

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "uploaded-file"
            onUpload(uri, name)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pickFile != null) {
                OutlinedButton(onClick = onCancelPick) { Text("← cancel") }
                Spacer(Modifier.width(Sp.s))
            }
            Text(
                when (pickFile) {
                    true -> "Pick a file"
                    false -> "Pick a folder"
                    else -> "Files"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.opPending) CircularProgressIndicator(strokeWidth = 3.dp)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.path, fontFamily = FontFamily.Monospace,
                     style = MaterialTheme.typography.bodyLarge)
                if (pickFile == null) {
                    ActionFlowRow {
                        OutlinedButton(onClick = onUp, enabled = state.path != "~") { Text("↑ up") }
                        OutlinedButton(onClick = onRefresh) { Text("refresh") }
                        Button(onClick = { newDirDialog = true }) { Text("+ folder") }
                        Button(onClick = {
                            picker.launch(arrayOf("*/*"))
                        }) { Text("↑ upload") }
                        OutlinedButton(onClick = { showHidden = !showHidden }) {
                            Text(if (showHidden) "● dotfiles" else "◌ dotfiles")
                        }
                    }
                } else if (pickFile == false) {
                    Button(onClick = { onPicked(state.path) }) { Text("use this folder ✓") }
                }
                Text(
                    when (pickFile) {
                        true -> "navigate to the file, then tap it"
                        false -> "navigate into the folder, then “use this folder”"
                        else -> "tap a folder to open it · long-press a row for actions"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                state.notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
        }

        state.transfer?.let { t ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${t.label} ${t.name}…", style = MaterialTheme.typography.bodyMedium)
                    val total = t.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (t.bytes.toDouble() / total).toFloat() },
                            modifier = Modifier.fillMaxWidth())
                        Text("${Formatter.formatShortFileSize(context, t.bytes)} / " +
                                 Formatter.formatShortFileSize(context, total),
                             fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.secondary)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (t.bytes > 0) {
                            Text(Formatter.formatShortFileSize(context, t.bytes),
                                 fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                 color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }

        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically,
                                 horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(strokeWidth = 3.dp)
                Text("loading…")
            }
            state.entries.isEmpty() && state.error != null -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRefresh) { Text("retry") }
                }
            }
            state.entries.isEmpty() -> Box(modifier = Modifier.fillMaxSize(),
                                           contentAlignment = Alignment.Center) {
                Text("This folder is empty.", color = MaterialTheme.colorScheme.secondary)
            }
            display.isEmpty() -> Box(modifier = Modifier.fillMaxSize(),
                                     contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                       verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text(
                        "${state.entries.size} hidden ${if (state.entries.size == 1) "entry" else "entries"}",
                        color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { showHidden = true }) { Text("show dotfiles") }
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(display, key = { it.name }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    when {
                                        entry.type == "dir" ->
                                            onOpenPath(state.path.trimEnd('/') + "/" + entry.name)
                                        pickFile == true ->
                                            onPicked(state.path.trimEnd('/') + "/" + entry.name)
                                        pickFile == null ->
                                            detailsTarget = entry   // P2: tap = details
                                    }
                                },
                                onLongClick = { if (pickFile == null) actionsTarget = entry },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        Text(TYPE_GLYPH[entry.type] ?: "•",
                             modifier = Modifier.width(34.dp), fontSize = 18.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            // Human metadata only (P2): "Aug 28 · 3.5 KB".
                            // Raw ISO stamps, permissions and symlink targets
                            // live in the details sheet now.
                            Text(
                                "${JobLabels.relativeTime(entry.mtime) ?: "—"}  ·  " +
                                    Formatter.formatShortFileSize(context, entry.size),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Text("⋮", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }

    if (newDirDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDirDialog = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("folder name") }, singleLine = true,
                                  colors = TrmxFieldColors())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onMakeDir(name.trim())
                    newDirDialog = false
                }) { Text("create") }
            },
            dismissButton = { TextButton(onClick = { newDirDialog = false }) { Text("cancel") } },
        )
    }

    actionsTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionsTarget = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { detailsTarget = entry; actionsTarget = null }) {
                        Text("Details")
                    }
                    if (entry.type != "dir") {
                        TextButton(onClick = { onOpenFile(entry); actionsTarget = null }) {
                            Text("Open (via viewer app)")
                        }
                        TextButton(onClick = { onShareFile(entry); actionsTarget = null }) {
                            Text("Share…")
                        }
                        TextButton(onClick = { onDownload(entry); actionsTarget = null }) {
                            Text("Save to app documents")
                        }
                    }
                    TextButton(onClick = { renameTarget = entry; actionsTarget = null }) {
                        Text("Rename…")
                    }
                    TextButton(onClick = { deleteTarget = entry; actionsTarget = null }) {
                        Text("Delete…", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionsTarget = null }) { Text("close") }
            },
        )
    }

    renameTarget?.let { entry ->
        var newName by remember(entry) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename “${entry.name}”") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                                      label = { Text("new name") }, singleLine = true,
                                      colors = TrmxFieldColors())
                    Text("…or remove it entirely:", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        deleteTarget = entry
                        renameTarget = null
                    }) { Text("Delete “${entry.name}”") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newName != entry.name) {
                        onRename(entry, newName.trim())
                    }
                    renameTarget = null
                }) { Text("rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("cancel") }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${entry.name}”?") },
            text = {
                Text(
                    if (entry.type == "dir")
                        "This deletes the folder AND everything inside it (recursive). " +
                            "There is no trash bin — this cannot be undone."
                    else
                        "This deletes the file. There is no trash bin — this cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry)
                    deleteTarget = null
                }) { Text("delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("keep it") }
            },
        )
    }

    detailsTarget?.let { entry ->
        // File Details (UX-audit P2): the raw Linux facts — full path,
        // permissions, exact timestamps, symlink target — live here, not
        // in the list rows.
        val fullPath = state.path.trimEnd('/') + "/" + entry.name
        ModalBottomSheet(onDismissRequest = { detailsTarget = null }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = Sp.m)
                    .padding(bottom = Sp.l),
                verticalArrangement = Arrangement.spacedBy(Sp.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TYPE_GLYPH[entry.type] ?: "•", fontSize = 20.sp)
                    Spacer(Modifier.width(Sp.s))
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                DetailKV("path", fullPath)
                DetailKV("type", entry.type)
                DetailKV(
                    "size",
                    "${Formatter.formatShortFileSize(context, entry.size)}  ·  ${entry.size} bytes")
                DetailKV(
                    "modified",
                    "${JobLabels.relativeTime(entry.mtime) ?: "—"}  ·  ${entry.mtime ?: "—"}")
                entry.mode?.let { DetailKV("permissions", it) }
                entry.target?.let { DetailKV("symlink to", it) }

                if (entry.type != "dir") {
                    ActionFlowRow {
                        Button(onClick = { onOpenFile(entry); detailsTarget = null }) {
                            Text("open")
                        }
                        OutlinedButton(onClick = { onShareFile(entry); detailsTarget = null }) {
                            Text("share")
                        }
                        OutlinedButton(onClick = { onDownload(entry); detailsTarget = null }) {
                            Text("save to app")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailKV(k: String, v: String) {
    Row {
        Text(k, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
             color = MaterialTheme.colorScheme.secondary,
             modifier = Modifier.width(90.dp))
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
             style = MaterialTheme.typography.bodySmall)
    }
}

private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    null
}
