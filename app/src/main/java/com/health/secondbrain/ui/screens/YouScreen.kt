package com.health.secondbrain.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.health.secondbrain.data.ClinicalObservationSummary
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.data.RiskPrediction
import kotlin.math.roundToInt

private val YouBg = Color(0xFFFFFBF7)
private val YouWhite = Color(0xFFFFFFFF)
private val YouCard = Color(0xFFFFF5F0)
private val YouPrimary = Color(0xFFFF8A5B)
private val YouPrimaryDeep = Color(0xFFE96F43)
private val YouText = Color(0xFF2C2C2C)
private val YouMuted = Color(0xFF7B6F6A)
private val YouBorder = Color(0xFFFFDFD1)
private val YouGreen = Color(0xFF6BCB77)
private val YouOrange = Color(0xFFFFB84D)
private val YouRed = Color(0xFFFF6B6B)

private data class SummaryMetric(
    val label: String,
    val value: String,
    val tone: Color = YouPrimary,
)

private data class VitalMetric(
    val label: String,
    val value: String,
    val note: String,
    val tone: Color,
)

@Composable
fun YouScreen(
    state: YouScreenUiState,
    onBack: () -> Unit,
    onModeChange: (HealthBackendMode) -> Unit,
) {
    val dashboard = state.dashboard
    val context = state.agentContext
    val observations = context?.clinicalObservations.orEmpty()
    val summaryMetrics = remember(state) { summaryMetrics(state) }
    val vitals = remember(state) { vitalMetrics(state) }
    val alerts = remember(state) { alertRows(state) }
    val warnings = remember(state) { warningRows(state) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YouBg)
            .systemBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                TopBar(onBack = onBack)
            }
            item {
                HeaderCard(
                    state = state,
                    onModeChange = onModeChange,
                )
            }
            item {
                HealthSummary(summaryMetrics)
            }
            item {
                VitalsSection(vitals)
            }
            item {
                AlertsSection(
                    alerts = alerts,
                    backendStatus = dashboard.backendStatus,
                    observations = observations,
                )
            }
            item {
                WarningsSection(warnings)
            }
            item {
                DataSourceSection(state)
            }
            item {
                Spacer(Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(YouWhite)
                .border(1.dp, YouBorder, CircleShape)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text("‹", color = YouText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Text("You", color = YouText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun HeaderCard(
    state: YouScreenUiState,
    onModeChange: (HealthBackendMode) -> Unit,
) {
    val profile = state.profile
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp), clip = false)
            .background(
                Brush.linearGradient(listOf(YouPrimary, YouPrimaryDeep)),
                RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(YouWhite.copy(alpha = 0.22f))
                        .border(1.dp, YouWhite.copy(alpha = 0.42f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        profile.avatarInitials.take(3),
                        color = YouWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Text(
                        profile.displayName,
                        color = YouWhite,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.memberLabel,
                        color = YouWhite.copy(alpha = 0.82f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (state.isLoading) "Refreshing health data" else profile.lastUpdatedText,
                        color = YouWhite.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusBadge(profile.statusLabel)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModePill("Fake", state.dashboard.mode == HealthBackendMode.Fake) {
                    onModeChange(HealthBackendMode.Fake)
                }
                ModePill("Real", state.dashboard.mode == HealthBackendMode.Real) {
                    onModeChange(HealthBackendMode.Real)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String) {
    Box(
        modifier = Modifier
            .background(YouGreen.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = YouWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) YouWhite else YouWhite.copy(alpha = 0.18f))
            .clickable { onTap() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) YouPrimaryDeep else YouWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HealthSummary(metrics: List<SummaryMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Health Summary")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            metrics.forEach { metric ->
                MetricCard(metric, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(metric: SummaryMetric, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp), clip = false)
            .background(YouCard, RoundedCornerShape(16.dp))
            .border(1.dp, YouBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(metric.value, color = metric.tone, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            metric.label,
            color = YouMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VitalsSection(vitals: List<VitalMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Vitals")
        Column(
            modifier = Modifier
                .shadow(2.dp, RoundedCornerShape(16.dp), clip = false)
                .background(YouWhite, RoundedCornerShape(16.dp))
                .border(1.dp, YouBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            vitals.forEach { VitalRow(it) }
        }
    }
}

@Composable
private fun VitalRow(metric: VitalMetric) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(metric.tone)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(metric.label, color = YouText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(metric.note, color = YouMuted, fontSize = 12.sp)
        }
        Text(
            metric.value,
            color = YouText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun AlertsSection(
    alerts: List<String>,
    backendStatus: String,
    observations: List<ClinicalObservationSummary>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("🔔 Alerts", color = YouOrange)
        Column(
            modifier = Modifier
                .shadow(2.dp, RoundedCornerShape(16.dp), clip = false)
                .background(YouWhite, RoundedCornerShape(16.dp))
                .border(1.dp, YouBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            alerts.forEach { AlertItem(text = it, tone = YouOrange) }
            if (observations.isNotEmpty()) {
                observations.take(2).forEach { observation ->
                    AlertItem(
                        text = "${observation.display}: ${observation.renderValue()}",
                        tone = YouPrimary
                    )
                }
            }
            AlertItem(text = backendStatus, tone = YouGreen)
        }
    }
}

@Composable
private fun AlertItem(text: String, tone: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone)
        )
        Text(
            text,
            color = YouText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WarningsSection(warnings: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("⚠️ Warnings", color = YouRed)
        Column(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(20.dp), clip = false)
                .background(YouCard, RoundedCornerShape(20.dp))
                .border(1.dp, YouPrimary.copy(alpha = 0.34f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            warnings.forEach { WarningItem(it) }
        }
    }
}

@Composable
private fun WarningItem(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(YouRed)
        )
        Text(
            text,
            color = YouText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun DataSourceSection(state: YouScreenUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(YouWhite, RoundedCornerShape(16.dp))
            .border(1.dp, YouBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        SectionTitle("Data")
        Spacer(Modifier.height(8.dp))
        Text(
            state.preferences.dataSharingLabel,
            color = YouText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Units: ${state.preferences.preferredUnits}  •  Reminders: ${if (state.preferences.remindersEnabled) "on" else "off"}",
            color = YouMuted,
            fontSize = 13.sp
        )
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = YouRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color = YouText) {
    Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

private fun summaryMetrics(state: YouScreenUiState): List<SummaryMetric> {
    val observations = state.agentContext?.clinicalObservations.orEmpty()
    val visits = observations
        .mapNotNull { it.observedAt?.take(10) }
        .distinct()
        .size
        .takeIf { it > 0 }
        ?: observations.size
    val activeRx = observations.count { it.display.containsMedicationSignal() }
    val pendingLabs = observations.count { it.display.containsLabSignal() }
    return listOf(
        SummaryMetric("Visits", visits.toString(), YouPrimary),
        SummaryMetric("Active Rx", activeRx.toString(), if (activeRx > 0) YouOrange else YouGreen),
        SummaryMetric("Pending Labs", pendingLabs.toString(), if (pendingLabs > 0) YouRed else YouGreen),
    )
}

private fun vitalMetrics(state: YouScreenUiState): List<VitalMetric> {
    val latest = state.agentContext?.recordedDailySummaries?.lastOrNull()
    val heart = state.dashboard.organs.firstOrNull { it.id == "heart" }
    val systolic = state.agentContext?.clinicalObservations.orEmpty()
        .firstOrNull { it.display.contains("systolic", ignoreCase = true) }
    val diastolic = state.agentContext?.clinicalObservations.orEmpty()
        .firstOrNull { it.display.contains("diastolic", ignoreCase = true) }

    val bp = when {
        systolic?.valueNumber != null && diastolic?.valueNumber != null ->
            "${systolic.valueNumber.roundToInt()}/${diastolic.valueNumber.roundToInt()}"
        systolic?.valueNumber != null -> "${systolic.valueNumber.roundToInt()}/--"
        else -> "No recent BP"
    }
    val hr = latest?.averageHeartRate?.roundToInt()?.let { "$it bpm" }
        ?: heart?.metrics?.firstOrNull()?.let { "${it.value} bpm" }
        ?: "No HR yet"
    val hrv = latest?.hrvRmssd?.roundToInt()?.let { "$it ms" }
        ?: heart?.metrics?.firstOrNull { it.label.contains("HRV", ignoreCase = true) }?.value
        ?: "No HRV yet"

    return listOf(
        VitalMetric("BP Trend", bp, "Clinical observations", if (bp.startsWith("No")) YouOrange else YouGreen),
        VitalMetric("Heart Rate", hr, "Daily health summary", YouPrimary),
        VitalMetric("HRV", hrv, "Recovery signal", YouGreen),
    )
}

private fun alertRows(state: YouScreenUiState): List<String> {
    val rows = mutableListOf<String>()
    val overview = state.dashboard.overviewLine.takeIf { it.isNotBlank() && !it.contains("Loading", true) }
    if (overview != null) rows += overview
    state.dashboard.organs
        .filter { !it.statusGood }
        .take(2)
        .forEach { rows += "${it.displayName}: ${it.previewSummary}" }
    if (rows.isEmpty()) rows += "No urgent alerts from current dashboard data"
    return rows
}

private fun warningRows(state: YouScreenUiState): List<String> {
    val rows = mutableListOf<String>()
    riskWarning(state.agentContext?.recordedRisk)?.let { rows += it }
    state.dashboard.organs
        .filter { !it.statusGood }
        .take(3)
        .forEach { rows += "${it.displayName} needs attention" }
    if (rows.isEmpty()) rows += "No critical warnings detected"
    return rows
}

private fun riskWarning(risk: RiskPrediction?): String? {
    if (risk == null) return null
    val maxScore = maxOf(risk.arrhythmiaScore, risk.cardiacDecompScore, risk.sleepImpairmentScore)
    if (maxScore < 0.35) return null
    val label = when (maxScore) {
        risk.arrhythmiaScore -> "Arrhythmia"
        risk.cardiacDecompScore -> "Cardiac load"
        else -> "Sleep impairment"
    }
    return "$label risk ${(maxScore * 100).roundToInt()}%"
}

private fun ClinicalObservationSummary.renderValue(): String {
    valueText?.takeIf { it.isNotBlank() }?.let { return it }
    valueNumber?.let { number ->
        val rounded = if (number % 1.0 == 0.0) number.roundToInt().toString() else "%.1f".format(number)
        return listOfNotNull(rounded, unit).joinToString(" ")
    }
    return "recorded"
}

private fun String.containsLabSignal(): Boolean {
    val normalized = lowercase()
    return listOf("lab", "glucose", "cholesterol", "creatinine", "blood", "panel").any {
        normalized.contains(it)
    }
}

private fun String.containsMedicationSignal(): Boolean {
    val normalized = lowercase()
    return listOf("rx", "medication", "medicine", "drug", "prescription", "metformin").any {
        normalized.contains(it)
    }
}
