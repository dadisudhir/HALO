package com.health.secondbrain.data

import com.health.secondbrain.model.Metric
import com.health.secondbrain.model.OrganNode
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.roundToInt

data class HealthAgentContext(
    val generatedAt: Instant,
    val mode: HealthBackendMode,
    val organ: OrganNode,
    val recordedDailySummaries: List<DailyHealthSummary>,
    val demoDailySummaryCount: Int,
    val recordedRisk: RiskPrediction?,
    val clinicalObservations: List<ClinicalObservationSummary>,
) {
    val hasRecordedBiometrics: Boolean = recordedDailySummaries.isNotEmpty()

    fun toPromptJson(): String {
        val latest = recordedDailySummaries.lastOrNull()
        val recent = recordedDailySummaries.takeLast(3)
        val personalMetrics = if (hasRecordedBiometrics) organ.metrics.toJson() else JSONArray()
        return JSONObject()
            .put("generated_at", generatedAt.toString())
            .put("mode", mode.name.lowercase())
            .put(
                "organ",
                JSONObject()
                    .put("id", organ.id)
                    .put("name", organ.displayName)
                    .put("system_label", organ.systemLabel)
                    .put("status_good", if (hasRecordedBiometrics) organ.statusGood else JSONObject.NULL)
                    .put("personal_metrics_available", hasRecordedBiometrics)
                    .put(
                        "personal_preview",
                        if (hasRecordedBiometrics) organ.previewSummary else JSONObject.NULL
                    )
                    .put("personal_metrics", personalMetrics)
            )
            .put(
                "display_context",
                JSONObject()
                    .put("available", organ.metrics.isNotEmpty())
                    .put("display_only", true)
                    .put("source_policy", "Visible HALO card values may describe the UI, but are not recorded personal biometrics.")
                    .put("status_good", organ.statusGood)
                    .put("preview_summary", organ.previewSummary)
                    .put("week_summary", organ.sentenceWeek)
                    .put("month_summary", organ.sentenceMonth)
                    .put("next_step", organ.sentenceNextStep)
                    .put("metrics", organ.metrics.toJson())
                    .put("chart_7_day", JSONArray(organ.chart7Day.map { it.toDouble() }))
                    .put("active_zones", JSONArray(organ.activeZones.map { it.toDouble() }))
            )
            .put("recorded_daily_count", recordedDailySummaries.size)
            .put("demo_daily_count", demoDailySummaryCount)
            .put("latest_recorded_daily", latest?.toJson())
            .put("recent_recorded_daily", JSONArray(recent.map { it.toJson() }))
            .put("recorded_risk", recordedRisk?.toJson())
            .put("clinical_observations", JSONArray(clinicalObservations.take(5).map { it.toJson() }))
            .put(
                "missing_data_policy",
                "Missing or demo-only values are unavailable for personal conclusions."
            )
            .toString()
    }

    private fun List<Metric>.toJson(): JSONArray =
        JSONArray(map { metric ->
            JSONObject()
                .put("label", metric.label)
                .put("value", metric.value)
                .put("delta", metric.deltaText)
                .put("direction", metric.direction.name)
        })

    private fun DailyHealthSummary.toJson(): JSONObject =
        JSONObject()
            .put("day", day.toString())
            .put("resting_bpm", restingBpm.roundToInt())
            .put("average_heart_rate", averageHeartRate.roundToInt())
            .put("daily_steps", dailySteps)
            .put("sleep_hours", sleepHours)
            .put("sleep_efficiency", sleepEfficiency.roundToInt())
            .put("hrv_rmssd", hrvRmssd.roundToInt())
            .put("hydration_percent", hydrationPercent.roundToInt())
            .put("source", source)

    private fun RiskPrediction.toJson(): JSONObject =
        JSONObject()
            .put("generated_at", generatedAt.toString())
            .put("arrhythmia_score", arrhythmiaScore)
            .put("cardiac_decomp_score", cardiacDecompScore)
            .put("sleep_impairment_score", sleepImpairmentScore)
            .put("model", "heuristic_mvp")

    private fun ClinicalObservationSummary.toJson(): JSONObject =
        JSONObject()
            .put("display", display)
            .put("value_text", valueText)
            .put("value_number", valueNumber)
            .put("unit", unit)
            .put("observed_at", observedAt)
            .put("source", source)
            .put("demo_source", source.isDemoHealthSource())
}
