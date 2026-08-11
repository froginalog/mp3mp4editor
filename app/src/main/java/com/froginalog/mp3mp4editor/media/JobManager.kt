package com.froginalog.mp3mp4editor.media

import android.content.Context
import android.net.Uri
import com.froginalog.mp3mp4editor.service.MediaJobService
import com.froginalog.mp3mp4editor.util.Files
import com.froginalog.mp3mp4editor.util.TimeFmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the download/convert/trim queue.
 *
 * Work runs on a single background slot — encoders and muxers are hardware-backed and running two
 * at once on a phone tends to be slower than running them back to back.
 */
class JobManager(
    private val context: Context,
    private val downloader: FileDownloader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slot = Mutex()
    private val ids = AtomicLong(0)
    private val coroutines = ConcurrentHashMap<Long, Job>()
    private val cancelFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    private val _jobs = MutableStateFlow<List<MediaJob>>(emptyList())
    val jobs: StateFlow<List<MediaJob>> = _jobs.asStateFlow()

    fun enqueue(spec: JobSpec): Long {
        val id = ids.incrementAndGet()
        val cancelled = AtomicBoolean(false)
        cancelFlags[id] = cancelled

        _jobs.update { it + MediaJob(id = id, title = spec.title, detail = describe(spec)) }
        MediaJobService.start(context)

        coroutines[id] = scope.launch {
            try {
                slot.withLock {
                    if (cancelled.get()) {
                        finishCancelled(id)
                        return@withLock
                    }
                    update(id) { it.copy(state = JobState.RUNNING, status = "Starting…") }
                    val result = execute(id, spec) { cancelled.get() }
                    update(id) {
                        it.copy(
                            state = JobState.DONE,
                            progress = 1f,
                            status = "Saved to ${result.folder}",
                            outputUri = result.uri,
                            outputMime = result.mimeType,
                        )
                    }
                }
            } catch (e: InterruptedException) {
                finishCancelled(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishCancelled(id)
                throw e
            } catch (t: Throwable) {
                update(id) {
                    it.copy(
                        state = JobState.FAILED,
                        status = "Failed",
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                coroutines.remove(id)
                cancelFlags.remove(id)
            }
        }
        return id
    }

    fun cancel(id: Long) {
        cancelFlags[id]?.set(true)
        coroutines[id]?.cancel()
    }

    /** Removes a finished job from the list. Active jobs are cancelled first. */
    fun dismiss(id: Long) {
        cancel(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.isActive } }
    }

    // -- execution -------------------------------------------------------------------------

    private class Result(val uri: Uri, val mimeType: String, val folder: String)

    private fun execute(id: Long, spec: JobSpec, cancelled: () -> Boolean): Result = when (spec) {
        is JobSpec.YoutubeVideo -> runYoutubeVideo(id, spec, cancelled)
        is JobSpec.YoutubeAudio -> runYoutubeAudio(id, spec, cancelled)
        is JobSpec.LocalExport -> runLocalExport(id, spec, cancelled)
    }

    private fun runYoutubeVideo(
        id: Long,
        spec: JobSpec.YoutubeVideo,
        cancelled: () -> Boolean,
    ): Result {
        val temps = ArrayList<File>()
        try {
            val videoFile = Files.newTempFile(context, ".mp4").also { temps += it }
            // With a merge the audio download shares the download phase's progress budget.
            val downloadShare = if (spec.choice.needsAudioMerge) 0.55f else 0.9f

            update(id) { it.copy(status = "Downloading video…") }
            downloader.download(spec.choice.video.url, videoFile, cancelled) { done, total ->
                reportDownload(id, "Downloading video", done, total, 0f, downloadShare)
            }

            var merged: File? = null
            if (spec.choice.needsAudioMerge) {
                val audio = spec.mergeAudio
                    ?: throw Mp4Tools.MediaOpException("No compatible audio track for this quality.")
                val audioFile = Files.newTempFile(context, ".m4a").also { temps += it }
                update(id) { it.copy(status = "Downloading audio…") }
                downloader.download(audio.source.url, audioFile, cancelled) { done, total ->
                    reportDownload(id, "Downloading audio", done, total, downloadShare, 0.8f)
                }

                if (cancelled()) throw InterruptedException()
                update(id) { it.copy(status = "Combining video and audio…") }
                val output = Files.newTempFile(context, ".mp4").also { temps += it }
                Mp4Tools.mux(videoFile, audioFile, output) { fraction ->
                    setProgress(id, 0.8f + fraction * 0.15f)
                }
                merged = output
            }

            val finalFile = merged ?: videoFile
            update(id) { it.copy(status = "Saving…", progress = 0.96f) }
            val name = "${Files.sanitize(spec.meta.title)} (${spec.choice.label}).mp4"
            val uri = MediaStoreWriter.publish(context, finalFile, name, OutputFormat.Mp4.mimeType)
            temps.remove(finalFile)
            return Result(uri, OutputFormat.Mp4.mimeType, "Movies/${MediaStoreWriter.FOLDER}")
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private fun runYoutubeAudio(
        id: Long,
        spec: JobSpec.YoutubeAudio,
        cancelled: () -> Boolean,
    ): Result {
        val temps = ArrayList<File>()
        try {
            val sourceFile = Files.newTempFile(context, ".${spec.audio.source.suffix}").also { temps += it }
            update(id) { it.copy(status = "Downloading audio…") }
            val downloadShare = if (spec.format is OutputFormat.Mp3) 0.6f else 0.9f
            downloader.download(spec.audio.source.url, sourceFile, cancelled) { done, total ->
                reportDownload(id, "Downloading audio", done, total, 0f, downloadShare)
            }
            if (cancelled()) throw InterruptedException()

            val baseName = Files.sanitize(spec.meta.title)
            val finalFile: File = when (val format = spec.format) {
                is OutputFormat.Mp3 -> {
                    update(id) { it.copy(status = "Converting to MP3…") }
                    val output = Files.newTempFile(context, ".mp3").also { temps += it }
                    encodeMp3(
                        source = Uri.fromFile(sourceFile),
                        output = output,
                        startUs = 0L,
                        endUs = Long.MAX_VALUE,
                        bitrateKbps = format.bitrateKbps,
                        cancelled = cancelled,
                    ) { fraction -> setProgress(id, 0.6f + fraction * 0.35f) }
                    output
                }

                else -> sourceFile
            }

            update(id) { it.copy(status = "Saving…", progress = 0.96f) }
            val name = "$baseName.${spec.format.extension}"
            val uri = MediaStoreWriter.publish(context, finalFile, name, spec.format.mimeType)
            temps.remove(finalFile)
            return Result(uri, spec.format.mimeType, "Music/${MediaStoreWriter.FOLDER}")
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private fun runLocalExport(
        id: Long,
        spec: JobSpec.LocalExport,
        cancelled: () -> Boolean,
    ): Result {
        val temps = ArrayList<File>()
        try {
            val startUs = spec.startMs * 1000L
            val endUs = spec.endMs * 1000L
            val base = Files.sanitize(Files.stripExtension(spec.sourceName))
            val output = Files.newTempFile(context, ".${spec.format.extension}").also { temps += it }

            when (val format = spec.format) {
                is OutputFormat.Mp3 -> {
                    update(id) { it.copy(status = "Encoding MP3…") }
                    encodeMp3(spec.source, output, startUs, endUs, format.bitrateKbps, cancelled) {
                        setProgress(id, it * 0.95f)
                    }
                }

                is OutputFormat.Mp4, is OutputFormat.M4a -> {
                    update(id) { it.copy(status = "Trimming…") }
                    Mp4Tools.trim(
                        context = context,
                        source = spec.source,
                        output = output,
                        startUs = startUs,
                        endUs = endUs,
                        audioOnly = format == OutputFormat.M4a,
                    ) { fraction -> setProgress(id, fraction * 0.95f) }
                }
            }

            if (cancelled()) throw InterruptedException()
            update(id) { it.copy(status = "Saving…", progress = 0.97f) }

            val clipTag = "${TimeFmt.clock(spec.startMs, withMillis = false)}-" +
                TimeFmt.clock(spec.endMs, withMillis = false)
            val name = "$base [${clipTag.replace(':', '.')}].${spec.format.extension}"
            val uri = MediaStoreWriter.publish(context, output, name, spec.format.mimeType)
            temps.remove(output)

            val folder = if (spec.format == OutputFormat.Mp4) {
                "Movies/${MediaStoreWriter.FOLDER}"
            } else {
                "Music/${MediaStoreWriter.FOLDER}"
            }
            return Result(uri, spec.format.mimeType, folder)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private fun encodeMp3(
        source: Uri,
        output: File,
        startUs: Long,
        endUs: Long,
        bitrateKbps: Int,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ) {
        val sink = Mp3Sink(output, bitrateKbps)
        try {
            PcmDecoder.decode(
                context = context,
                source = source,
                startUs = startUs,
                endUs = endUs,
                sink = sink,
                isCancelled = cancelled,
                onProgress = onProgress,
            )
        } catch (t: Throwable) {
            sink.close()
            throw t
        }
    }

    // -- state plumbing --------------------------------------------------------------------

    private fun reportDownload(
        id: Long,
        label: String,
        done: Long,
        total: Long,
        from: Float,
        to: Float,
    ) {
        if (total > 0) {
            val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
            update(id) {
                it.copy(
                    progress = from + fraction * (to - from),
                    status = "$label · ${TimeFmt.bytes(done)} / ${TimeFmt.bytes(total)}",
                )
            }
        } else {
            update(id) { it.copy(progress = null, status = "$label · ${TimeFmt.bytes(done)}") }
        }
    }

    private fun setProgress(id: Long, value: Float) {
        update(id) { it.copy(progress = value.coerceIn(0f, 1f)) }
    }

    private fun finishCancelled(id: Long) {
        update(id) { it.copy(state = JobState.CANCELLED, status = "Cancelled", progress = null) }
    }

    private fun update(id: Long, transform: (MediaJob) -> MediaJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun describe(spec: JobSpec): String = when (spec) {
        is JobSpec.YoutubeVideo -> "MP4 · ${spec.choice.label}"
        is JobSpec.YoutubeAudio -> when (val f = spec.format) {
            is OutputFormat.Mp3 -> "MP3 · ${f.bitrateKbps} kbps"
            else -> "M4A · original quality"
        }

        is JobSpec.LocalExport -> {
            val range = "${TimeFmt.clock(spec.startMs, false)} → ${TimeFmt.clock(spec.endMs, false)}"
            "${spec.format.extension.uppercase()} · $range"
        }
    }
}
