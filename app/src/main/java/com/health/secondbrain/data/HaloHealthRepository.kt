package com.health.secondbrain.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.health.secondbrain.features.HealthRiskEngine
import com.health.secondbrain.health.HealthDashboardMapper
import com.health.secondbrain.health.HealthDashboardUiState
import com.health.secondbrain.model.OrganNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class HaloHealthRepository(context: Context) {

    private val database = HaloHealthDatabase(context.applicationContext)
    private val fakeSource = FakeHealthDataSource()
    private val realSource = RealHealthDataSource()
    private val watchJsonDataSource = WatchJsonDataSource()
    private val watchIngestor = WatchSampleIngestor()

    suspend fun loadUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        UserProfile(
            displayName = "You",
            memberLabel = "HALO health profile",
            avatarInitials = "YOU",
            statusLabel = "Active",
            lastUpdatedText = "Updated just now",
        )
    }

    suspend fun loadUserPreferences(): UserPreferences = withContext(Dispatchers.IO) {
        UserPreferences(
            preferredUnits = "US units",
            remindersEnabled = true,
            dataSharingLabel = "Local SQLite + optional FHIR",
        )
    }

    suspend fun loadDashboard(mode: HealthBackendMode): HealthDashboardUiState =
        withContext(Dispatchers.IO) {
            val writable = database.writableDatabase
            fakeSource.seedComponentsIfEmpty(writable)
            fakeSource.seedIfEmpty(writable)

            val fhirImport = if (mode == HealthBackendMode.Real) {
                runCatching { realSource.importFromFhir(writable) }
                    .getOrElse { realSource.seedSyntheticFallback(writable, it.message) }
            } else {
                null
            }

            val components = readComponents(writable)
            val summaries = readDailySummaries(writable)
            val prediction = HealthRiskEngine.score(summaries)
            persistPrediction(writable, prediction)
            val dashboard = HealthDashboardMapper.from(components, summaries, prediction, mode, fhirImport)
            val activeAlert = readActiveAlert(writable)
            if (activeAlert == null) {
                dashboard
            } else {
                dashboard.copy(
                    backendStatus = "${dashboard.backendStatus} - active ${activeAlert.alertType} ${activeAlert.severity}",
                )
            }
        }

    suspend fun loadAgentContext(
        mode: HealthBackendMode,
        organ: OrganNode,
    ): HealthAgentContext = withContext(Dispatchers.IO) {
        val writable = database.writableDatabase
        fakeSource.seedComponentsIfEmpty(writable)
        fakeSource.seedIfEmpty(writable)

        val summaries = readDailySummaries(writable)
        val recordedSummaries = summaries.filter { it.isRecordedSignal() }
        HealthAgentContext(
            generatedAt = Instant.now(),
            mode = mode,
            organ = organ,
            recordedDailySummaries = recordedSummaries,
            demoDailySummaryCount = summaries.size - recordedSummaries.size,
            recordedRisk = recordedSummaries.takeIf { it.isNotEmpty() }?.let { HealthRiskEngine.score(it) },
            clinicalObservations = readClinicalObservations(writable),
            activeAlert = readActiveAlert(writable),
            recentWatchEvents = readRecentWatchEvents(writable),
            signalTimeline = readSignalTimeline(summaries),
        )
    }

    suspend fun ingestWatchJson(rawJson: String, source: String): WatchIngestResult =
        withContext(Dispatchers.IO) {
            val writable = database.writableDatabase
            fakeSource.seedComponentsIfEmpty(writable)
            fakeSource.seedIfEmpty(writable)

            val payload = watchJsonDataSource.parse(rawJson, source)
            val draft = watchIngestor.ingest(writable, payload)
            val prediction = HealthRiskEngine.score(readDailySummaries(writable))
            persistPrediction(writable, prediction)
            val alert = watchIngestor.persistAlertIfNeeded(writable, draft, prediction)
            WatchIngestResult(
                batchId = payload.batchId,
                source = payload.source,
                payloadType = payload.payloadType,
                receivedAt = payload.receivedAt,
                dailySummariesUpserted = draft.dailySummariesUpserted,
                ecgSamplesInserted = draft.ecgSamplesInserted,
                activeAlert = alert,
                riskPrediction = prediction,
            )
        }

    suspend fun loadActiveAlert(): ActiveAlertSummary? = withContext(Dispatchers.IO) {
        readActiveAlert(database.readableDatabase)
    }

    suspend fun addUserInput(
        inputType: String,
        valueText: String?,
        valueNumber: Double?,
        unit: String?,
        rawJson: String? = null,
    ) = withContext(Dispatchers.IO) {
        database.writableDatabase.insert(
            "user_inputs",
            null,
            ContentValues().apply {
                put("input_type", inputType)
                put("value_text", valueText)
                put("value_number", valueNumber)
                put("unit", unit)
                put("occurred_at", Instant.now().toString())
                put("raw_json", rawJson)
            }
        )
    }

    private fun readComponents(db: SQLiteDatabase): List<HealthComponentDefinition> {
        val cursor = db.query(
            "health_components",
            arrayOf(
                "id",
                "display_name",
                "system_label",
                "component_type",
                "icon_asset",
                "accent_hex",
                "sort_order",
                "enabled",
            ),
            "enabled = 1 AND icon_asset != ''",
            null,
            null,
            null,
            "sort_order ASC",
        )
        cursor.use {
            val rows = ArrayList<HealthComponentDefinition>()
            while (it.moveToNext()) {
                rows += HealthComponentDefinition(
                    id = it.getString(0),
                    displayName = it.getString(1),
                    systemLabel = it.getString(2),
                    componentType = it.getString(3),
                    iconAsset = it.getString(4),
                    accentHex = it.getString(5),
                    sortOrder = it.getInt(6),
                    enabled = it.getInt(7) == 1,
                )
            }
            return rows
        }
    }

    private fun readDailySummaries(db: SQLiteDatabase): List<DailyHealthSummary> {
        val cursor = db.query(
            "daily_health_summaries",
            arrayOf(
                "day",
                "resting_bpm",
                "average_heart_rate",
                "daily_steps",
                "sleep_hours",
                "sleep_efficiency",
                "hrv_rmssd",
                "hydration_percent",
                "source",
            ),
            null,
            null,
            null,
            null,
            "day ASC",
        )
        cursor.use {
            val rows = ArrayList<DailyHealthSummary>()
            while (it.moveToNext()) {
                rows += DailyHealthSummary(
                    day = LocalDate.parse(it.getString(0)),
                    restingBpm = it.getDouble(1),
                    averageHeartRate = it.getDouble(2),
                    dailySteps = it.getInt(3),
                    sleepHours = it.getDouble(4),
                    sleepEfficiency = it.getDouble(5),
                    hrvRmssd = it.getDouble(6),
                    hydrationPercent = it.getDouble(7),
                    source = it.getString(8),
                )
            }
            return rows
        }
    }

    private fun readClinicalObservations(db: SQLiteDatabase): List<ClinicalObservationSummary> {
        val cursor = db.rawQuery(
            """
            SELECT
                o.display,
                o.value_text,
                o.value_number,
                o.unit,
                o.observed_at,
                r.source
            FROM clinical_observations o
            LEFT JOIN clinical_records r ON r.id = o.clinical_record_id
            ORDER BY COALESCE(o.observed_at, r.recorded_at) DESC
            LIMIT 12
            """.trimIndent(),
            null,
        )
        cursor.use {
            val rows = ArrayList<ClinicalObservationSummary>()
            while (it.moveToNext()) {
                rows += ClinicalObservationSummary(
                    display = it.getString(0) ?: "Observation",
                    valueText = it.getNullableString(1),
                    valueNumber = if (it.isNull(2)) null else it.getDouble(2),
                    unit = it.getNullableString(3),
                    observedAt = it.getNullableString(4),
                    source = it.getNullableString(5) ?: "unknown",
                )
            }
            return rows
        }
    }

    private fun readActiveAlert(db: SQLiteDatabase): ActiveAlertSummary? {
        val cursor = db.query(
            "active_alerts",
            arrayOf("id", "alert_type", "severity", "created_at", "source", "evidence_json", "acknowledged"),
            "acknowledged = 0",
            null,
            null,
            null,
            "created_at DESC",
            "1",
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return ActiveAlertSummary(
                id = it.getString(0),
                alertType = it.getString(1),
                severity = it.getString(2),
                createdAt = Instant.parse(it.getString(3)),
                source = it.getString(4),
                evidenceJson = it.getString(5),
                acknowledged = it.getInt(6) == 1,
            )
        }
    }

    private fun readRecentWatchEvents(db: SQLiteDatabase): List<WatchEventSummary> {
        val cursor = db.query(
            "watch_json_batches",
            arrayOf("id", "received_at", "source", "payload_type", "raw_json"),
            null,
            null,
            null,
            null,
            "received_at DESC",
            "6",
        )
        cursor.use {
            val rows = ArrayList<WatchEventSummary>()
            while (it.moveToNext()) {
                rows += WatchEventSummary(
                    id = it.getString(0),
                    receivedAt = Instant.parse(it.getString(1)),
                    source = it.getString(2),
                    payloadType = it.getString(3),
                    summary = summarizeWatchJson(it.getString(4)),
                )
            }
            return rows
        }
    }

    private fun summarizeWatchJson(rawJson: String): String {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return "watch packet received"
        val parts = buildList {
            root.optDoubleOrNull("heart_rate_bpm")?.let { add("HR ${it.toInt()} bpm") }
            root.optDoubleOrNull("heartRateBpm")?.let { add("HR ${it.toInt()} bpm") }
            root.optString("irregular_rhythm_status").takeIf { it.isNotBlank() }?.let { add("rhythm $it") }
            root.optJSONObject("heartRate")?.optString("latestBpm")?.takeIf { it.isNotBlank() }?.let { add("HR $it bpm") }
            root.optJSONObject("irregularRhythm")?.optInt("detectedRows", 0)?.takeIf { it > 0 }?.let {
                add("irregular rhythm detected")
            }
            root.optJSONArray("samples")?.length()?.takeIf { it > 0 }?.let { add("$it ECG samples") }
        }
        return parts.ifEmpty { listOf("watch packet received") }.joinToString(", ")
    }

    private fun readSignalTimeline(summaries: List<DailyHealthSummary>): List<SignalChangeSummary> {
        if (summaries.size < 2) return emptyList()
        val timeline = mutableListOf<SignalChangeSummary>()
        summaries.zipWithNext().forEach { (previous, current) ->
            val sleepDelta = current.sleepHours - previous.sleepHours
            val efficiencyDelta = current.sleepEfficiency - previous.sleepEfficiency
            val hrvDelta = current.hrvRmssd - previous.hrvRmssd
            val heartDelta = current.averageHeartRate - previous.averageHeartRate
            if (sleepDelta <= -0.3 || efficiencyDelta <= -4.0) {
                timeline += SignalChangeSummary(
                    occurredAt = current.day.toString(),
                    signal = "sleep",
                    value = "${current.sleepHours}h, ${current.sleepEfficiency.toInt()}%",
                    source = current.source,
                    description = "Sleep worsened before the later cardiac strain signal.",
                )
            }
            if (hrvDelta <= -4.0) {
                timeline += SignalChangeSummary(
                    occurredAt = current.day.toString(),
                    signal = "hrv_rmssd",
                    value = current.hrvRmssd.toInt().toString(),
                    source = current.source,
                    description = "HRV dropped after sleep disruption, suggesting recovery stress.",
                )
            }
            if (heartDelta >= 5.0 || current.restingBpm - previous.restingBpm >= 5.0) {
                timeline += SignalChangeSummary(
                    occurredAt = current.day.toString(),
                    signal = "heart_rate",
                    value = "${current.averageHeartRate.toInt()} avg bpm",
                    source = current.source,
                    description = "Heart rate rose after sleep and HRV changes.",
                )
            }
        }
        return timeline.takeLast(12)
    }

    private fun android.database.Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else when (val value = opt(name)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }

    private fun persistPrediction(db: SQLiteDatabase, prediction: RiskPrediction) {
        val featureId = db.insert(
            "feature_vectors",
            null,
            ContentValues().apply {
                put("generated_at", prediction.generatedAt.toString())
                put("features_json", prediction.rawJson)
            }
        )
        db.insert(
            "risk_predictions",
            null,
            ContentValues().apply {
                put("feature_vector_id", featureId)
                put("generated_at", prediction.generatedAt.toString())
                put("arrhythmia_score", prediction.arrhythmiaScore)
                put("cardiac_decomp_score", prediction.cardiacDecompScore)
                put("sleep_impairment_score", prediction.sleepImpairmentScore)
                put("raw_json", prediction.rawJson)
            }
        )
    }
}
