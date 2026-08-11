package com.froginalog.mp3mp4editor.youtube

/** One downloadable stream URL plus what we know about it. */
data class StreamSource(
    val url: String,
    val suffix: String,
    val mimeType: String,
    val approxBytes: Long,
)

/** A video quality the user can pick. */
data class VideoChoice(
    val label: String,
    val height: Int,
    val video: StreamSource,
    /** Adaptive streams carry no audio, so an audio track has to be muxed back in. */
    val needsAudioMerge: Boolean,
    val approxBytes: Long,
)

data class AudioChoice(
    val label: String,
    val bitrateKbps: Int,
    val source: StreamSource,
)

data class VideoMeta(
    val id: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val videoChoices: List<VideoChoice>,
    /** Highest-quality audio in any codec — the input for MP3 conversion. */
    val bestAudio: AudioChoice?,
    /** Highest-quality AAC/M4A audio — the only kind that can be muxed into an MP4. */
    val bestM4aAudio: AudioChoice?,
) {
    val canDownloadVideo: Boolean get() = videoChoices.isNotEmpty()
}
