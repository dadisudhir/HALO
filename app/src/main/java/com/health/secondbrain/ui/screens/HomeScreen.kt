package com.health.secondbrain.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.health.secondbrain.model.DeltaDirection
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import com.health.secondbrain.ui.components.OrganAssetIcon
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/* ----------------------------------------------------------------------------
 * Gravity bubble cluster.
 *
 * Every bubble lives on a hex grid centred on the "YOU" node. Each bubble's
 * rendered scale + opacity is a smooth (gaussian) function of how far its
 * centre sits from the middle of the viewport, so whatever is in the middle
 * swells up large and the outskirts shrink to faint dots — the Apple-Watch
 * honeycomb fisheye. The surface is draggable in 2D; releasing a drag lets a
 * spring pull the nearest bubble back to dead-centre (the "gravity" settle).
 * ------------------------------------------------------------------------- */

private enum class NodeKind { You, Organ, Decoration }

private data class BubbleNode(
    val key: String,
    val kind: NodeKind,
    val baseXDp: Float,        // grid position relative to YOU, in dp
    val baseYDp: Float,
    val baseRadiusDp: Float,
    val organ: OrganNode? = null,
)

// Layout tuning ---------------------------------------------------------------
private const val HEX_SIZE_DP = 82f       // hex spacing
private const val YOU_RADIUS_DP = 70f
private const val ORGAN_RADIUS_DP = 50f
private const val DECO_RADIUS_DP = 16f
private const val RING_COUNT = 4          // how far the decoration field extends

private const val MAX_SCALE = 1.9f
private const val MIN_SCALE = 0.16f
private const val SIGMA_FRACTION = 0.30f  // gaussian width as fraction of min screen dim
private const val FLOAT_AMP_PX = 5f       // breathing amplitude
private const val CENTER_Y_FRACTION = 0.36f  // vertical anchor of the cluster (lower = higher up)

/** Axial coordinates of every cell in a single hex ring (radius 0 = centre). */
private fun hexRing(radius: Int): List<Pair<Int, Int>> {
    if (radius == 0) return listOf(0 to 0)
    // axial directions matching cube dirs
    val dirs = listOf(
        1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1
    )
    val out = ArrayList<Pair<Int, Int>>(6 * radius)
    var q = -radius
    var r = radius
    for (i in 0 until 6) {
        for (j in 0 until radius) {
            out.add(q to r)
            q += dirs[i].first
            r += dirs[i].second
        }
    }
    return out
}

/** pointy-top axial -> pixel (in dp units). */
private fun axialToDp(q: Int, r: Int): Offset {
    val x = HEX_SIZE_DP * (sqrt(3f) * q + sqrt(3f) / 2f * r)
    val y = HEX_SIZE_DP * (1.5f * r)
    return Offset(x, y)
}

/** Build the full node list: YOU at centre, the 7 organs in the cells closest
 *  to the centre (so they cluster tightly around YOU), and every remaining cell
 *  filled with a faint unfilled decoration bubble. */
private fun buildNodes(organs: List<OrganNode>): List<BubbleNode> {
    val cells = ArrayList<Pair<Int, Int>>()
    for (ring in 0..RING_COUNT) cells.addAll(hexRing(ring))

    // Sort cells by distance from the centre so organ slots are always the
    // nearest available cells, regardless of how many organs there are.
    val sorted = cells.sortedBy { (q, r) ->
        val p = axialToDp(q, r)
        p.x * p.x + p.y * p.y
    }

    val nodes = ArrayList<BubbleNode>(sorted.size)

    sorted.forEachIndexed { index, (q, r) ->
        val p = axialToDp(q, r)
        when {
            index == 0 -> nodes.add(
                BubbleNode("you", NodeKind.You, p.x, p.y, YOU_RADIUS_DP)
            )
            index <= organs.size -> {
                val organ = organs[index - 1]
                nodes.add(
                    BubbleNode(organ.id, NodeKind.Organ, p.x, p.y, ORGAN_RADIUS_DP, organ)
                )
            }
            else -> nodes.add(
                BubbleNode("deco_$index", NodeKind.Decoration, p.x, p.y, DECO_RADIUS_DP)
            )
        }
    }
    return nodes
}

private fun fisheyeScale(distPx: Float, sigmaPx: Float): Float {
    val g = exp(-(distPx * distPx) / (2f * sigmaPx * sigmaPx))
    return MIN_SCALE + (MAX_SCALE - MIN_SCALE) * g
}

private fun fisheyeAlpha(distPx: Float, sigmaPx: Float): Float {
    val g = exp(-(distPx * distPx) / (2f * sigmaPx * sigmaPx))
    return (0.16f + 0.84f * g).coerceIn(0f, 1f)
}

@Composable
fun HomeScreen(
    mode: HealthBackendMode = HealthBackendMode.Fake,
    organs: List<OrganNode> = emptyList(),
    overviewStats: List<Metric> = emptyList(),
    overviewNote: String = "",
    overviewLine: String = "Loading backend...",
    backendStatus: String = "backend pending",
    footerPrimary: String = "Waiting for health samples",
    footerSecondary: String = "Waiting for component metrics",
    onModeChange: (HealthBackendMode) -> Unit = {},
    onOrganTap: (String) -> Unit,
    onYouTap: () -> Unit = {},
) {
    val attentionCount = remember(organs) { organs.count { !it.statusGood } }
    Column(
        Modifier.fillMaxSize().background(Palette.BgBase).statusBarsPadding()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hey, Pranav", style = Type.titleHero, color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                ModeChip("Fake", mode == HealthBackendMode.Fake) {
                    onModeChange(HealthBackendMode.Fake)
                }
                Spacer(Modifier.width(8.dp))
                ModeChip("Real", mode == HealthBackendMode.Real) {
                    onModeChange(HealthBackendMode.Real)
                }
            }
            Text(
                "$attentionCount areas need attention",
                style = Type.body, color = Palette.TextSecondary
            )
            Text(
                overviewLine,
                style = Type.caption, color = Palette.TextMuted
            )
            Text(backendStatus, style = Type.caption, color = Palette.TextMuted)
        }

        BubbleCluster(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            organs = organs,
            overviewStats = overviewStats,
            overviewNote = overviewNote,
            onOrganTap = onOrganTap,
            onYouTap = onYouTap
        )

        HomeFooter(footerPrimary = footerPrimary, footerSecondary = footerSecondary)
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .background(
                if (selected) Palette.TextPrimary else Palette.Surface,
                RoundedCornerShape(18.dp)
            )
            .border(1.dp, Palette.Border, RoundedCornerShape(18.dp))
            .clickable { onTap() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = Type.caption,
            color = if (selected) Color.Black else Palette.TextSecondary,
        )
    }
}

@Composable
private fun BubbleCluster(
    modifier: Modifier,
    organs: List<OrganNode>,
    overviewStats: List<Metric>,
    overviewNote: String,
    onOrganTap: (String) -> Unit,
    onYouTap: () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val nodes = remember(organs) { buildNodes(organs) }

    // 2D pan offset, in px, as two springable channels.
    val panX = remember { Animatable(0f) }
    val panY = remember { Animatable(0f) }

    // gentle breathing phase
    val infinite = rememberInfiniteTransition(label = "float")
    val phase by infinite.animateFloat(
        0f, (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val ringSpin by infinite.animateFloat(
        0f, 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )

    Box(
        modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.BgRadialMid, Palette.BgBase),
                    radius = 1100f
                )
            )
            .clip(androidx.compose.ui.graphics.RectangleShape)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val centerX = wPx / 2f
            val centerY = hPx * CENTER_Y_FRACTION
            val sigma = minOf(wPx, hPx) * SIGMA_FRACTION

            val pan = Offset(panX.value, panY.value)

            // Which node is closest to dead-centre right now?
            var focusKey = "you"
            var focusDist = Float.MAX_VALUE
            nodes.forEach { n ->
                val nx = with(density) { n.baseXDp.dp.toPx() }
                val ny = with(density) { n.baseYDp.dp.toPx() }
                val d = hypot(nx + pan.x, ny + pan.y)
                if (n.kind != NodeKind.Decoration && d < focusDist) {
                    focusDist = d
                    focusKey = n.key
                }
            }

            val dragModifier = Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch { panX.snapTo(panX.value + drag.x) }
                        scope.launch { panY.snapTo(panY.value + drag.y) }
                    },
                    onDragEnd = {
                        // Gravity settle: pull the nearest non-decoration bubble to centre.
                        val cur = Offset(panX.value, panY.value)
                        var bestX = 0f
                        var bestY = 0f
                        var best = Float.MAX_VALUE
                        nodes.forEach { n ->
                            if (n.kind == NodeKind.Decoration) return@forEach
                            val nx = with(density) { n.baseXDp.dp.toPx() }
                            val ny = with(density) { n.baseYDp.dp.toPx() }
                            val d = hypot(nx + cur.x, ny + cur.y)
                            if (d < best) {
                                best = d; bestX = -nx; bestY = -ny
                            }
                        }
                        scope.launch {
                            panX.animateTo(bestX, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                        }
                        scope.launch {
                            panY.animateTo(bestY, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                        }
                    }
                )
            }

            Box(Modifier.fillMaxSize().then(dragModifier)) {
                nodes.forEach { node ->
                    val baseX = with(density) { node.baseXDp.dp.toPx() }
                    val baseY = with(density) { node.baseYDp.dp.toPx() }

                    // breathing wobble (skipped for YOU so the centre stays stable)
                    val seed = node.key.hashCode() % 360
                    val wob = if (node.kind == NodeKind.You) 0f else FLOAT_AMP_PX
                    val fx = sin(phase + seed) * wob
                    val fy = cos(phase * 0.9f + seed) * wob

                    val cx = centerX + baseX + pan.x + fx
                    val cy = centerY + baseY + pan.y + fy

                    val dist = hypot(cx - centerX, cy - centerY)
                    val scale = fisheyeScale(dist, sigma)
                    val alpha = fisheyeAlpha(dist, sigma)

                    // cull bubbles that have shrunk to nothing or wandered off-screen
                    val rPx = with(density) { node.baseRadiusDp.dp.toPx() } * scale
                    if (alpha < 0.04f) return@forEach
                    if (cx < -rPx || cy < -rPx || cx > wPx + rPx || cy > hPx + rPx) return@forEach

                    val isFocused = node.key == focusKey
                    val focusBoost = if (isFocused && node.kind == NodeKind.Organ) 1.12f else 1f

                    val topLeftX = (cx - with(density) { node.baseRadiusDp.dp.toPx() }).roundToInt()
                    val topLeftY = (cy - with(density) { node.baseRadiusDp.dp.toPx() }).roundToInt()

                    Box(
                        Modifier
                            .offset { IntOffset(topLeftX, topLeftY) }
                            .size(node.baseRadiusDp.dp * 2)
                            .graphicsLayer {
                                scaleX = scale * focusBoost
                                scaleY = scale * focusBoost
                                this.alpha = alpha
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        when (node.kind) {
                            NodeKind.You -> YouBubble(
                                focused = isFocused,
                                onTap = {
                                    if (isFocused) {
                                        onYouTap()
                                    } else {
                                        scope.launch {
                                            panX.animateTo(-baseX, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
                                        }
                                        scope.launch {
                                            panY.animateTo(-baseY, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
                                        }
                                    }
                                }
                            )
                            NodeKind.Decoration -> DecorationBubble()
                            NodeKind.Organ -> OrganBubble(
                                organ = node.organ!!,
                                focused = isFocused,
                                spin = ringSpin,
                                onTap = {
                                    if (isFocused) {
                                        onOrganTap(node.organ.id)
                                    } else {
                                        scope.launch {
                                            panX.animateTo(-baseX, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
                                        }
                                        scope.launch {
                                            panY.animateTo(-baseY, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                // Bottom overview card — fills the lower half of the cluster.
                // Personal stats when YOU is centred, otherwise the focused
                // organ's metrics + summary + next step.
                val focusedNode = nodes.firstOrNull { it.key == focusKey }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.42f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (focusedNode?.kind == NodeKind.You) {
                        OverviewCard(
                            title = "Your overview",
                            accent = Palette.TextPrimary,
                            stats = overviewStats.map { it.toOverviewStat() },
                            note = overviewNote.ifBlank { null }
                        )
                    } else {
                        focusedNode?.organ?.let { organ ->
                            OverviewCard(
                                title = organ.displayName,
                                accent = organ.accent,
                                stats = organ.metrics.map { it.toOverviewStat() },
                                note = organ.sentenceNextStep
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YouBubble(focused: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Surface, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = if (focused) Palette.TextPrimary.copy(alpha = 0.5f) else Palette.Border,
                style = Stroke(width = if (focused) 3f else 2f)
            )
        }
        Text("YOU", style = Type.label, color = Palette.TextPrimary)
    }
}

private data class OverviewStat(
    val label: String,
    val value: String,
    val delta: String? = null,
    val color: Color,
)

private fun Metric.toOverviewStat(): OverviewStat {
    val color = when (direction) {
        DeltaDirection.UpGood, DeltaDirection.DownGood -> Palette.Healthy
        DeltaDirection.UpBad, DeltaDirection.DownBad -> Palette.Urgent
        DeltaDirection.Neutral -> Palette.TextSecondary
    }
    return OverviewStat(label, value, deltaText, color)
}

@Composable
private fun OverviewCard(
    title: String,
    accent: Color,
    stats: List<OverviewStat>,
    note: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Palette.Surface, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(accent, CircleShape)
            )
            Text(title, style = Type.bodyBold, color = Palette.TextPrimary)
        }
        Spacer(Modifier.height(16.dp))

        // Stat table — two columns, populated top to bottom.
        val rows = stats.chunked(2)
        rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { stat ->
                    Column(
                        Modifier.weight(1f).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(stat.value, style = Type.titleSection, color = stat.color)
                            stat.delta?.let {
                                Text(
                                    "  $it",
                                    style = Type.caption,
                                    color = stat.color.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Text(stat.label, style = Type.caption, color = Palette.TextSecondary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            if (i < rows.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Palette.Border.copy(alpha = 0.4f))
                )
            }
        }

        if (note != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                note,
                style = Type.bodySmall,
                color = Palette.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DecorationBubble() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.BubbleUnfilledFill, CircleShape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = Palette.BubbleUnfilledStroke, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun OrganBubble(
    organ: OrganNode,
    focused: Boolean,
    spin: Float,
    onTap: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        // animated scan ring on the focused, needs-attention organ
        if (focused && !organ.statusGood) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = spin
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
            ) {
                drawCircle(
                    color = organ.accent.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
                    )
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(organ.accent, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onTap() },
            contentAlignment = Alignment.Center
        ) {
            OrganAssetIcon(
                iconAsset = organ.iconAsset,
                contentDescription = organ.displayName,
                modifier = Modifier.fillMaxSize().padding(6.dp)
            )
        }
    }
}

@Composable
private fun HomeFooter(footerPrimary: String, footerSecondary: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.BgBase)
            .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 16.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Tap a bubble to open details", style = Type.bodySmall, color = Palette.TextPrimary)
        if (footerPrimary.isNotBlank()) {
            Text(footerPrimary, style = Type.caption, color = Palette.TextSecondary)
        }
        if (footerSecondary.isNotBlank()) {
            Text(footerSecondary, style = Type.caption, color = Palette.TextMuted)
        }
    }
}
