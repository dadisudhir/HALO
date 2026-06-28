package com.health.secondbrain.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.secondbrain.model.DeltaDirection
import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import com.health.secondbrain.ui.components.LineSpark
import com.health.secondbrain.ui.components.OrganAssetIcon
import com.health.secondbrain.ui.components.WeekBars
import com.health.secondbrain.ui.theme.Palette
import com.health.secondbrain.ui.theme.Type

@Composable
fun OrganDetailScreen(
    organId: String,
    organ: OrganNode? = null,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val organ = organ ?: return MissingComponent(organId = organId, onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.BgBase)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ Body",
                style = Type.bodyBold,
                color = Palette.TextPrimary,
                modifier = Modifier.clickable { onBack() }
            )
            Spacer(Modifier.weight(1f))
            Text("⌁", style = Type.bodyBold, color = Palette.TextSecondary)
        }

        Hero(organ)

        // Name + system
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)) {
            Text(organ.displayName, style = Type.titleHero, color = Palette.TextPrimary)
            Text(organ.systemLabel, style = Type.bodySmall, color = Palette.TextSecondary)
        }

        MetricGrid(organ.metrics)

        ChartCard(title = "${organ.displayName} recovery", subtitle = "7 days") {
            LineSpark(
                values = organ.chart7Day,
                color = organ.accent,
                modifier = Modifier.fillMaxWidth().height(78.dp)
            )
        }

        ChartCard(title = "This week's active zones", subtitle = "M T W T F S S") {
            WeekBars(
                values = organ.activeZones,
                color = organ.accent,
                modifier = Modifier.fillMaxWidth().height(62.dp)
            )
        }

        OverviewSection(organ)
        NextStepCard(organ)
        ChatEntryPanel(organ, onOpenChat = onOpenChat)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Hero(organ: OrganNode) {
    Box(
        Modifier
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(170.dp)
            .background(organ.tint, RoundedCornerShape(24.dp))
            .border(1.dp, organ.accent.copy(alpha = 0.35f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrganAssetIcon(
                iconAsset = organ.iconAsset,
                contentDescription = organ.displayName,
                modifier = Modifier
                    .size(96.dp)
                    .padding(6.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                organ.componentType.replace('_', ' '),
                style = Type.caption,
                color = organ.accent.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun MissingComponent(organId: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.BgBase)
            .statusBarsPadding()
            .padding(18.dp)
    ) {
        Text(
            "‹ Body",
            style = Type.bodyBold,
            color = Palette.TextPrimary,
            modifier = Modifier.clickable { onBack() }
        )
        Spacer(Modifier.height(28.dp))
        Text("Component unavailable", style = Type.titleScreen, color = Palette.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "$organId is not enabled in the backend health_components table.",
            style = Type.body,
            color = Palette.TextSecondary
        )
    }
}

@Composable
private fun MetricGrid(metrics: List<Metric>) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        metrics.forEach { m ->
            MetricCard(m, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(metric.label, style = Type.caption, color = Palette.TextMuted)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(metric.value, style = Type.metricValue, color = Palette.TextPrimary)
            Spacer(Modifier.width(6.dp))
            val color = when (metric.direction) {
                DeltaDirection.UpBad, DeltaDirection.DownBad -> Palette.Urgent
                DeltaDirection.UpGood, DeltaDirection.DownGood -> Palette.Healthy
                DeltaDirection.Neutral -> Palette.Warning
            }
            Text(metric.deltaText, color = color, style = Type.bodyBold)
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.bodyBold, color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(subtitle, style = Type.caption, color = Palette.TextMuted)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun OverviewSection(organ: OrganNode) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("Overview", style = Type.titleSection, color = Palette.TextPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SentimentCard("PAST WEEK", Palette.SentimentWeek, organ.sentenceWeek, Modifier.weight(1f))
            SentimentCard("PAST MONTH", Palette.SentimentMonth, organ.sentenceMonth, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SentimentCard(label: String, labelColor: Color, body: String, modifier: Modifier) {
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(label, style = Type.label, color = labelColor)
        Spacer(Modifier.height(5.dp))
        Text(body, style = Type.bodySmall, color = Palette.TextSecondary)
    }
}

@Composable
private fun NextStepCard(organ: OrganNode) {
    Column(
        Modifier
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(Palette.NextStepBg, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.NextStepBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Text("NEXT STEP", style = Type.label, color = Palette.NextStepLabel)
        Spacer(Modifier.height(4.dp))
        Text(organ.sentenceNextStep, style = Type.body, color = Palette.NextStepText)
    }
}

@Composable
private fun ChatEntryPanel(organ: OrganNode, onOpenChat: () -> Unit) {
    Column(
        Modifier
            .padding(14.dp)
            .fillMaxWidth()
            .background(Palette.SurfaceElev, RoundedCornerShape(20.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clickable { onOpenChat() }
    ) {
        Text(
            "Ask about your ${organ.displayName.lowercase()}…",
            style = Type.bodySmall,
            color = Palette.TextSecondary
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            organ.chatChips.forEach { chip ->
                Box(
                    Modifier
                        .background(Palette.Surface, RoundedCornerShape(20.dp))
                        .border(1.dp, Palette.Border, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(chip, style = Type.caption, color = Palette.TextPrimary)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, Palette.Border, RoundedCornerShape(24.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Type a message…",
                style = Type.bodySmall, color = Palette.TextMuted,
                modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(34.dp)
                    .background(organ.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("↑", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
