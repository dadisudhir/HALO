package com.health.secondbrain.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.health.secondbrain.ui.theme.Palette
import kotlin.math.max
import kotlin.math.min

/** Simple sparkline. Stroke color = organ accent. */
@Composable
fun LineSpark(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val lo = values.min()
        val hi = max(values.max(), lo + 1f)
        val stepX = if (values.size > 1) w / (values.size - 1) else w

        // baseline
        drawLine(
            Palette.Border,
            Offset(0f, h - 1f),
            Offset(w, h - 1f),
            strokeWidth = 2f
        )

        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h - ((v - lo) / (hi - lo)) * (h * 0.85f) - (h * 0.075f)
            val p = Offset(x, y)
            prev?.let { drawLine(color, it, p, strokeWidth = 6f) }
            drawCircle(color, radius = 5f, center = p)
            prev = p
        }
    }
}

/** 7-bar weekly bar chart (M..S). Highlights the max bar in [color]. */
@Composable
fun WeekBars(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    if (values.isEmpty()) return
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = 14f
        val barW = (w - gap * (values.size - 1)) / values.size
        val hi = max(values.max(), 1f)
        val highlightIdx = values.indexOf(hi)

        values.forEachIndexed { i, v ->
            val bh = (v / hi) * h * 0.92f
            val left = i * (barW + gap)
            val top = h - bh
            val fill = if (i == highlightIdx) color else color.copy(alpha = 0.25f)
            drawRoundRectCompat(fill, Offset(left, top), Size(barW, bh))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectCompat(
    color: Color, topLeft: Offset, size: Size
) {
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
    )
}

/** Dashed scanning ring overlay for the scan-focus screen. */
@Composable
fun DashedRing(
    radius: Float,
    color: Color,
    strokeWidthPx: Float = 3f,
    alpha: Float = 0.6f,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = center,
            style = Stroke(
                width = strokeWidthPx,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 18f), 0f)
            )
        )
    }
}

internal fun safeRange(lo: Float, hi: Float): Float = min(hi, max(lo, 0.001f))
