package com.froginalog.mp3mp4editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.froginalog.mp3mp4editor.ui.AppRoot
import com.froginalog.mp3mp4editor.ui.theme.EditorTheme
import com.froginalog.mp3mp4editor.youtube.YoutubeRepository

class MainActivity : ComponentActivity() {

    private val sharedLink = mutableStateOf<String?>(null)
    private val openedMedia = mutableStateOf<Uri?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        askForNotifications()

        setContent {
            EditorTheme {
                AppRoot(
                    sharedLink = sharedLink.value,
                    onSharedLinkConsumed = { sharedLink.value = null },
                    openedMedia = openedMedia.value,
                    onOpenedMediaConsumed = { openedMedia.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                YoutubeRepository.normalizeUrl(text)?.let { sharedLink.value = it }
            }

            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val type = intent.type.orEmpty()
                if (type.startsWith("video/") || type.startsWith("audio/")) {
                    openedMedia.value = data
                } else {
                    YoutubeRepository.normalizeUrl(data.toString())?.let { sharedLink.value = it }
                }
            }
        }
    }

    /** Progress notifications are nice to have; the app works without them. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
