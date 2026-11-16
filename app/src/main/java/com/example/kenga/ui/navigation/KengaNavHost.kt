package com.example.kenga.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.kenga.ui.body.BodyScreen
import com.example.kenga.ui.library.LibraryScreen
import com.example.kenga.ui.library.VideoDetailScreen
import com.example.kenga.ui.log.LogScreen
import com.example.kenga.ui.settings.SettingsScreen
import com.example.kenga.ui.stats.StatsScreen

/**
 * Top-level [NavHost] wiring the five bottom-nav destinations.
 *
 * The [NavHostController] is passed in from [com.example.kenga.MainActivity]
 * (exactly one shared controller — bar and content hit the same graph).
 * Later phases add the video-detail and player routes here.
 */
@Composable
fun KengaNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = KengaDestination.BODY.route,
        modifier = modifier,
    ) {
        composable(KengaDestination.BODY.route) {
            BodyScreen(
                onOpenLog = {
                    navController.navigate(KengaDestination.LOG.route) { launchSingleTop = true }
                },
            )
        }
        composable(KengaDestination.LIBRARY.route) {
            LibraryScreen(
                onOpenVideo = { videoId ->
                    navController.navigate("video/$videoId")
                },
            )
        }

        // Video detail (routed from Library cards).
        composable("video/{videoId}") { entry ->
            val videoId = entry.arguments?.getString("videoId").orEmpty()
            VideoDetailScreen(
                videoId = videoId,
                onBack = { navController.popBackStack() },
                onOpenLog = {
                    navController.navigate(KengaDestination.LOG.route) { launchSingleTop = true }
                },
            )
        }
        composable(KengaDestination.LOG.route) { LogScreen() }
        composable(KengaDestination.STATS.route) { StatsScreen() }
        composable(KengaDestination.SETTINGS.route) { SettingsScreen() }
    }
}
