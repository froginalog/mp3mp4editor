package com.froginalog.mp3mp4editor.ui.edit

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.froginalog.mp3mp4editor.EditorApp
import com.froginalog.mp3mp4editor.media.JobSpec
import com.froginalog.mp3mp4editor.media.Mp4Tools
import com.froginalog.mp3mp4editor.media.OutputFormat
import com.froginalog.mp3mp4editor.util.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Carries a file from another screen (or another app) into the trimmer. */
object PendingEdit {
    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri.asStateFlow()

    fun request(value: Uri) {
        _uri.value = value
    }

    fun consume() {
        _uri.value = null
    }
}

data class EditUiState(
    val source: Uri? = null,
    val name: String = "",
    val durationMs: Long = 0L,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    /** The audio track is AAC, so it can be lifted into an M4A without re-encoding. */
    val canCopyAudio: Boolean = false,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
    val mp3Bitrate: Int = 192,
) {
    val hasClip: Boolean get() = source != null && endMs > startMs
    val clipMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

class EditViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EditorApp).container

    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    val jobs = container.jobManager.jobs

    fun load(uri: Uri) {
        if (_state.value.source == uri && _state.value.durationMs > 0) return
        _state.update { EditUiState(source = uri, loading = true, mp3Bitrate = it.mp3Bitrate) }

        viewModelScope.launch {
            val context = getApplication<Application>()
            // Persist access so the file survives a process restart when it came from the picker.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val probe = withContext(Dispatchers.IO) { Files.probe(context, uri) }
            val name = withContext(Dispatchers.IO) { Files.displayName(context, uri) } ?: "clip"
            val duration = probe.durationMs

            if (duration <= 0L) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Couldn't read that file — it may be an unsupported format.",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    loading = false,
                    name = name,
                    durationMs = duration,
                    hasVideo = probe.hasVideo,
                    canCopyAudio = probe.audioMime?.let(Mp4Tools::isMuxableAudio) ?: false,
                    hasAudio = probe.audioMime != null,
                    startMs = 0L,
                    endMs = duration,
                    error = null,
                )
            }
        }
    }

    fun setRange(startMs: Long, endMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val start = startMs.coerceIn(0L, duration)
        val end = endMs.coerceIn(0L, duration)
        if (end <= start) return
        _state.update { it.copy(startMs = start, endMs = end) }
    }

    fun setStart(ms: Long) = setRange(ms, _state.value.endMs)

    fun setEnd(ms: Long) = setRange(_state.value.startMs, ms)

    fun setBitrate(value: Int) {
        _state.update { it.copy(mp3Bitrate = value) }
    }

    fun reset() {
        _state.update { EditUiState(mp3Bitrate = it.mp3Bitrate) }
    }

    fun export(format: OutputFormat) {
        val current = _state.value
        val source = current.source ?: return
        if (!current.hasClip) return
        container.jobManager.enqueue(
            JobSpec.LocalExport(
                source = source,
                sourceName = current.name,
                startMs = current.startMs,
                endMs = current.endMs,
                format = format,
            )
        )
    }

    fun cancelJob(id: Long) = container.jobManager.cancel(id)

    fun dismissJob(id: Long) = container.jobManager.dismiss(id)
}
