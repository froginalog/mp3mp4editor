package com.froginalog.mp3mp4editor.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.froginalog.mp3mp4editor.ui.download.DownloadScreen
import com.froginalog.mp3mp4editor.ui.edit.EditScreen
import com.froginalog.mp3mp4editor.ui.edit.PendingEdit
import com.froginalog.mp3mp4editor.ui.library.LibraryScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("download", "Get", Icons.Filled.Download),
    Tab("edit", "Trim", Icons.Filled.ContentCut),
    Tab("library", "Library", Icons.Filled.LibraryMusic),
)

@Composable
fun AppRoot(
    sharedLink: String?,
    onSharedLinkConsumed: () -> Unit,
    openedMedia: Uri?,
    onOpenedMediaConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    // A media file opened from outside the app goes straight to the trimmer.
    LaunchedEffect(openedMedia) {
        val uri = openedMedia ?: return@LaunchedEffect
        PendingEdit.request(uri)
        onOpenedMediaConsumed()
        navController.navigate("edit") { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "download",
            modifier = Modifier.padding(padding),
        ) {
            composable("download") {
                DownloadScreen(
                    sharedLink = sharedLink,
                    onSharedLinkConsumed = onSharedLinkConsumed,
                )
            }
            composable("edit") { EditScreen() }
            composable("library") {
                LibraryScreen(
                    onTrim = { uri ->
                        PendingEdit.request(uri)
                        navController.navigate("edit") { launchSingleTop = true }
                    }
                )
            }
        }
    }
}
