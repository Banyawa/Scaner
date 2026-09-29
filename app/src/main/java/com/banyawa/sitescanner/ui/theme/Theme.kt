package com.banyawa.sitescanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFFB45F00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB8),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF3B6939),
    onTertiary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF004786),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFFFB960),
    onSecondary = Color(0xFF472A00),
    secondaryContainer = Color(0xFF653E00),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFFA1D39A),
    onTertiary = Color(0xFF0A390F),
)

@Composable
fun SiteScannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Colours used for drawings (plan view), independent of light/dark theme. */
object PlanColors {
    val Background = Color(0xFFFDFCF8)
    val Grid = Color(0xFFE3E6EA)
    val Slice = Color(0xFF9AA3AD)
    val Wall = Color(0xFF1B2733)
    val Dimension = Color(0xFF0B5CAD)
    val Measurement = Color(0xFFD84315)
    val Door = Color(0xFF00897B)
    val Window = Color(0xFF1E6FD9)
    val WindowFill = Color(0x331E6FD9)

    /** Scan points standing in front of a wall on an elevation (sockets, pipes, cabinets). */
    val ElevationFront = Color(0xFFEF6C00)
    val ElevationKey = Color(0xFFE65100)
}
