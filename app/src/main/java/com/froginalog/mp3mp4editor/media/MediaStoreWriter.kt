package com.froginalog.mp3mp4editor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Publishes finished files into the shared Movies/Music collections. */
object MediaStoreWriter {

    const val FOLDER = "MP3MP4Editor"

    /**
     * Moves [source] into the public collection under [displayName] and returns its content URI.
     * [source] is deleted afterwards — it is always a file from our own cache directory.
     */
    fun publish(context: Context, source: File, displayName: String, mimeType: String): Uri {
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (isVideo) {
            "${Environment.DIRECTORY_MOVIES}/$FOLDER"
        } else {
            "${Environment.DIRECTORY_MUSIC}/$FOLDER"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: throw Mp4Tools.MediaOpException("Couldn't create the output file.")

        try {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "Couldn't open the output file." }
                source.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        source.delete()
        return uri
    }

    /** Everything this app has published, newest first. */
    fun listOutputs(context: Context): List<OutputEntry> {
        val results = ArrayList<OutputEntry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"

        for (isVideo in listOf(true, false)) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val args = arrayOf("%$FOLDER%")
            runCatching {
                context.contentResolver.query(
                    collection, projection, selection, args,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        results += OutputEntry(
                            uri = Uri.withAppendedPath(collection, id.toString()),
                            name = cursor.getString(nameCol) ?: "(unnamed)",
                            sizeBytes = cursor.getLong(sizeCol),
                            mimeType = cursor.getString(mimeCol) ?: "*/*",
                            addedEpochSec = cursor.getLong(dateCol),
                            durationMs = if (cursor.isNull(durationCol)) 0L else cursor.getLong(durationCol),
                        )
                    }
                }
            }
        }
        return results.sortedByDescending { it.addedEpochSec }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

data class OutputEntry(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val addedEpochSec: Long,
    val durationMs: Long,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}
