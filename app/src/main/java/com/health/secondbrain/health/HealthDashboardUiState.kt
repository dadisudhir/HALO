package com.health.secondbrain.health

import androidx.compose.ui.graphics.Color
import com.health.secondbrain.data.DailyHealthSummary
import com.health.secondbrain.data.EcgSessionSummary
import com.health.secondbrain.data.FhirImportResult
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.data.HealthComponentDefinition
import com.health.secondbrain.data.RiskPrediction
import com.health.secondbrain.data.WatchSnapshotSummary
import com.health.secondbrain.features.HealthInterpolator
import com.health.secondbrain.features.HealthRiskEngine
import com.health.secondbrain.model.DeltaDirection
import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import kotlin.math.roundToInt

data class HealthDashboardUiState(
    val mode: HealthBackendMode,
    val organs: List<OrganNode>,
    val overviewStats: List<Metric>,
    val overviewNote: String,
    val overviewLine: String,
    val footerPrimary: String,
    val footerSecondary: String,
    val backendStatus: String,
) {
    fun organById(id: String): OrganNode? = organs.firstOrNull { it.id == id }

    companion object {
        val Loading = HealthDashboardUiState(
            mode = HealthBackendMode.Fake,
            organs = emptyList(),
            overviewStats = emptyList(),
            overviewNote = "Waiting for backend components.",
            overviewLine = "Loading local backend...",
            footerPrimary = "Preparing SQLite health store",
            footerSecondary = "Fake path works offline; real path imports FHIR",
            backendStatus = "loading",
        )
    }
}

object HealthDashboardMapper {

    fun from(
        components: List<HealthComponentDefinition>,
        summaries: List<DailyHealthSummary>,
        prediction: RiskPrediction,
        mode: HealthBackendMode,
        fhirImport: FhirImportResult?,
        watchSnapshot: WatchSnapshotSummary? = null,
        ecgSummary: EcgSessionSummary? = null,
    ): HealthDashboardUiState {
        if (summaries.isEmpty()) return HealthDashboardUiState.Loading.copy(backendStatus = "empty summaries")
        if (components.isEmpty()) return HealthDashboardUiState.Loading.copy(backendStatus = "empty components")

        val baseline = summaries.dropLast(7).ifEmpty { summaries }
        val latest = summaries.last()
        val recordedSummary = computeRecordedSummary(summaries)

        val baselineResting = baseline.map { it.restingBpm }.average()
        val deltaResting = latest.restingBpm - baselineResting
        val baselineHrv = baseline.map { it.hrvRmssd }.average()
        val deltaHrv = latest.hrvRmssd - baselineHrv
        val baselineSleep = baseline.map { it.sleepHours }.average()
        val deltaSleep = latest.sleepHours - baselineSleep
        val baselineSteps = baseline.map { it.dailySteps.toDouble() }.average()
        val deltaSteps = latest.dailySteps - baselineSteps

        val organs = components
            .filter { it.enabled && it.iconAsset.isNotBlank() }
            .map { component ->
                when (component.id) {
                    "heart" -> heartOrgan(component, summaries, prediction, latest, deltaResting, deltaHrv, watchSnapshot, ecgSummary)
                    "sleep" -> sleepOrgan(component, summaries, prediction, latest, deltaSleep)
                    "brain" -> brainOrgan(component, summaries, latest, deltaHrv)
                    "liver" -> liverOrgan(component, summaries, latest, fhirImport)
                    "kidney" -> kidneyOrgan(component, summaries, latest, fhirImport)
                    "lungs" -> lungsOrgan(component, summaries, latest, deltaSteps)
                    else -> genericOrgan(component, summaries, latest)
                }
            }

        val importLine = fhirImport?.let {
            " - FHIR P:${it.patientCount} O:${it.observationCount} C:${it.conditionCount} ${it.status.take(42)}"
        }.orEmpty()

        return HealthDashboardUiState(
            mode = mode,
            organs = organs,
            overviewStats = recordedSummary?.overviewStats.orEmpty(),
            overviewNote = recordedSummary?.overviewNote
                ?: "No recorded watch or clinical daily summaries yet. Demo-seeded rows are kept out of the personal summary.",
            overviewLine = recordedSummary?.overviewLine ?: "No recorded biometrics yet",
            footerPrimary = recordedSummary?.footerPrimary.orEmpty(),
            footerSecondary = recordedSummary?.footerSecondary.orEmpty(),
            backendStatus = "${mode.name.lowercase()} path - SQLite ${summaries.size} daily rows - ${organs.size} components$importLine",
        )
    }

    private data class RecordedSummaryBlock(
        val overviewStats: List<Metric>,
        val overviewNote: String,
        val overviewLine: String,
        val footerPrimary: String,
        val footerSecondary: String,
    )

    private fun computeRecordedSummary(summaries: List<DailyHealthSummary>): RecordedSummaryBlock? {
        val recorded = summaries.filter { it.isRecordedSource() }
        if (recorded.isEmpty()) return null

        val latest = recorded.last()
        val baseline = recorded.dropLast(7).ifEmpty { recorded.dropLast(1) }
        val deltaResting = baseline.deltaOrNull(latest.restingBpm) { it.restingBpm }
        val deltaHrv = baseline.deltaOrNull(latest.hrvRmssd) { it.hrvRmssd }
        val deltaSleep = baseline.deltaOrNull(latest.sleepHours) { it.sleepHours }
        val deltaSteps = baseline.deltaOrNull(latest.dailySteps.toDouble()) { it.dailySteps.toDouble() }

        val recordedPrediction = HealthRiskEngine.score(recorded)
        val recovery = (100 - recordedPrediction.cardiacDecompScore * 38 - recordedPrediction.sleepImpairmentScore * 28)
            .roundToInt()
            .coerceIn(1, 99)
        val strain = (45 + recordedPrediction.cardiacDecompScore * 55).roundToInt().coerceIn(1, 99)
        val readiness = (100 - recordedPrediction.arrhythmiaScore * 26 - recordedPrediction.sleepImpairmentScore * 36)
            .roundToInt()
            .coerceIn(1, 99)

        val rhrDelta = deltaResting?.let { " (${formatSigned(it)} bpm)" }.orEmpty()
        val hrvDelta = deltaHrv?.let { " (${formatSigned(it)})" }.orEmpty()
        val stepDelta = deltaSteps?.let { " (${formatSigned(it)} vs base)" }.orEmpty()

        return RecordedSummaryBlock(
            overviewStats = listOf(
                Metric("Recovery", "$recovery%", "-", DeltaDirection.Neutral),
                Metric("Strain", strain.toString(), "+", DeltaDirection.UpBad),
                Metric("Readiness", readiness.toString(), "-", DeltaDirection.Neutral),
                Metric("Sleep", formatHours(latest.sleepHours), formatOptionalDelta(deltaSleep, "h"), deltaDirection(deltaSleep, upIsBad = false)),
                Metric("HRV", latest.hrvRmssd.roundToInt().toString(), formatOptionalDelta(deltaHrv, ""), deltaDirection(deltaHrv, upIsBad = false)),
                Metric("Hydration", "${latest.hydrationPercent.roundToInt()}%", "-", DeltaDirection.Neutral),
            ),
            overviewNote = "Computed from ${recorded.size} recorded daily summaries in SQLite.",
            overviewLine = "Recovery $recovery% - Strain $strain - Readiness $readiness",
            footerPrimary = "RHR ${latest.restingBpm.roundToInt()}$rhrDelta - HRV ${latest.hrvRmssd.roundToInt()}$hrvDelta - Sleep ${formatHours(latest.sleepHours)}",
            footerSecondary = "Steps ${latest.dailySteps}$stepDelta - Hydration ${latest.hydrationPercent.roundToInt()}%",
        )
    }

    private fun DailyHealthSummary.isRecordedSource(): Boolean {
        val normalized = source.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized != "fake_periodic_seed" &&
            !normalized.contains("fake") &&
            !normalized.contains("seed") &&
            !normalized.contains("demo") &&
            !normalized.contains("synthetic")
    }

    private fun List<DailyHealthSummary>.deltaOrNull(
        latest: Double,
        readValue: (DailyHealthSummary) -> Double,
    ): Double? {
        if (isEmpty()) return null
        return latest - map(readValue).average()
    }

    private fun heartOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        prediction: RiskPrediction,
        latest: DailyHealthSummary,
        deltaResting: Double,
        deltaHrv: Double,
        watchSnapshot: WatchSnapshotSummary?,
        ecgSummary: EcgSessionSummary?,
    ): OrganNode {
        val needsAttention = prediction.arrhythmiaScore > 0.42 || prediction.cardiacDecompScore > 0.42
        val currentBpm = watchSnapshot?.currentBpm
        val restingBpm = watchSnapshot?.restingBpm ?: latest.restingBpm
        val hrv = watchSnapshot?.hrvRmssd ?: latest.hrvRmssd
        val baselineResting = latest.restingBpm - deltaResting
        val baselineHrv = latest.hrvRmssd - deltaHrv
        val watchLine = watchSnapshot?.let {
            "Watch live: Current BPM ${currentBpm?.roundToInt() ?: "n/a"}, resting HR ${restingBpm.roundToInt()}, source ${it.source}."
        } ?: "Watch live BPM has not been received yet."
        val ecgLine = ecgSummary?.let {
            val hz = it.samplingHz?.let { value -> "${value.roundToInt()} Hz" } ?: "sampling Hz n/a"
            val range = if (it.minMv != null && it.maxMv != null) {
                "range ${formatMv(it.minMv)} to ${formatMv(it.maxMv)}"
            } else {
                "mV range n/a"
            }
            "ECG ${it.sessionId}: ${it.sampleCount} samples at $hz, latest ${formatOptionalMv(it.latestMv)}, $range, lead-off ${it.leadOffCount}."
        } ?: "ECG packets have not been received yet."
        return baseOrgan(
            component = component,
            attentionScore = (0.58f + prediction.cardiacDecompScore.toFloat()).coerceIn(0.35f, 1.0f),
            metrics = listOf(
                Metric(
                    "Current BPM",
                    currentBpm?.roundToInt()?.toString() ?: latest.averageHeartRate.roundToInt().toString(),
                    if (watchSnapshot != null) "live" else "avg",
                    DeltaDirection.UpBad,
                ),
                Metric(
                    "Resting HR",
                    restingBpm.roundToInt().toString(),
                    formatDelta(restingBpm - baselineResting, "bpm"),
                    deltaDirection(restingBpm - baselineResting, upIsBad = true),
                ),
                Metric(
                    "HRV",
                    hrv.roundToInt().toString(),
                    formatDelta(hrv - baselineHrv, ""),
                    deltaDirection(hrv - baselineHrv, upIsBad = false),
                ),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.restingBpm }, latest.restingBpm),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.averageHeartRate - 58.0 }, 20.0),
            sentenceWeek = "$watchLine Cardiac risk is ${percent(prediction.cardiacDecompScore)}.",
            sentenceMonth = ecgLine,
            sentenceNextStep = if (needsAttention) {
                "Keep today easy and prioritize sleep until resting HR returns toward baseline."
            } else {
                "Cardiac signals are inside the current MVP guardrails."
            },
            statusGood = !needsAttention,
            previewSummary = "${watchSnapshot?.event ?: "Cardiac ${percent(prediction.cardiacDecompScore)}"}, ${ecgSummary?.sampleCount ?: 0} ECG samples.",
            chatChips = listOf("Explain my risk score", "What changed today?", "Show ECG evidence"),
        )
    }

    private fun sleepOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        prediction: RiskPrediction,
        latest: DailyHealthSummary,
        deltaSleep: Double,
    ): OrganNode {
        val score = latest.sleepEfficiency.roundToInt()
        return baseOrgan(
            component = component,
            attentionScore = (0.45f + prediction.sleepImpairmentScore.toFloat()).coerceIn(0.30f, 1.0f),
            metrics = listOf(
                Metric("Score", score.toString(), formatDelta(score - 86.0, ""), deltaDirection(score - 86.0, upIsBad = false)),
                Metric("Sleep", formatHours(latest.sleepHours), formatDelta(deltaSleep, "h"), deltaDirection(deltaSleep, upIsBad = false)),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.sleepEfficiency }, latest.sleepEfficiency),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.sleepHours * 8.0 }, latest.sleepHours * 8.0),
            sentenceWeek = "Sleep impairment is ${percent(prediction.sleepImpairmentScore)} from hours, efficiency, and HRV trend.",
            sentenceMonth = "The fake path seeds nightly summaries; the real path can add FHIR clinical records into the same store.",
            sentenceNextStep = if (prediction.sleepImpairmentScore > 0.40) {
                "Move bedtime earlier tonight and keep the first workout block easy."
            } else {
                "Sleep is stable enough for normal training load."
            },
            statusGood = prediction.sleepImpairmentScore < 0.40,
            previewSummary = "Sleep score $score with ${formatHours(latest.sleepHours)} logged last night.",
            chatChips = listOf("Why am I tired?", "Sleep trend?"),
        )
    }

    private fun brainOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        latest: DailyHealthSummary,
        deltaHrv: Double,
    ): OrganNode {
        val focus = (latest.sleepEfficiency * 0.55 + latest.hrvRmssd * 0.45).roundToInt().coerceIn(1, 99)
        return baseOrgan(
            component = component,
            attentionScore = 0.44f,
            metrics = listOf(
                Metric("Focus", focus.toString(), if (deltaHrv >= 0) "+" else "-", deltaDirection(deltaHrv, upIsBad = false)),
                Metric("Stress HRV", latest.hrvRmssd.roundToInt().toString(), formatDelta(deltaHrv, ""), deltaDirection(deltaHrv, upIsBad = false)),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.hrvRmssd }, latest.hrvRmssd),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.sleepEfficiency - 60.0 }, 20.0),
            sentenceWeek = "Cognitive readiness is inferred from HRV and sleep until web mood inputs are added.",
            sentenceMonth = "Backend identity, icon, and labels are loaded from health_components.",
            sentenceNextStep = "Use mood inputs next so the brain component can blend self-reported sentiment.",
            statusGood = focus >= 65,
            previewSummary = "Focus estimate $focus from HRV and sleep efficiency.",
            chatChips = listOf("Mental load high?", "What drives focus?"),
        )
    }

    private fun liverOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        latest: DailyHealthSummary,
        fhirImport: FhirImportResult?,
    ): OrganNode =
        baseOrgan(
            component = component,
            attentionScore = if ((fhirImport?.observationCount ?: 0) > 0) 0.72f else 0.45f,
            metrics = listOf(
                Metric("FHIR obs", (fhirImport?.observationCount ?: 0).toString(), "-", DeltaDirection.Neutral),
                Metric("Hydration", "${latest.hydrationPercent.roundToInt()}%", "-", DeltaDirection.Neutral),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.hydrationPercent }, latest.hydrationPercent),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.dailySteps / 300.0 }, 20.0),
            sentenceWeek = "FHIR clinical records are stored for labs and diagnoses; hydration is from the watch/fake stream.",
            sentenceMonth = "This component exists because health_components contains ${component.id} with ${component.iconAsset}.",
            sentenceNextStep = "Map liver-specific FHIR labs into this component next.",
            statusGood = true,
            previewSummary = fhirImport?.let { "Imported ${it.observationCount} observations from FHIR." }
                ?: "Real FHIR import has not run yet.",
            chatChips = listOf("Which FHIR labs?", "Hydration trend?"),
        )

    private fun kidneyOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        latest: DailyHealthSummary,
        fhirImport: FhirImportResult?,
    ): OrganNode =
        baseOrgan(
            component = component,
            attentionScore = 0.45f,
            metrics = listOf(
                Metric("Hydration", "${latest.hydrationPercent.roundToInt()}%", "-", DeltaDirection.Neutral),
                Metric("Conditions", (fhirImport?.conditionCount ?: 0).toString(), "-", DeltaDirection.Neutral),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.hydrationPercent }, latest.hydrationPercent),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.sleepEfficiency - 58.0 }, 20.0),
            sentenceWeek = "Kidney view is ready for FHIR labs; watch hydration is already mapped.",
            sentenceMonth = "Backend conditions are available to classify kidney-related risk when lab mapping is added.",
            sentenceNextStep = "Import creatinine and eGFR observations from FHIR when available.",
            statusGood = true,
            previewSummary = "FHIR condition count: ${fhirImport?.conditionCount ?: 0}.",
            chatChips = listOf("Any kidney labs?", "Hydration enough?"),
        )

    private fun lungsOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        latest: DailyHealthSummary,
        deltaSteps: Double,
    ): OrganNode =
        baseOrgan(
            component = component,
            attentionScore = 0.50f,
            metrics = listOf(
                Metric("Steps", latest.dailySteps.toString(), formatDelta(deltaSteps, ""), deltaDirection(deltaSteps, upIsBad = false)),
                Metric("Resp rate", "n/a", "-", DeltaDirection.Neutral),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.dailySteps.toDouble() / 180.0 }, latest.dailySteps / 180.0),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.averageHeartRate - 58.0 }, 20.0),
            sentenceWeek = "Activity load is live in SQLite; respiratory rate awaits watch or FHIR observation mapping.",
            sentenceMonth = "If this component row is disabled or its icon path is blank, it disappears from the UI.",
            sentenceNextStep = "Add respiratory rate from watch/FHIR when the source is available.",
            statusGood = true,
            previewSummary = "Steps are ${latest.dailySteps}; respiratory records are not connected yet.",
            chatChips = listOf("How active?", "Respiratory data?"),
        )

    private fun genericOrgan(
        component: HealthComponentDefinition,
        summaries: List<DailyHealthSummary>,
        latest: DailyHealthSummary,
    ): OrganNode =
        baseOrgan(
            component = component,
            attentionScore = 0.40f,
            metrics = listOf(
                Metric("Samples", summaries.size.toString(), "-", DeltaDirection.Neutral),
                Metric("Hydration", "${latest.hydrationPercent.roundToInt()}%", "-", DeltaDirection.Neutral),
            ),
            chart7Day = HealthInterpolator.sevenDay(summaries.map { it.hydrationPercent }, latest.hydrationPercent),
            activeZones = HealthInterpolator.sevenDay(summaries.map { it.sleepEfficiency - 60.0 }, 20.0),
            sentenceWeek = "${component.displayName} is backend-defined and ready for metric mapping.",
            sentenceMonth = "Add a mapper branch for ${component.id} when disease-specific signals are available.",
            sentenceNextStep = "Connect this component to a watch, web, or FHIR signal.",
            statusGood = true,
            previewSummary = "${component.displayName} loaded from backend health_components.",
            chatChips = listOf("Which data?", "What changed?"),
        )

    private fun baseOrgan(
        component: HealthComponentDefinition,
        attentionScore: Float,
        metrics: List<Metric>,
        chart7Day: List<Float>,
        activeZones: List<Float>,
        sentenceWeek: String,
        sentenceMonth: String,
        sentenceNextStep: String,
        statusGood: Boolean,
        previewSummary: String,
        chatChips: List<String>,
    ): OrganNode {
        val accent = parseColor(component.accentHex)
        return OrganNode(
            id = component.id,
            displayName = component.displayName,
            systemLabel = component.systemLabel,
            iconAsset = component.iconAsset,
            componentType = component.componentType,
            accent = accent,
            tint = accent.copy(alpha = 0.18f),
            attentionScore = attentionScore,
            metrics = metrics,
            chart7Day = chart7Day,
            activeZones = activeZones,
            sentenceWeek = sentenceWeek,
            sentenceMonth = sentenceMonth,
            sentenceNextStep = sentenceNextStep,
            statusGood = statusGood,
            previewSummary = previewSummary,
            chatChips = chatChips,
        )
    }

    private fun parseColor(hex: String): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrElse { Color(0xFF9AA3B2) }

    private fun deltaDirection(delta: Double, upIsBad: Boolean): DeltaDirection =
        when {
            delta > 0.2 && upIsBad -> DeltaDirection.UpBad
            delta > 0.2 -> DeltaDirection.UpGood
            delta < -0.2 && upIsBad -> DeltaDirection.DownGood
            delta < -0.2 -> DeltaDirection.DownBad
            else -> DeltaDirection.Neutral
        }

    private fun deltaDirection(delta: Double?, upIsBad: Boolean): DeltaDirection =
        delta?.let { deltaDirection(it, upIsBad) } ?: DeltaDirection.Neutral

    private fun formatDelta(delta: Double, unit: String): String {
        if (kotlin.math.abs(delta) < 0.2) return "-"
        val rounded = delta.roundToInt()
        val prefix = if (rounded > 0) "+" else ""
        return "$prefix$rounded$unit"
    }

    private fun formatOptionalDelta(delta: Double?, unit: String): String =
        delta?.let { formatDelta(it, unit) } ?: "-"

    private fun formatOptionalMv(value: Double?): String =
        value?.let { formatMv(it) } ?: "mV n/a"

    private fun formatMv(value: Double): String =
        "${String.format("%.3f", value)} mV"

    private fun formatSigned(delta: Double): String {
        val rounded = delta.roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }

    private fun formatHours(hours: Double): String {
        val whole = hours.toInt()
        val minutes = ((hours - whole) * 60).roundToInt().coerceIn(0, 59)
        return "${whole}h${minutes.toString().padStart(2, '0')}m"
    }

    private fun percent(score: Double): String = "${(score * 100).roundToInt()}%"
}
