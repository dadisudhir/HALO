package com.health.secondbrain.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.secondbrain.model.OrganNode
import com.health.secondbrain.model.OrganRegistry
import com.health.secondbrain.ui.components.OrganAssetIcon
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type
import kotlin.math.roundToInt

private data class BubblePos(val xFrac: Float, val yFrac: Float, val rDp: Float)

/* Layout from the spec, viewbox 320x444 → fractions. */
private val DECORATIONS = listOf(
    BubblePos(70/320f, 70/444f, 15f),
    BubblePos(250/320f, 64/444f, 13f),
    BubblePos(285/320f, 150/444f, 16f),
    BubblePos(40/320f, 158/444f, 13f),
    BubblePos(278/320f, 300/444f, 14f),
    BubblePos(48/320f, 318/444f, 16f),
    BubblePos(120/320f, 392/444f, 13f),
    BubblePos(222/320f, 386/444f, 15f),
    BubblePos(300/320f, 232/444f, 11f),
    BubblePos(28/320f, 244/444f, 11f),
)

private val ORGAN_POS = mapOf(
    "heart"  to BubblePos(158/320f, 196/444f, 46f),
    "liver"  to BubblePos(232/320f, 138/444f, 34f),
    "gut"    to BubblePos(240/320f, 226/444f, 28f),
    "sleep"  to BubblePos(92/320f, 150/444f, 27f),
    "lungs"  to BubblePos(92/320f, 244/444f, 26f),
    "kidney" to BubblePos(166/320f, 288/444f, 25f),
    "brain"  to BubblePos(232/320f, 300/444f, 20f),
)

@Composable
fun HomeScreen(onOrganTap: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.BgBase).statusBarsPadding()
    ) {
        // greeting
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("Hey, Pranav", style = Type.titleHero, color = Palette.TextPrimary)
            Text(
                "${OrganRegistry.all.count { !it.statusGood }} areas need a look · pinch to scan",
                style = Type.body, color = Palette.TextSecondary
            )
        }

        BubbleCluster(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onOrganTap = onOrganTap
        )

        BottomNav()
    }
}

@Composable
private fun BubbleCluster(modifier: Modifier, onOrganTap: (String) -> Unit) {
    val transition = rememberInfiniteTransition(label = "scan")
    val rotation by transition.animateFloat(
        0f, 360f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "ring"
    )
    val density = LocalDensity.current

    Box(
        modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.BgRadialMid, Palette.BgBase),
                    radius = 1100f
                )
            )
    ) {
        // Decoration & organ bubbles via a single Box layout positioning each child.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight

            // decorations
            DECORATIONS.forEach { p ->
                val dx = w * p.xFrac - p.rDp.dp
                val dy = h * p.yFrac - p.rDp.dp
                Box(
                    Modifier
                        .offset(x = dx, y = dy)
                        .size((p.rDp * 2).dp)
                        .background(Palette.BubbleUnfilledFill, shape = androidx.compose.foundation.shape.CircleShape)
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Palette.BubbleUnfilledStroke,
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }

            // organs
            OrganRegistry.all.forEach { organ ->
                val pos = ORGAN_POS[organ.id] ?: return@forEach
                val r = pos.rDp.dp
                val dx = w * pos.xFrac - r
                val dy = h * pos.yFrac - r

                Box(
                    Modifier
                        .offset(x = dx, y = dy)
                        .size(r * 2),
                    contentAlignment = Alignment.Center
                ) {
                    // scanning ring on most-urgent organ
                    if (organ.id == "heart") {
                        Canvas(
                            Modifier
                                .size(r * 2 + 18.dp)
                                .rotate(rotation)
                        ) {
                            drawCircle(
                                color = Palette.Heart.copy(alpha = 0.6f),
                                style = Stroke(
                                    width = 3f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
                                )
                            )
                        }
                    }

                    Box(
                        Modifier
                            .size(r * 2)
                            .background(organ.accent, shape = androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onOrganTap(organ.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        OrganAssetIcon(
                            organId = organ.id,
                            contentDescription = organ.displayName,
                            modifier = Modifier
                                .size(r * 1.2f)
                                .padding(8.dp)
                        )
                    }
                }

                if (organ.id == "heart") {
                    // label below heart bubble
                    val labelY = h * pos.yFrac + r + 6.dp
                    Box(
                        Modifier
                            .offset(x = w * pos.xFrac - 28.dp, y = labelY)
                            .width(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Heart", style = Type.tinyBold, color = Palette.TextPrimary)
                    }
                }
            }

            // footer hint
            Text(
                "tap a bubble to scan in",
                style = Type.caption,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun BottomNav() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.BgBase)
            .padding(top = 12.dp, bottom = 18.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem(Icons.Outlined.GridView, "Body", active = true)
        NavItem(Icons.Outlined.ShowChart, "Trends")
        NavItem(Icons.Outlined.Add, "Log")
        NavItem(Icons.Outlined.Menu, "More")
    }
}

@Composable
private fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false) {
    val tint = if (active) Palette.TextPrimary else Palette.TextMuted
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = Type.caption, color = tint)
    }
}
