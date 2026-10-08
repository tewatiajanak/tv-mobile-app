package com.videobridge.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Placeholder brand palette; the real one arrives with the Phase 9 design system.
private val BrandBlue = Color(0xFF2456D6)
private val BrandBlueLight = Color(0xFFB4C5FF)
private val BrandTeal = Color(0xFF00897B)
private val BrandTealLight = Color(0xFF6FDACB)

private val LightColors = lightColorScheme(primary = BrandBlue, secondary = BrandTeal)
private val DarkColors = darkColorScheme(primary = BrandBlueLight, secondary = BrandTealLight)

val VideoBridgeTypography = Typography()

@Composable
fun VideoBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the brand colours show; a Settings toggle comes in Phase 9.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColors

            else -> LightColors
        }
    MaterialTheme(colorScheme = colorScheme, typography = VideoBridgeTypography, content = content)
}
