package com.froginalog.mp3mp4editor.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * Resolves a YouTube watch URL into the concrete media URLs behind it, using NewPipeExtractor.
 *
 * Nothing is cached: YouTube's stream URLs are short-lived and signed, so they are resolved right
 * before a download starts.
 */
class YoutubeRepository(client: OkHttpClient) {

    init {
        NewPipe.init(NewPipeDownloader(client))
    }

    class ResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun resolve(rawUrl: String): VideoMeta = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
            ?: throw ResolveException("That doesn't look like a YouTube link.")

        val info = try {
            StreamInfo.getInfo(url)
        } catch (e: IOException) {
            throw ResolveException("Couldn't reach YouTube. Check your connection.", e)
        } catch (e: Exception) {
            throw ResolveException(
                "Couldn't read that video. It may be private, age-restricted, or region-locked.",
                e,
            )
        }

        val audioStreams = info.audioStreams.orEmpty().filter { it.isUsable() }
        val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }?.toChoice()
        val bestM4a = audioStreams
            .filter { it.suffixOrEmpty().equals("m4a", ignoreCase = true) }
            .maxByOrNull { it.averageBitrate }
            ?.toChoice()

        val progressive = info.videoStreams.orEmpty()
            .filter { it.isUsable() && it.suffixOrEmpty().equals("mp4", ignoreCase = true) }
            .map { it.toChoice(needsMerge = false, mergeAudio = null) }

        val adaptive = if (bestM4a != null) {
            info.videoOnlyStreams.orEmpty()
                .filter { it.isUsable() && it.suffixOrEmpty().equals("mp4", ignoreCase = true) }
                .map { it.toChoice(needsMerge = true, mergeAudio = bestM4a) }
        } else {
            emptyList()
        }

        // Prefer the adaptive entry when both offer the same height — it is the higher bitrate one.
        val choices = (adaptive + progressive)
            .sortedWith(compareByDescending<VideoChoice> { it.height }.thenBy { !it.needsAudioMerge })
            .distinctBy { it.height }

        VideoMeta(
            id = info.id.orEmpty(),
            title = info.name.orEmpty().ifBlank { "YouTube video" },
            uploader = info.uploaderName.orEmpty(),
            durationMs = info.duration.coerceAtLeast(0L) * 1000L,
            thumbnailUrl = thumbnailFor(info.id.orEmpty()),
            videoChoices = choices,
            bestAudio = bestAudio,
            bestM4aAudio = bestM4a,
        )
    }

    // -- mapping helpers -------------------------------------------------------------------

    private fun VideoStream.toChoice(needsMerge: Boolean, mergeAudio: AudioChoice?): VideoChoice {
        val height = heightFromResolution(resolution)
        val videoBytes = contentLengthOrZero()
        val audioBytes = if (needsMerge) mergeAudio?.source?.approxBytes ?: 0L else 0L
        return VideoChoice(
            label = resolution.orEmpty().ifBlank { "${height}p" },
            height = height,
            video = StreamSource(
                url = content,
                suffix = suffixOrEmpty(),
                mimeType = "video/mp4",
                approxBytes = videoBytes,
            ),
            needsAudioMerge = needsMerge,
            approxBytes = if (videoBytes > 0) videoBytes + audioBytes else 0L,
        )
    }

    private fun AudioStream.toChoice(): AudioChoice {
        val kbps = if (averageBitrate > 0) averageBitrate else 0
        val suffix = suffixOrEmpty()
        return AudioChoice(
            label = buildString {
                append(suffix.uppercase().ifEmpty { "AUDIO" })
                if (kbps > 0) append(" · ${kbps} kbps")
            },
            bitrateKbps = kbps,
            source = StreamSource(
                url = content,
                suffix = suffix.ifEmpty { "bin" },
                mimeType = if (suffix.equals("m4a", true)) "audio/mp4" else "audio/webm",
                approxBytes = contentLengthOrZero(),
            ),
        )
    }

    /**
     * NewPipe exposes some streams as manifests (DASH/HLS) rather than a plain URL; those cannot be
     * fetched with a single GET, so they are filtered out.
     */
    private fun org.schabi.newpipe.extractor.stream.Stream.isUsable(): Boolean =
        content.orEmpty().startsWith("http")

    private fun org.schabi.newpipe.extractor.stream.Stream.suffixOrEmpty(): String =
        runCatching { format?.suffix.orEmpty() }.getOrDefault("")

    private fun org.schabi.newpipe.extractor.stream.Stream.contentLengthOrZero(): Long =
        runCatching { itagItem?.contentLength ?: 0L }.getOrDefault(0L).coerceAtLeast(0L)

    private fun heightFromResolution(resolution: String?): Int {
        val digits = resolution.orEmpty().takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private fun thumbnailFor(id: String): String =
        if (id.isBlank()) "" else "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

        /**
         * Accepts anything a share sheet might hand over — `youtu.be/…`, `m.youtube.com/watch?v=…`,
         * Shorts, a bare ID, or a link buried in a sentence of share text.
         */
        fun normalizeUrl(input: String): String? {
            val text = input.trim()
            if (text.isEmpty()) return null

            // A bare video ID.
            if (text.length == 11 && ID_PATTERN.matches(text)) {
                return "https://www.youtube.com/watch?v=$text"
            }

            val candidate = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')')
                ?: return null
            val id = extractId(candidate) ?: return null
            return "https://www.youtube.com/watch?v=$id"
        }

        private fun extractId(url: String): String? {
            val patterns = listOf(
                Regex("[?&]v=([A-Za-z0-9_-]{11})"),
                Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
                Regex("/shorts/([A-Za-z0-9_-]{11})"),
                Regex("/embed/([A-Za-z0-9_-]{11})"),
                Regex("/live/([A-Za-z0-9_-]{11})"),
            )
            for (pattern in patterns) {
                pattern.find(url)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }
    }
}
