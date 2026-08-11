package com.froginalog.mp3mp4editor.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.froginalog.mp3mp4editor.EditorApp
import com.froginalog.mp3mp4editor.media.JobSpec
import com.froginalog.mp3mp4editor.media.OutputFormat
import com.froginalog.mp3mp4editor.youtube.VideoChoice
import com.froginalog.mp3mp4editor.youtube.VideoMeta
import com.froginalog.mp3mp4editor.youtube.YoutubeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val url: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val meta: VideoMeta? = null,
    val mp3Bitrate: Int = 192,
)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EditorApp).container

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    val jobs = container.jobManager.jobs

    fun setUrl(value: String) {
        _state.update { it.copy(url = value, error = null) }
    }

    fun setBitrate(value: Int) {
        _state.update { it.copy(mp3Bitrate = value) }
    }

    fun clear() {
        _state.update { DownloadUiState(mp3Bitrate = it.mp3Bitrate) }
    }

    fun fetch() {
        val url = _state.value.url.trim()
        if (url.isEmpty() || _state.value.loading) return

        _state.update { it.copy(loading = true, error = null, meta = null) }
        viewModelScope.launch {
            try {
                val meta = container.youtube.resolve(url)
                _state.update { it.copy(loading = false, meta = meta) }
            } catch (e: YoutubeRepository.ResolveException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = t.message ?: "Something went wrong.")
                }
            }
        }
    }

    fun downloadVideo(choice: VideoChoice) {
        val meta = _state.value.meta ?: return
        container.jobManager.enqueue(
            JobSpec.YoutubeVideo(
                meta = meta,
                choice = choice,
                mergeAudio = if (choice.needsAudioMerge) meta.bestM4aAudio else null,
            )
        )
    }

    fun downloadMp3() {
        val meta = _state.value.meta ?: return
        val audio = meta.bestAudio ?: return
        container.jobManager.enqueue(
            JobSpec.YoutubeAudio(meta, audio, OutputFormat.Mp3(_state.value.mp3Bitrate))
        )
    }

    fun downloadM4a() {
        val meta = _state.value.meta ?: return
        // Saving the stream untouched is only honest when it really is an M4A.
        val audio = meta.bestM4aAudio ?: return
        container.jobManager.enqueue(JobSpec.YoutubeAudio(meta, audio, OutputFormat.M4a))
    }

    fun cancelJob(id: Long) = container.jobManager.cancel(id)

    fun dismissJob(id: Long) = container.jobManager.dismiss(id)
}
