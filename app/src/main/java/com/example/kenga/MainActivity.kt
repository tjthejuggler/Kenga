package com.example.kenga

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.kenga.ui.components.KengaBackground
import com.example.kenga.ui.navigation.KengaBottomBar
import com.example.kenga.ui.navigation.KengaDestination
import com.example.kenga.ui.navigation.KengaNavHost
import com.example.kenga.ui.theme.KengaTheme
import com.example.kenga.widget.KengaWidgetProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            KengaTheme {
                KengaApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep the home-screen widget in sync: sets logged since it last
        // rendered change the tier colors on its kangaroo-man.
        KengaWidgetProvider.requestUpdate(this)
        // Tail combined-scene widget: refresh our served figure + ping
        // Tail (cheap no-op ping when Tail isn't installed).
        com.example.kenga.widget.FigureSnapshotProvider.refreshAndPing(this)
    }
}

/**
 * App scaffold: outback bottom bar + [KengaNavHost] content.
 *
 * Exactly ONE [NavHostController] is created here and passed down into
 * [KengaNavHost] (Octo Phase 9 root-cause fix carried over): the bar and the
 * content must navigate the same graph.
 */
@Composable
fun KengaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val darkTheme = isSystemInDarkTheme()

    // One ambient outback backdrop behind every destination; screens render
    // on top with transparent backgrounds.
    Box(modifier = Modifier.fillMaxSize()) {
        KengaBackground(darkTheme = darkTheme, modifier = Modifier.fillMaxSize())
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            // Transparent container means contentColorFor() can't derive a content
            // color — without this every Text without an explicit color renders
            // Compose's fallback BLACK on the dark dusk.
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                KengaBottomBar(
                    destinations = KengaDestination.entries.toList(),
                    currentRoute = currentRoute,
                    onNavigate = { destination -> navigateToTab(navController, destination) },
                )
            },
        ) { innerPadding ->
            KengaNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * Top-level tab navigation: pop everything above the start destination, save
 * and restore per-tab state, never stack duplicates.
 */
private fun navigateToTab(navController: NavHostController, destination: KengaDestination) {
    val startId = navController.graph.findStartDestination().id
    navController.navigate(destination.route) {
        popUpTo(startId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
