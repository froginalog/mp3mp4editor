package com.froginalog.mp3mp4editor.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Receives interleaved 16-bit PCM as it comes out of the decoder. */
interface PcmSink {
    /** Called once, before any [write], with the decoder's real output format. */
    fun onFormat(sampleRate: Int, channelCount: Int)

    /** [pcm] holds [length] valid interleaved samples. The array is reused — copy if you keep it. */
    fun write(pcm: ShortArray, length: Int)

    fun finish()
}

/**
 * Decodes the audio track of any file Android can read into raw PCM, optionally restricted to a
 * `[startUs, endUs)` window, and pushes it at a [PcmSink].
 *
 * This is the front half of MP3 export: MediaCodec has no MP3 encoder, so we decode here and hand
 * the samples to LAME.
 */
object PcmDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decode(
        context: Context,
        source: Uri,
        startUs: Long,
        endUs: Long,
        sink: PcmSink,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            val format = inputFormat
                ?: throw Mp4Tools.MediaOpException("This file has no audio track.")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Callers that just want "the whole file" pass Long.MAX_VALUE; pin it to the real
            // duration so progress reporting means something.
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val stopUs = if (endUs == Long.MAX_VALUE && trackDurationUs > 0) trackDurationUs else endUs

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var formatAnnounced = false
            var sawInputEos = false
            var sawOutputEos = false
            var scratch = ShortArray(8192)
            val span = (stopUs - startUs).coerceAtLeast(1L)

            while (!sawOutputEos) {
                if (isCancelled()) throw InterruptedException("Cancelled")

                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val sampleTime = extractor.sampleTime
                        if (size < 0 || sampleTime < 0 || sampleTime > stopUs) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outputIndex < 0) continue
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            if (!formatAnnounced) {
                                sink.onFormat(sampleRate, channels)
                                formatAnnounced = true
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            var count = shorts.remaining()

                            // Clip the partial buffers at either end of the requested window.
                            var skip = framesToSkip(info.presentationTimeUs, startUs, sampleRate) * channels
                            val overhang = framesOverEnd(
                                info.presentationTimeUs, stopUs, sampleRate, count / channels
                            ) * channels
                            if (overhang > 0) count -= overhang
                            if (skip > count) skip = count

                            val usable = count - skip
                            if (usable > 0) {
                                if (scratch.size < usable) scratch = ShortArray(usable)
                                shorts.position(skip)
                                shorts.get(scratch, 0, usable)
                                sink.write(scratch, usable)
                            }
                            onProgress(
                                ((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f)
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        if (info.presentationTimeUs > stopUs) sawOutputEos = true
                    }
                }
            }

            if (!formatAnnounced) sink.onFormat(sampleRate, channels)
            sink.finish()
            onProgress(1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun framesToSkip(bufferStartUs: Long, startUs: Long, sampleRate: Int): Int {
        if (bufferStartUs >= startUs) return 0
        val deltaUs = startUs - bufferStartUs
        return (deltaUs * sampleRate / 1_000_000L).toInt().coerceAtLeast(0)
    }

    private fun framesOverEnd(
        bufferStartUs: Long,
        endUs: Long,
        sampleRate: Int,
        frames: Int,
    ): Int {
        val bufferEndUs = bufferStartUs + frames * 1_000_000L / sampleRate.coerceAtLeast(1)
        if (bufferEndUs <= endUs) return 0
        val overUs = bufferEndUs - endUs
        return (overUs * sampleRate / 1_000_000L).toInt().coerceIn(0, frames)
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
