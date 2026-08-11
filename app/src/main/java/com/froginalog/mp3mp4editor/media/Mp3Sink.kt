package com.froginalog.mp3mp4editor.media

import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * [PcmSink] that writes an MP3 with LAME.
 *
 * This is the only place the LAME binding is referenced — see the README if the `TAndroidLame`
 * dependency ever needs replacing.
 */
class Mp3Sink(
    private val output: File,
    private val bitrateKbps: Int,
) : PcmSink {

    private var lame: AndroidLame? = null
    private var stream: BufferedOutputStream? = null
    private var channels = 2

    private var left = ShortArray(0)
    private var right = ShortArray(0)
    private var mp3Buffer = ByteArray(0)

    override fun onFormat(sampleRate: Int, channelCount: Int) {
        channels = channelCount.coerceIn(1, 2)
        lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutBitrate(bitrateKbps)
            .setOutChannels(channels)
            .setQuality(2)
            .setMode(if (channels == 1) LameBuilder.Mode.MONO else LameBuilder.Mode.STEREO)
            .build()
        stream = BufferedOutputStream(FileOutputStream(output), 1 shl 16)
    }

    override fun write(pcm: ShortArray, length: Int) {
        val encoder = lame ?: return
        val out = stream ?: return
        val frames = length / channels
        if (frames <= 0) return

        ensureCapacity(frames)
        if (channels == 1) {
            System.arraycopy(pcm, 0, left, 0, frames)
        } else {
            var i = 0
            while (i < frames) {
                left[i] = pcm[i * 2]
                right[i] = pcm[i * 2 + 1]
                i++
            }
        }

        val written = if (channels == 1) {
            encoder.encode(left, left, frames, mp3Buffer)
        } else {
            encoder.encode(left, right, frames, mp3Buffer)
        }
        if (written > 0) out.write(mp3Buffer, 0, written)
    }

    override fun finish() {
        val encoder = lame
        val out = stream
        if (encoder != null && out != null) {
            if (mp3Buffer.size < 8192) mp3Buffer = ByteArray(8192)
            val flushed = encoder.flush(mp3Buffer)
            if (flushed > 0) out.write(mp3Buffer, 0, flushed)
            out.flush()
        }
        close()
    }

    /** Safe to call twice; used on the error path too. */
    fun close() {
        runCatching { stream?.close() }
        runCatching { lame?.close() }
        stream = null
        lame = null
    }

    private fun ensureCapacity(frames: Int) {
        if (left.size < frames) {
            left = ShortArray(frames)
            right = ShortArray(frames)
        }
        // LAME's documented worst case for the output buffer.
        val needed = (frames * 1.25).toInt() + 7200
        if (mp3Buffer.size < needed) mp3Buffer = ByteArray(needed)
    }
}
