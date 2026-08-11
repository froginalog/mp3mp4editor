package com.froginalog.mp3mp4editor.media

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Streams a URL to disk, resuming from whatever is already there when the server allows it. */
class FileDownloader(private val client: OkHttpClient) {

    fun download(
        url: String,
        destination: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val existing = if (destination.exists()) destination.length() else 0L

        val builder = Request.Builder()
            .url(url)
            // YouTube's media hosts are picky about clients with no UA.
            .header("User-Agent", USER_AGENT)
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("Empty response from the server.")
            val resuming = response.code == 206 && existing > 0
            var downloaded = if (resuming) existing else 0L
            val total = if (body.contentLength() >= 0) downloaded + body.contentLength() else -1L

            if (!resuming && existing > 0) destination.delete()

            RandomAccessFile(destination, "rw").use { file ->
                file.seek(downloaded)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastReported = 0L
                    while (true) {
                        if (isCancelled()) throw InterruptedException("Cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        // Reporting on every chunk would spam the UI; ~256 KB is plenty.
                        if (downloaded - lastReported >= REPORT_EVERY) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
                file.setLength(downloaded)
            }
            onProgress(downloaded, if (total > 0) total else downloaded)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val REPORT_EVERY = 256 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
