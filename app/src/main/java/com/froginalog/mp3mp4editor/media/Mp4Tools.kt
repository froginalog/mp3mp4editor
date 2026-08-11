package com.froginalog.mp3mp4editor.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Container-level operations built on [MediaExtractor] / [MediaMuxer].
 *
 * Everything here copies encoded samples verbatim — no decode, no re-encode — so it is fast and
 * lossless. The trade-off is that a trim can only begin on a sync (key) frame; [TrimResult]
 * reports where the cut actually landed.
 */
object Mp4Tools {

    class MediaOpException(message: String) : Exception(message)

    data class TrimResult(val actualStartUs: Long, val actualEndUs: Long)

    private const val DEFAULT_BUFFER = 1 shl 20

    /**
     * Combines the video track of [videoFile] with the audio track of [audioFile] into one MP4.
     * Used for adaptive YouTube streams, which ship video and audio separately.
     */
    fun mux(videoFile: File, audioFile: File, output: File, onProgress: (Float) -> Unit = {}) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw MediaOpException("The downloaded video part has no video track.")
            val audioTrack = findTrack(audioExtractor, "audio/")
                ?: throw MediaOpException("The downloaded audio part has no audio track.")

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)
            requireMuxableAudio(audioFormat)

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            rotationOf(videoFormat)?.let { muxer.setOrientationHint(it) }
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(audioFormat)
            muxer.start()

            val durationUs = max(durationOf(videoFormat), durationOf(audioFormat)).coerceAtLeast(1L)
            val buffer = ByteBuffer.allocate(
                max(maxInputSize(videoFormat), maxInputSize(audioFormat))
            )

            copyAll(videoExtractor, muxer, outVideo, buffer) { us ->
                onProgress((us.toFloat() / durationUs).coerceIn(0f, 1f) * 0.9f)
            }
            copyAll(audioExtractor, muxer, outAudio, buffer) { us ->
                onProgress(0.9f + (us.toFloat() / durationUs).coerceIn(0f, 1f) * 0.1f)
            }
            onProgress(1f)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    /**
     * Copies the samples of [source] between [startUs] and [endUs] into [output].
     *
     * When [audioOnly] is set the video track is dropped, which is how "extract the audio without
     * re-encoding" is implemented.
     */
    fun trim(
        context: Context,
        source: Uri,
        output: File,
        startUs: Long,
        endUs: Long,
        audioOnly: Boolean,
        onProgress: (Float) -> Unit = {},
    ): TrimResult {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)

            // Map input track index -> output track index for everything we intend to keep.
            val trackMap = HashMap<Int, Int>()
            val formats = HashMap<Int, MediaFormat>()
            var rotation: Int? = null
            var maxInput = 0

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val keep = when {
                    mime.startsWith("audio/") -> true
                    mime.startsWith("video/") -> !audioOnly
                    else -> false
                }
                if (!keep) continue
                if (mime.startsWith("audio/")) requireMuxableAudio(format)
                if (mime.startsWith("video/")) rotation = rotationOf(format)
                extractor.selectTrack(i)
                formats[i] = format
                maxInput = max(maxInput, maxInputSize(format))
            }

            if (formats.isEmpty()) {
                throw MediaOpException(
                    if (audioOnly) "This file has no audio track to extract."
                    else "This file has no track that can be copied."
                )
            }

            rotation?.let { muxer.setOrientationHint(it) }
            formats.forEach { (input, format) -> trackMap[input] = muxer.addTrack(format) }
            muxer.start()

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(if (maxInput > 0) maxInput else DEFAULT_BUFFER)
            val info = MediaCodec.BufferInfo()
            val span = (endUs - startUs).coerceAtLeast(1L)

            var firstSampleUs = -1L
            var lastSampleUs = startUs
            // Tracks don't end together: video may run past endUs while audio still has samples
            // inside the window, so each track is retired on its own.
            val finished = HashSet<Int>()

            while (finished.size < trackMap.size) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val outputTrack = trackMap[trackIndex]
                if (outputTrack == null) {
                    if (!extractor.advance()) break
                    continue
                }

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) break
                if (sampleTimeUs > endUs) {
                    finished += trackIndex
                    if (!extractor.advance()) break
                    continue
                }

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                if (firstSampleUs < 0) firstSampleUs = sampleTimeUs
                lastSampleUs = max(lastSampleUs, sampleTimeUs)

                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTimeUs - firstSampleUs).coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)

                onProgress(((sampleTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f))
                if (!extractor.advance()) break
            }

            if (firstSampleUs < 0) {
                throw MediaOpException("No media found in the selected range.")
            }
            onProgress(1f)
            return TrimResult(firstSampleUs, lastSampleUs)
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun copyAll(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        buffer: ByteBuffer,
        onSampleTime: (Long) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val time = extractor.sampleTime
            info.offset = 0
            info.size = size
            info.presentationTimeUs = time.coerceAtLeast(0L)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            onSampleTime(time)
            if (!extractor.advance()) break
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    /** MediaMuxer's MP4 writer only accepts AAC and the legacy AMR codecs. */
    fun isMuxableAudio(mime: String): Boolean =
        mime.startsWith(MediaFormat.MIMETYPE_AUDIO_AAC) ||
            mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB ||
            mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB

    private fun requireMuxableAudio(format: MediaFormat) {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!isMuxableAudio(mime)) {
            throw MediaOpException(
                "This file's audio ($mime) can't be copied into an MP4. Export it as MP3 instead."
            )
        }
    }

    private fun rotationOf(format: MediaFormat): Int? =
        if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else null

    private fun durationOf(format: MediaFormat): Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(DEFAULT_BUFFER)
        } else {
            DEFAULT_BUFFER
        }
}
