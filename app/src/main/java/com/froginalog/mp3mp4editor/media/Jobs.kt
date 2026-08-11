package com.froginalog.mp3mp4editor.media

import android.net.Uri
import com.froginalog.mp3mp4editor.youtube.AudioChoice
import com.froginalog.mp3mp4editor.youtube.VideoChoice
import com.froginalog.mp3mp4editor.youtube.VideoMeta

/** What the user asked for, in the format the exporter understands. */
sealed interface OutputFormat {
    val extension: String
    val mimeType: String

    data object Mp4 : OutputFormat {
        override val extension = "mp4"
        override val mimeType = "video/mp4"
    }

    /** Audio lifted out of the container untouched. Only valid when the audio is AAC. */
    data object M4a : OutputFormat {
        override val extension = "m4a"
        override val mimeType = "audio/mp4"
    }

    data class Mp3(val bitrateKbps: Int = 192) : OutputFormat {
        override val extension = "mp3"
        override val mimeType = "audio/mpeg"
    }
}

sealed interface JobSpec {
    val title: String

    data class YoutubeVideo(
        val meta: VideoMeta,
        val choice: VideoChoice,
        val mergeAudio: AudioChoice?,
    ) : JobSpec {
        override val title: String get() = meta.title
    }

    data class YoutubeAudio(
        val meta: VideoMeta,
        val audio: AudioChoice,
        val format: OutputFormat,
    ) : JobSpec {
        override val title: String get() = meta.title
    }

    data class LocalExport(
        val source: Uri,
        val sourceName: String,
        val startMs: Long,
        val endMs: Long,
        val format: OutputFormat,
    ) : JobSpec {
        override val title: String get() = sourceName
    }
}

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class MediaJob(
    val id: Long,
    val title: String,
    val detail: String,
    val state: JobState = JobState.QUEUED,
    /** 0f..1f, or null while the total size is unknown. */
    val progress: Float? = 0f,
    val status: String = "Queued",
    val outputUri: Uri? = null,
    val outputMime: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
}
