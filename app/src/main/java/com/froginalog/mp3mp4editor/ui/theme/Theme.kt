package com.froginalog.mp3mp4editor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Purple = Color(0xFF7C5CFF)
private val PurpleDark = Color(0xFFB69CFF)
private val Teal = Color(0xFF17BEBB)

private val LightColors = lightColorScheme(
    primary = Purple,
    secondary = Teal,
    tertiary = Color(0xFFE05263),
)

private val DarkColors = darkColorScheme(
    primary = PurpleDark,
    secondary = Teal,
    tertiary = Color(0xFFFF8A94),
)

@Composable
fun EditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        // Material You on the phones that support it.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
