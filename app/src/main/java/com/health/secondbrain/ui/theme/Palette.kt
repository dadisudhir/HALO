package com.health.secondbrain.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Unified dark-mode palette. We intentionally diverge from the source spec
 * (which switches to a light background on detail screens) and keep every
 * surface dark for OLED/contrast and visual consistency.
 */
object Palette {
    // Backgrounds
    val BgBase        = Color(0xFF0B0D10)   // app background
    val BgRadialMid   = Color(0xFF161A22)   // home radial gradient center
    val Surface       = Color(0xFF14171C)   // cards
    val SurfaceElev   = Color(0xFF1C2027)   // raised cards / chat bubble
    val Border        = Color(0xFF262B33)
    val BorderSubtle  = Color(0xFF1C2027)

    // Text
    val TextPrimary   = Color(0xFFECEAE4)
    val TextSecondary = Color(0xFF8B8F99)
    val TextMuted     = Color(0xFF5E626B)

    // Bubble decoration
    val BubbleUnfilledFill   = Color(0xFF14171C)
    val BubbleUnfilledStroke = Color(0xFF262B33)

    // Status / semantic
    val Healthy   = Color(0xFF6BCB77)
    val Warning   = Color(0xFFF2A65A)
    val Urgent    = Color(0xFFFF6B6B)

    // Organ accents (dark-mode tuned)
    val Heart   = Color(0xFFFF6B6B)
    val Liver   = Color(0xFFF2A65A)
    val Gut     = Color(0xFF6BCB77)
    val Sleep   = Color(0xFF6B8AFF)
    val Lungs   = Color(0xFF4FD1C5)
    val Kidney  = Color(0xFFB97AE0)
    val Brain   = Color(0xFFF178B6)

    // Next step card (dark-mode green-tinted)
    val NextStepBg     = Color(0xFF13241C)
    val NextStepBorder = Color(0xFF1F4A2E)
    val NextStepLabel  = Color(0xFF8FD49A)
    val NextStepText   = Color(0xFFCDE6D3)

    // Sentiment labels
    val SentimentWeek  = Color(0xFFFF8A75)
    val SentimentMonth = Color(0xFFF2A65A)

    // Chat
    val UserBubble     = Color(0xFFECEAE4)   // user message bg (light pill on dark)
    val UserBubbleText = Color(0xFF14171C)
    val AiBubble       = SurfaceElev
    val AiBubbleText   = TextPrimary
}
