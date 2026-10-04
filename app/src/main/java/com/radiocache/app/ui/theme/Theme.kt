package com.radiocache.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/** App Material 3 theme. Car-friendly contrast handled in Stage 7. */
@Composable
fun RadioCacheTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Stable per-station brand colors. Known stations get hand-picked colors;
 * anything else falls back to a hash-derived hue so the color is stable
 * across restarts and identical on every screen (Stations, Home, Player).
 */
object StationColors {
    // Public-domain build: these are OUR palette entries, not third-party
    // brand colours (the previous map used broadcasters' trademark colours).
    private val fixed = mapOf(
        "commons" to Color(0xFF00695C), // commons teal
        "librivox" to Color(0xFF5D4037), // book brown
    )

    fun forStation(stationId: String): Color =
        fixed[stationId] ?: run {
            val hue = (stationId.hashCode() and 0xFFFFFF) % 360
            Color.hsv(hue.toFloat(), 0.55f, 0.85f)
        }

    /** Readable foreground (initial letter) for the background color. */
    fun onColor(background: Color): Color =
        if (background.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
}
