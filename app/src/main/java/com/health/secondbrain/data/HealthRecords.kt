package com.health.secondbrain.data

import java.time.Instant
import java.time.LocalDate

enum class HealthBackendMode {
    Fake,
    Real,
}

data class UserProfile(
    val displayName: String,
    val memberLabel: String,
    val avatarInitials: String,
    val statusLabel: String,
    val lastUpdatedText: String,
)

data class UserPreferences(
    val preferredUnits: String,
    val remindersEnabled: Boolean,
    val dataSharingLabel: String,
)

data class DailyHealthSummary(
    val day: LocalDate,
    val restingBpm: Double,
    val averageHeartRate: Double,
    val dailySteps: Int,
    val sleepHours: Double,
    val sleepEfficiency: Double,
    val hrvRmssd: Double,
    val hydrationPercent: Double,
    val source: String,
)

fun String.isDemoHealthSource(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.isBlank()) return true
    return normalized.contains("fake") ||
        normalized.contains("seed") ||
        normalized.contains("demo") ||
        normalized.contains("synthetic")
}

fun DailyHealthSummary.isRecordedSignal(): Boolean = !source.isDemoHealthSource()

data class HealthComponentDefinition(
    val id: String,
    val displayName: String,
    val systemLabel: String,
    val componentType: String,
    val iconAsset: String,
    val accentHex: String,
    val sortOrder: Int,
    val enabled: Boolean,
)

data class RiskPrediction(
    val generatedAt: Instant,
    val arrhythmiaScore: Double,
    val cardiacDecompScore: Double,
    val sleepImpairmentScore: Double,
    val rawJson: String,
)

data class FhirImportResult(
    val patientCount: Int,
    val observationCount: Int,
    val conditionCount: Int,
    val status: String,
)

data class ClinicalObservationSummary(
    val display: String,
    val valueText: String?,
    val valueNumber: Double?,
    val unit: String?,
    val observedAt: String?,
    val source: String,
)

data class WatchIngestResult(
    val batchId: String,
    val source: String,
    val payloadType: String,
    val receivedAt: Instant,
    val dailySummariesUpserted: Int,
    val ecgSamplesInserted: Int,
    val activeAlert: ActiveAlertSummary?,
    val riskPrediction: RiskPrediction,
)

data class ActiveAlertSummary(
    val id: String,
    val alertType: String,
    val severity: String,
    val createdAt: Instant,
    val source: String,
    val evidenceJson: String,
    val acknowledged: Boolean,
)

data class WatchEventSummary(
    val id: String,
    val receivedAt: Instant,
    val source: String,
    val payloadType: String,
    val summary: String,
)

data class SignalChangeSummary(
    val occurredAt: String,
    val signal: String,
    val value: String,
    val source: String,
    val description: String,
)

data class WatchSnapshotSummary(
    val currentBpm: Double?,
    val restingBpm: Double?,
    val hrvRmssd: Double?,
    val capturedAt: Instant,
    val source: String,
    val event: String,
)

data class EcgSessionSummary(
    val sessionId: String,
    val sampleCount: Int,
    val samplingHz: Double?,
    val latestMv: Double?,
    val minMv: Double?,
    val maxMv: Double?,
    val leadOffCount: Int,
    val lastReceivedAt: Instant,
)
