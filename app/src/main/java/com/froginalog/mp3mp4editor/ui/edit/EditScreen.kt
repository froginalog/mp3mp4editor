package com.froginalog.mp3mp4editor.ui.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.froginalog.mp3mp4editor.media.OutputFormat
import com.froginalog.mp3mp4editor.ui.common.JobCard
import com.froginalog.mp3mp4editor.ui.common.openMedia
import com.froginalog.mp3mp4editor.util.TimeFmt
import kotlinx.coroutines.delay

@Composable
fun EditScreen(viewModel: EditViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val pending by PendingEdit.uri.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(pending) {
        pending?.let {
            viewModel.load(it)
            PendingEdit.consume()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::load) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = false }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var loopClip by remember { mutableStateOf(false) }

    LaunchedEffect(state.source) {
        val source = state.source
        if (source == null) {
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(source))
            player.prepare()
        }
        positionMs = 0L
    }

    // Drives the playhead readout and keeps preview playback inside the selection. Keyed only on
    // the source: the range is read live, so dragging a handle must not restart the loop.
    LaunchedEffect(state.source) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            if (loopClip && player.isPlaying && positionMs >= state.endMs) {
                player.pause()
                player.seekTo(state.startMs)
                loopClip = false
            }
            delay(60)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Clip & trim", style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = {
                    picker.launch(arrayOf("video/*", "audio/*"))
                }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Open file")
                }
            }
        }

        if (state.loading) {
            item { CircularProgressIndicator() }
        }

        state.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }

        if (state.source == null && !state.loading) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Nothing loaded", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Open an MP4, MP3 or M4A — from this phone or from the Library tab — " +
                                "then drag the two handles to pick the part you want to keep.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (state.source != null && state.durationMs > 0) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.hasVideo) {
                        AndroidView(
                            factory = { ctx ->
                                val view = PlayerView(ctx)
                                view.useController = false
                                view.player = player
                                view
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
            }

            item {
                Text(
                    state.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                        )
                    }
                    OutlinedButton(onClick = {
                        player.seekTo(state.startMs)
                        loopClip = true
                        player.play()
                    }) { Text("Preview clip") }
                    Text(
                        "${TimeFmt.clock(positionMs)} / ${TimeFmt.clock(state.durationMs, false)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                RangeSlider(
                    value = state.startMs.toFloat()..state.endMs.toFloat(),
                    onValueChange = { range ->
                        viewModel.setRange(range.start.toLong(), range.endInclusive.toLong())
                    },
                    valueRange = 0f..state.durationMs.toFloat(),
                    onValueChangeFinished = { player.seekTo(state.startMs) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                TimeRow(
                    label = "Start",
                    valueMs = state.startMs,
                    onValueMs = viewModel::setStart,
                    onUsePlayhead = { viewModel.setStart(positionMs) },
                )
            }

            item {
                TimeRow(
                    label = "End",
                    valueMs = state.endMs,
                    onValueMs = viewModel::setEnd,
                    onUsePlayhead = { viewModel.setEnd(positionMs) },
                )
            }

            item {
                Text(
                    "Clip length ${TimeFmt.clock(state.clipMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Text("Export as", style = MaterialTheme.typography.titleMedium)
            }

            if (state.hasAudio) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("MP3", style = MaterialTheme.typography.bodyMedium)
                        listOf(128, 192, 320).forEach { rate ->
                            FilterChip(
                                selected = state.mp3Bitrate == rate,
                                onClick = { viewModel.setBitrate(rate) },
                                label = { Text("$rate") },
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.hasVideo) {
                        Button(
                            onClick = { viewModel.export(OutputFormat.Mp4) },
                            enabled = state.hasClip,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Trim to MP4 (no re-encode)") }
                    }
                    if (state.hasAudio) {
                        Button(
                            onClick = { viewModel.export(OutputFormat.Mp3(state.mp3Bitrate)) },
                            enabled = state.hasClip,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export MP3 (${state.mp3Bitrate} kbps)") }
                    }
                    if (state.canCopyAudio) {
                        FilledTonalButton(
                            onClick = { viewModel.export(OutputFormat.M4a) },
                            enabled = state.hasClip,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export M4A (no re-encode)") }
                    }
                    TextButton(onClick = viewModel::reset) { Text("Close file") }
                }
            }

            if (state.hasVideo) {
                item {
                    Text(
                        "MP4 and M4A exports copy the original data, so the start snaps to the " +
                            "nearest keyframe. MP3 export is sample-accurate.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item { Text("Queue", style = MaterialTheme.typography.titleMedium) }
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
private fun TimeRow(
    label: String,
    valueMs: Long,
    onValueMs: (Long) -> Unit,
    onUsePlayhead: () -> Unit,
) {
    var text by remember { mutableStateOf(TimeFmt.clock(valueMs)) }
    // Follow the slider, but leave a half-typed value alone: if the field already parses to the
    // current position there is nothing to correct.
    LaunchedEffect(valueMs) {
        if (TimeFmt.parse(text) != valueMs) text = TimeFmt.clock(valueMs)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                TimeFmt.parse(updated)?.let(onValueMs)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onUsePlayhead) { Text("Playhead") }
    }
}
