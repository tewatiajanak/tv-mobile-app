package com.videobridge.core.tvdesignsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

// TV is dark by default (living rooms are dark) and never uses pure white backgrounds.
private val TvColors =
    darkColorScheme(
        primary = Color(0xFFB4C5FF),
        secondary = Color(0xFF6FDACB),
        background = Color(0xFF0F1115),
        surface = Color(0xFF1A1D24),
    )

@Composable
fun VideoBridgeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColors, content = content)
}
