package com.health.secondbrain.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Light-mode palette inspired by Headspace.
 * Warm, welcoming, friendly colors with white background.
 */
object Palette {
    // Backgrounds (Light Theme)
    val BgBase        = Color(0xFFFFFFFF)   // app background (white)
    val BgRadialMid   = Color(0xFFFFFBF7)   // soft cream
    val Surface       = Color(0xFFFFF5F0)   // soft peach cards
    val SurfaceElev   = Color(0xFFFFFFFF)   // white for elevated elements
    val Border        = Color(0xFFFFE8DC)   // soft peach border
    val BorderSubtle  = Color(0xFFFFF5F0)   // very subtle

    // Text (Light Theme)
    val TextPrimary   = Color(0xFF2C2C2C)   // dark charcoal
    val TextSecondary = Color(0xFF666666)   // medium gray
    val TextMuted     = Color(0xFF999999)   // light gray

    // Bubble decoration
    val BubbleUnfilledFill   = Color(0xFFFFFFFF)
    val BubbleUnfilledStroke = Color(0xFFFFE8DC)

    // Status / semantic (Warm Theme)
    val Healthy   = Color(0xFF6BCB77)       // warm green
    val Warning   = Color(0xFFFFB84D)       // warm orange
    val Urgent    = Color(0xFFFF6B6B)       // coral red

    // Organ accents (Light-mode tuned)
    val Heart   = Color(0xFFFF8A5B)         // warm coral (Headspace-inspired)
    val Liver   = Color(0xFFFFB84D)         // warm orange
    val Gut     = Color(0xFF6BCB77)         // warm green
    val Sleep   = Color(0xFF7BA3FF)         // soft blue
    val Lungs   = Color(0xFF4FD1C5)         // teal
    val Kidney  = Color(0xFFD9A8E6)         // soft purple
    val Brain   = Color(0xFFF178B6)         // coral pink

    // Next step card
    val NextStepBg     = Color(0xFFFFF5F0)
    val NextStepBorder = Color(0xFFFFE8DC)
    val NextStepLabel  = Color(0xFF2C2C2C)
    val NextStepText   = Color(0xFF666666)

    // Sentiment labels
    val SentimentWeek  = Color(0xFFFF8A5B)
    val SentimentMonth = Color(0xFFFFB84D)

    // Chat
    val UserBubble     = Color(0xFFFF8A5B)   // coral pill
    val UserBubbleText = Color(0xFFFFFFFF)   // white text
    val AiBubble       = Color(0xFFFFF5F0)   // peach
    val AiBubbleText   = TextPrimary
}
