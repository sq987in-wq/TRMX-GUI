package dev.trmx.gui.ui

/*
 * File browser (Phase 7): navigate the Termux home per PROTOCOL §6 —
 * list, mkdir, rename, delete (with the recursive+confirm interlock),
 * download into app storage, upload from the document picker.
 * The bridge enforces the path policy; this screen is just a view of it.
 */

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.FileBrowserState
import dev.trmx.gui.model.FileEntry

private val TYPE_GLYPH = mapOf(
    "dir" to "📁", "file" to "📄", "symlink" to "🔗", "other" to "•")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    state: FileBrowserState,
    onBack: () -> Unit,
    onOpenPath: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onMakeDir: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onUpload: (android.net.Uri, String) -> Unit,
) {
    var newDirDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "uploaded-file"
            onUpload(uri, name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Dashboard") }
            Spacer(Modifier.width(10.dp))
            Text("Files", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.opPending) CircularProgressIndicator(strokeWidth = 3.dp)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.path, fontFamily = FontFamily.Monospace,
                     style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onUp, enabled = state.path != "~") { Text("↑ up") }
                    OutlinedButton(onClick = onRefresh) { Text("refresh") }
                    Button(onClick = { newDirDialog = true }) { Text("+ folder") }
                    Button(onClick = {
                        picker.launch(arrayOf("*/*"))
                    }) { Text("↑ upload") }
                }
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

        if (state.loading) {
            Text("loading…")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.name }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (entry.type == "dir") onOpenPath(
                                    state.path.trimEnd('/') + "/" + entry.name) },
                                onLongClick = { renameTarget = entry },
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        Text(TYPE_GLYPH[entry.type] ?: "•",
                             modifier = Modifier.width(34.dp), fontSize = 18.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                buildString {
                                    append(Formatter.formatShortFileSize(context, entry.size))
                                    entry.mode?.let { append("  ").append(it) }
                                    entry.mtime?.let { append("  ").append(it) }
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            if (entry.type == "symlink") {
                                Text("→ ${entry.target ?: "?"}",
                                     fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                     color = Color(0xFF80CBC4))
                            }
                        }
                        if (entry.type == "file") {
                            TextButton(onClick = { onDownload(entry) }) { Text("save") }
                        }
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
                                  label = { Text("folder name") }, singleLine = true)
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

    renameTarget?.let { entry ->
        var newName by remember(entry) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename “${entry.name}”") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                                      label = { Text("new name") }, singleLine = true)
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
