package com.froginalog.mp3mp4editor

import android.app.Application
import android.content.Context
import com.froginalog.mp3mp4editor.media.FileDownloader
import com.froginalog.mp3mp4editor.media.JobManager
import com.froginalog.mp3mp4editor.util.Files
import com.froginalog.mp3mp4editor.youtube.YoutubeRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled service locator — the app is small enough that a DI framework would be noise. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val youtube: YoutubeRepository by lazy { YoutubeRepository(httpClient) }

    val jobManager: JobManager by lazy { JobManager(appContext, FileDownloader(httpClient)) }
}

class EditorApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Partial downloads from a previous run are worthless once their signed URLs expire.
        Thread { Files.clearWorkDir(this) }.start()
    }
}
