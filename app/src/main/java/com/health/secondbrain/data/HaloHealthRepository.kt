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
import java.time.Instant
import java.time.LocalDate

class HaloHealthRepository(context: Context) {

    private val database = HaloHealthDatabase(context.applicationContext)
    private val fakeSource = FakeHealthDataSource()
    private val realSource = RealHealthDataSource()

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
            HealthDashboardMapper.from(components, summaries, prediction, mode, fhirImport)
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
        )
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

    private fun android.database.Cursor.getNullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

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
