package com.health.secondbrain.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.secondbrain.model.OrganRegistry
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type

@Composable
fun ScanFocusScreen(
    organId: String,
    onClose: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val organ = OrganRegistry.byId(organId)

    val transition = rememberInfiniteTransition(label = "scan-focus")
    val r1 by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Restart),
        label = "r1"
    )
    val r2 by transition.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(30_000, easing = LinearEasing), RepeatMode.Restart),
        label = "r2"
    )

    var cardVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cardVisible = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.BgBase)
            .statusBarsPadding()
    ) {
        // Top close
        Text(
            "✕ close",
            style = Type.bodyBold,
            color = Palette.TextSecondary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .clickable { onClose() }
        )

        // Center scanning element
        Box(Modifier.align(Alignment.Center)) {
            // dimmed orbiting dots — purely decorative
            Box(Modifier.size(280.dp).alpha(0.22f), contentAlignment = Alignment.Center) {
                OrganRegistry.all.filter { it.id != organId }.forEachIndexed { i, o ->
                    val angle = (360f / 6f) * i
                    val rotated = androidx.compose.ui.unit.IntOffset.Zero
                    Box(
                        Modifier
                            .offset(
                                x = (kotlin.math.cos(Math.toRadians(angle.toDouble())) * 110).dp,
                                y = (kotlin.math.sin(Math.toRadians(angle.toDouble())) * 110).dp,
                            )
                            .size(28.dp)
                            .background(o.accent, CircleShape)
                    )
                }
            }

            // ring 2
            Canvas(Modifier.size(200.dp).rotate(r2)) {
                drawCircle(
                    color = organ.accent.copy(alpha = 0.4f),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 12f), 0f)
                    )
                )
            }
            // ring 1
            Canvas(Modifier.size(160.dp).rotate(r1)) {
                drawCircle(
                    color = organ.accent.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 14f), 0f)
                    )
                )
            }
            // organ disc
            Box(
                Modifier
                    .size(118.dp)
                    .background(organ.tint, CircleShape)
                    .border(2.dp, organ.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = organIconChar(organ.id),
                    color = organ.accent,
                    fontSize = 46.sp
                )
            }
        }

        // Rising preview card
        AnimatedVisibility(
            visible = cardVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .padding(14.dp)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .background(Palette.Surface, RoundedCornerShape(22.dp))
                    .border(1.dp, Palette.Border, RoundedCornerShape(22.dp))
                    .padding(18.dp)
            ) {
                Text(
                    organ.systemLabel.substringBefore(" "),
                    style = Type.titleScreen,
                    color = Palette.TextPrimary
                )
                Spacer(Modifier.height(2.dp))
                val statusColor = if (organ.statusGood) Palette.Healthy else Palette.Urgent
                val statusText = if (organ.statusGood) "✓ Looking good" else "⚠ Needs attention"
                Text(statusText, style = Type.bodyBold, color = statusColor)

                Spacer(Modifier.height(8.dp))
                Text(
                    organ.previewSummary,
                    style = Type.body,
                    color = Palette.TextSecondary
                )

                Spacer(Modifier.height(14.dp))
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    Button(
                        onClick = onOpenDetails,
                        shape = RoundedCornerShape(30.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = organ.accent,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                    ) {
                        Text("Open details ↓", style = Type.bodyBold)
                    }
                }
            }
        }
    }
}

private fun organIconChar(id: String): String = when (id) {
    "heart"  -> "❤"
    "liver"  -> "◆"
    "gut"    -> "◉"
    "sleep"  -> "☾"
    "lungs"  -> "▲"
    "kidney" -> "◈"
    "brain"  -> "✷"
    else -> "●"
}
