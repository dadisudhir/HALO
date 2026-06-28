package com.health.secondbrain.features

import com.health.secondbrain.data.DailyHealthSummary
import com.health.secondbrain.data.RiskPrediction
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

object HealthRiskEngine {

    fun score(summaries: List<DailyHealthSummary>): RiskPrediction {
        val generatedAt = Instant.now()
        if (summaries.isEmpty()) {
            val raw = JSONObject()
                .put("status", "empty")
                .put("model", "heuristic_mvp")
                .toString()
            return RiskPrediction(generatedAt, 0.0, 0.0, 0.0, raw)
        }

        val current = summaries.takeLast(7)
        val baseline = summaries.dropLast(7).ifEmpty { summaries }

        val currentResting = current.map { it.restingBpm }.average()
        val baselineResting = baseline.map { it.restingBpm }.average()
        val currentAvgHr = current.map { it.averageHeartRate }.average()
        val baselineAvgHr = baseline.map { it.averageHeartRate }.average()
        val currentSteps = current.map { it.dailySteps.toDouble() }.average()
        val baselineSteps = baseline.map { it.dailySteps.toDouble() }.average()
        val currentSleep = current.map { it.sleepHours }.average()
        val currentEfficiency = current.map { it.sleepEfficiency }.average()
        val currentHrv = current.map { it.hrvRmssd }.average()
        val baselineHrv = baseline.map { it.hrvRmssd }.average()
        val stepSwing = current.map { abs(it.dailySteps - currentSteps) }.average()

        val arrhythmia = HealthInterpolator.clamp01(
            0.12 +
                max(0.0, currentResting - baselineResting) / 18.0 +
                max(0.0, baselineHrv - currentHrv) / 48.0 +
                stepSwing / 22000.0
        )
        val cardiac = HealthInterpolator.clamp01(
            0.14 +
                max(0.0, currentAvgHr - baselineAvgHr) / 24.0 +
                max(0.0, baselineSteps - currentSteps) / 9000.0 +
                max(0.0, currentResting - 62.0) / 28.0
        )
        val sleep = HealthInterpolator.clamp01(
            0.10 +
                max(0.0, 7.2 - currentSleep) / 2.4 +
                max(0.0, 86.0 - currentEfficiency) / 34.0 +
                max(0.0, baselineHrv - currentHrv) / 70.0
        )

        val raw = JSONObject()
            .put("model", "heuristic_mvp")
            .put("current_resting_bpm", currentResting)
            .put("baseline_resting_bpm", baselineResting)
            .put("current_avg_heart_rate", currentAvgHr)
            .put("baseline_avg_heart_rate", baselineAvgHr)
            .put("current_daily_steps", currentSteps)
            .put("baseline_daily_steps", baselineSteps)
            .put("current_sleep_hours", currentSleep)
            .put("current_sleep_efficiency", currentEfficiency)
            .put("current_hrv_rmssd", currentHrv)
            .put("baseline_hrv_rmssd", baselineHrv)
            .put("weekly_step_variability", stepSwing)
            .toString()

        return RiskPrediction(generatedAt, arrhythmia, cardiac, sleep, raw)
    }
}
