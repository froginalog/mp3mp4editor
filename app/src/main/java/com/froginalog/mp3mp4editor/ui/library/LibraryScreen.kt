package com.froginalog.mp3mp4editor.ui.library

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.froginalog.mp3mp4editor.media.OutputEntry
import com.froginalog.mp3mp4editor.ui.common.openMedia
import com.froginalog.mp3mp4editor.ui.common.shareMedia
import com.froginalog.mp3mp4editor.util.TimeFmt

@Composable
fun LibraryScreen(
    onTrim: (Uri) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<OutputEntry?>(null) }

    // Files finish in the background, so re-read the folder every time the tab is shown.
    LaunchedEffect(Unit) { viewModel.refresh() }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = { Text("${entry.name} will be removed from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry.uri)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
        }

        if (state.loading) {
            item { CircularProgressIndicator() }
        }

        if (!state.loading && state.entries.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Anything you download or export lands in " +
                        "Movies/MP3MP4Editor and Music/MP3MP4Editor.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.entries, key = { it.uri.toString() }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (entry.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                            contentDescription = null,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    TimeFmt.bytes(entry.sizeBytes),
                                    entry.durationMs.takeIf { it > 0 }
                                        ?.let { TimeFmt.clock(it, withMillis = false) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { openMedia(context, entry.uri, entry.mimeType) }) {
                            Text("Play")
                        }
                        TextButton(onClick = { onTrim(entry.uri) }) { Text("Trim") }
                        TextButton(onClick = { shareMedia(context, entry.uri, entry.mimeType) }) {
                            Text("Share")
                        }
                        TextButton(onClick = { pendingDelete = entry }) { Text("Delete") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
