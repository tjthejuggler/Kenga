package com.example.kenga.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = KengaOchre80,
    onPrimary = OchreOnDark,
    primaryContainer = OchreContainerDark,
    onPrimaryContainer = KengaOchre90,
    secondary = KengaPurple80,
    onSecondary = PurpleOnDark,
    secondaryContainer = PurpleContainerDark,
    onSecondaryContainer = KengaPurple90,
    tertiary = KengaEucalyptus80,
    onTertiary = EucalyptusOnDark,
    background = Dusk,
    onBackground = SandDark,
    surface = DuskSurface,
    onSurface = SandDark,
    surfaceVariant = DuskVariant,
    onSurfaceVariant = SandMuted,
)

private val LightColors = lightColorScheme(
    primary = KengaOchre40,
    onPrimary = Color.White,
    primaryContainer = OchreContainerLight,
    onPrimaryContainer = OchreOnContainerLight,
    secondary = KengaPurple40,
    onSecondary = Color.White,
    secondaryContainer = PurpleContainerLight,
    onSecondaryContainer = PurpleOnContainerLight,
    tertiary = KengaEucalyptus40,
    onTertiary = Color.White,
    background = Sand,
    onBackground = CharcoalDeep,
    surface = SandSurface,
    onSurface = CharcoalDeep,
    surfaceVariant = SandVariant,
    onSurfaceVariant = CharcoalMuted,
)

@Composable
fun KengaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand identity first: dynamic color would wash out the outback palette.
    // (Pass true to restore Material You on Android 12+.)
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
