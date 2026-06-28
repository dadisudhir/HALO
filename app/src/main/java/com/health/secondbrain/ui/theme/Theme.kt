package com.health.secondbrain.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type system using friendly fonts.
 * Display: Poppins (friendly, warm)
 * Body: Inter (balanced, approachable)
 */
object Type {
    private val Display = FontFamily.SansSerif      // Poppins (friendly)
    private val Body    = FontFamily.SansSerif       // Inter (balanced)

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
        colorScheme = lightColorScheme(
            background = Palette.BgBase,
            surface = Palette.Surface,
            primary = Palette.Heart,
            onPrimary = Palette.SurfaceElev,
            onBackground = Palette.TextPrimary,
            onSurface = Palette.TextPrimary,
        ),
        typography = Typography(),
        content = content,
    )
}
