package com.example.kenga.ui.navigation

import androidx.annotation.DrawableRes
import com.example.kenga.R

/** Bottom-nav destinations; routes are stable strings used by [KengaNavHost]. */
enum class KengaDestination(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
) {
    BODY("body", "Body", R.drawable.ic_nav_body),
    LIBRARY("library", "Library", R.drawable.ic_nav_library),
    LOG("log", "Log", R.drawable.ic_nav_log),
    STATS("stats", "Stats", R.drawable.ic_nav_stats),
    SETTINGS("settings", "Settings", R.drawable.ic_nav_settings),
}
