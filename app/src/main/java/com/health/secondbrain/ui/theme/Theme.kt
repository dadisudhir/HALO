package com.health.secondbrain.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type system. The original spec calls for Caveat + Gaegu (handwritten Google fonts).
 * For the hackathon shell we use the system serif/sans pair so the build runs without
 * fetching downloadable fonts. Swap to androidx.compose.ui.text.googlefonts.GoogleFont
 * once we have a Play Services API key configured.
 */
object Type {
    private val Display = FontFamily.Serif      // stand-in for Caveat
    private val Body    = FontFamily.SansSerif  // stand-in for Gaegu

    val titleHero   = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold,   fontSize = 30.sp, letterSpacing = 0.sp)
    val titleScreen = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold,   fontSize = 26.sp)
    val titleSection= TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold,   fontSize = 22.sp)
    val metricValue = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold,   fontSize = 30.sp)

    val body        = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Normal, fontSize = 14.sp)
    val bodySmall   = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val bodyBold    = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Bold,   fontSize = 14.sp)
    val label       = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Bold,   fontSize = 11.sp, letterSpacing = 1.sp)
    val caption     = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Normal, fontSize = 11.sp)
    val tinyBold    = TextStyle(fontFamily = Body,    fontWeight = FontWeight.Bold,   fontSize = 12.sp)
}

@Composable
fun HealthTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.BgBase,
            surface = Palette.Surface,
            primary = Palette.TextPrimary,
            onPrimary = Palette.BgBase,
            onBackground = Palette.TextPrimary,
            onSurface = Palette.TextPrimary,
        ),
        typography = Typography(),
        content = content,
    )
}
