package com.froginalog.mp3mp4editor.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object Files {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** Turns an arbitrary video title into something safe for a file name. */
    fun sanitize(name: String, fallback: String = "media"): String {
        val cleaned = ILLEGAL.replace(name, "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        val bounded = if (cleaned.length > 100) cleaned.substring(0, 100).trim() else cleaned
        return bounded.ifEmpty { fallback }
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** Duration in milliseconds, or 0 when the file cannot be probed. */
    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    data class Probe(
        val durationMs: Long,
        val hasVideo: Boolean,
        /** Mime type of the first audio track, e.g. `audio/mp4a-latm`. Null when there is none. */
        val audioMime: String?,
    )

    fun probe(context: Context, uri: Uri): Probe {
        val duration = durationMs(context, uri)
        var hasVideo = false
        var audioMime: String? = null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> hasVideo = true
                    mime.startsWith("audio/") && audioMime == null -> audioMime = mime
                }
            }
        } catch (t: Throwable) {
            // Leave the defaults; the duration check upstream reports the failure.
        } finally {
            runCatching { extractor.release() }
        }
        return Probe(duration, hasVideo, audioMime)
    }

    fun workDir(context: Context): File =
        File(context.cacheDir, "work").apply { mkdirs() }

    fun newTempFile(context: Context, suffix: String): File =
        File.createTempFile("job_", suffix, workDir(context))

    fun clearWorkDir(context: Context) {
        workDir(context).listFiles()?.forEach { it.delete() }
    }
}
