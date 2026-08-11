package com.froginalog.mp3mp4editor.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.froginalog.mp3mp4editor.ui.common.JobCard
import com.froginalog.mp3mp4editor.ui.common.openMedia
import com.froginalog.mp3mp4editor.util.TimeFmt

@Composable
fun DownloadScreen(
    sharedLink: String?,
    onSharedLinkConsumed: () -> Unit,
    viewModel: DownloadViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // A link shared from YouTube fills the field and resolves itself.
    LaunchedEffect(sharedLink) {
        if (sharedLink != null) {
            viewModel.setUrl(sharedLink)
            onSharedLinkConsumed()
            viewModel.fetch()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("YouTube to MP4 / MP3", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                label = { Text("YouTube link") },
                placeholder = { Text("https://youtu.be/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { viewModel.setUrl(it) }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::fetch,
                    enabled = state.url.isNotBlank() && !state.loading,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.loading) "Looking up…" else "Find formats")
                }
                if (state.meta != null) {
                    OutlinedButton(onClick = viewModel::clear) { Text("Clear") }
                }
            }
        }

        state.error?.let { message ->
            item {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.meta?.let { meta ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (meta.thumbnailUrl.isNotEmpty()) {
                            AsyncImage(
                                model = meta.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            meta.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                meta.uploader.takeIf { it.isNotBlank() },
                                TimeFmt.clock(meta.durationMs, withMillis = false),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionLabel("Audio") }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(128, 192, 320).forEach { rate ->
                        FilterChip(
                            selected = state.mp3Bitrate == rate,
                            onClick = { viewModel.setBitrate(rate) },
                            label = { Text("$rate kbps") },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::downloadMp3,
                        enabled = meta.bestAudio != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Download MP3") }

                    if (meta.bestM4aAudio != null) {
                        FilledTonalButton(
                            onClick = viewModel::downloadM4a,
                            modifier = Modifier.weight(1f),
                        ) { Text("M4A (original)") }
                    }
                }
            }

            item { SectionLabel("Video") }

            if (!meta.canDownloadVideo) {
                item {
                    Text(
                        "No downloadable MP4 stream was offered for this video.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(meta.videoChoices, key = { it.label + it.height }) { choice ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                buildString {
                                    append("MP4")
                                    if (choice.approxBytes > 0) {
                                        append(" · ~${TimeFmt.bytes(choice.approxBytes)}")
                                    }
                                    if (choice.needsAudioMerge) append(" · audio merged in")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { viewModel.downloadVideo(choice) }) { Text("Download") }
                    }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item { SectionLabel("Queue") }
            items(jobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    onCancel = { viewModel.cancelJob(job.id) },
                    onDismiss = { viewModel.dismissJob(job.id) },
                    onOpen = { uri, mime -> openMedia(context, uri, mime) },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Box(Modifier.padding(top = 6.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
