package com.health.secondbrain.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

class WatchSampleIngestor {

    fun ingest(db: SQLiteDatabase, payload: ParsedWatchPayload): WatchIngestDraft {
        var dailyRows = 0
        var ecgRows = 0
        db.beginTransaction()
        try {
            insertBatch(db, payload)
            dailyRows = upsertDailySummaries(db, payload)
            ecgRows = insertEcgSamples(db, payload)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return WatchIngestDraft(payload = payload, dailySummariesUpserted = dailyRows, ecgSamplesInserted = ecgRows)
    }

    fun persistAlertIfNeeded(
        db: SQLiteDatabase,
        draft: WatchIngestDraft,
        prediction: RiskPrediction,
    ): ActiveAlertSummary? {
        val payload = draft.payload
        val heartRateBpm = payload.latestHeartRateBpm
        val triggerReasons = buildList {
            if ((heartRateBpm ?: 0.0) >= 125.0) add("heart_rate_bpm >= 125")
            if (payload.irregularRhythmDetected) add("irregular rhythm status == DETECTED")
            if (prediction.cardiacDecompScore >= 0.70) add("cardiacDecompScore >= 0.70")
        }
        if (triggerReasons.isEmpty()) return null

        val createdAt = Instant.now()
        val evidence = JSONObject()
            .put("batch_id", payload.batchId)
            .put("payload_type", payload.payloadType)
            .put("source", payload.source)
            .put("captured_at", payload.capturedAt.toString())
            .put("trigger_reasons", JSONArray(triggerReasons))
            .put("heart_rate_bpm", heartRateBpm)
            .put("irregular_rhythm_detected", payload.irregularRhythmDetected)
            .put("cardiac_decomp_score", prediction.cardiacDecompScore)
            .put("arrhythmia_score", prediction.arrhythmiaScore)
            .put("sleep_impairment_score", prediction.sleepImpairmentScore)
            .put("daily_summaries_upserted", draft.dailySummariesUpserted)
            .put("ecg_samples_inserted", draft.ecgSamplesInserted)
            .toString()
        val alert = ActiveAlertSummary(
            id = UUID.randomUUID().toString(),
            alertType = "cardiac_strain",
            severity = if ((heartRateBpm ?: 0.0) >= 140.0 || payload.irregularRhythmDetected) "high" else "warning",
            createdAt = createdAt,
            source = payload.source,
            evidenceJson = evidence,
            acknowledged = false,
        )
        db.insertWithOnConflict(
            "active_alerts",
            null,
            ContentValues().apply {
                put("id", alert.id)
                put("alert_type", alert.alertType)
                put("severity", alert.severity)
                put("created_at", alert.createdAt.toString())
                put("source", alert.source)
                put("evidence_json", alert.evidenceJson)
                put("acknowledged", 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return alert
    }

    private fun insertBatch(db: SQLiteDatabase, payload: ParsedWatchPayload) {
        db.insertWithOnConflict(
            "watch_json_batches",
            null,
            ContentValues().apply {
                put("id", payload.batchId)
                put("received_at", payload.receivedAt.toString())
                put("source", payload.source)
                put("payload_type", payload.payloadType)
                put("raw_json", payload.rawJson)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun upsertDailySummaries(db: SQLiteDatabase, payload: ParsedWatchPayload): Int {
        val days = mutableMapOf<String, DailyPatch>()
        payload.heartSamples.forEach { sample ->
            val day = sample.time.toLocalDateString()
            days.getOrPut(day) { DailyPatch(day = day) }.heartRates += sample.bpm
        }
        payload.sleepSamples.forEach { sample ->
            val day = sample.time.toLocalDateString()
            val patch = days.getOrPut(day) { DailyPatch(day = day) }
            patch.sleepHours = sample.hours ?: patch.sleepHours
            patch.sleepEfficiency = sample.efficiency ?: patch.sleepEfficiency
        }
        if (days.isEmpty()) {
            if (payload.steps == null && payload.hrvRmssd == null && payload.hydrationPercent == null) return 0
            days[payload.capturedAt.toLocalDateString()] = DailyPatch(day = payload.capturedAt.toLocalDateString())
        }

        var count = 0
        days.values.forEach { patch ->
            val baseline = readBaseline(db, patch.day)
            val latestHr = patch.heartRates.lastOrNull()
            val avgHr = patch.heartRates.takeIf { it.isNotEmpty() }?.average()
            val restingHr = payload.restingBpm ?: latestHr ?: baseline.restingBpm
            db.insertWithOnConflict(
                "daily_health_summaries",
                null,
                ContentValues().apply {
                    put("day", patch.day)
                    put("resting_bpm", restingHr)
                    put("average_heart_rate", avgHr ?: latestHr ?: baseline.averageHeartRate)
                    put("daily_steps", payload.steps ?: baseline.dailySteps)
                    put("sleep_hours", patch.sleepHours ?: baseline.sleepHours)
                    put("sleep_efficiency", patch.sleepEfficiency ?: baseline.sleepEfficiency)
                    put("hrv_rmssd", payload.hrvRmssd ?: baseline.hrvRmssd)
                    put("hydration_percent", payload.hydrationPercent ?: baseline.hydrationPercent)
                    put("source", payload.source)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            count += 1
        }
        return count
    }

    private fun insertEcgSamples(db: SQLiteDatabase, payload: ParsedWatchPayload): Int {
        val packet = payload.ecgPacket ?: return 0
        val leadOffCount = packet.samples.count { it.leadOff }
        val receivedAt = payload.receivedAt.toString()
        val existing = readEcgSessionCounters(db, packet.sessionId)
        db.insertWithOnConflict(
            "ecg_sessions",
            null,
            ContentValues().apply {
                put("session_id", packet.sessionId)
                put("started_at", existing.startedAt ?: packet.capturedAt.toString())
                put("ended_at", packet.samples.lastOrNull()?.sampleTime?.toString() ?: packet.capturedAt.toString())
                put("sampling_hz", packet.samplingHz)
                put("source", packet.source)
                put("inserted_at", existing.insertedAt ?: receivedAt)
                put("last_received_at", receivedAt)
                put("sample_count", existing.sampleCount + packet.samples.size)
                put("lead_off_count", existing.leadOffCount + leadOffCount)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        packet.samples.forEach { sample ->
            db.insert(
                "ecg_samples",
                null,
                ContentValues().apply {
                    put("session_id", packet.sessionId)
                    put("received_at", receivedAt)
                    put("sample_time", sample.sampleTime?.toString())
                    put("sample_index", sample.sampleIndex)
                    put("elapsed_ms", sample.elapsedMs)
                    put("sampling_hz", packet.samplingHz)
                    put("ecg_mv", sample.ecgMv)
                    put("lead_off", if (sample.leadOff) 1 else 0)
                    put("source", packet.source)
                    put("batch_id", payload.batchId)
                    put("raw_json", sample.rawJson)
                },
            )
        }
        return packet.samples.size
    }

    private fun readBaseline(db: SQLiteDatabase, day: String): DailyBaseline {
        val sameDay = queryBaseline(
            db = db,
            selection = "day = ?",
            selectionArgs = arrayOf(day),
            orderBy = "id DESC",
        )
        if (sameDay != null) return sameDay
        return queryBaseline(db, selection = null, selectionArgs = null, orderBy = "day DESC, id DESC")
            ?: DailyBaseline(
                restingBpm = 70.0,
                averageHeartRate = 78.0,
                dailySteps = 7000,
                sleepHours = 7.0,
                sleepEfficiency = 82.0,
                hrvRmssd = 45.0,
                hydrationPercent = 70.0,
            )
    }

    private fun queryBaseline(
        db: SQLiteDatabase,
        selection: String?,
        selectionArgs: Array<String>?,
        orderBy: String,
    ): DailyBaseline? {
        val cursor = db.query(
            "daily_health_summaries",
            arrayOf(
                "resting_bpm",
                "average_heart_rate",
                "daily_steps",
                "sleep_hours",
                "sleep_efficiency",
                "hrv_rmssd",
                "hydration_percent",
            ),
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            "1",
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return DailyBaseline(
                restingBpm = it.getDouble(0),
                averageHeartRate = it.getDouble(1),
                dailySteps = it.getInt(2),
                sleepHours = it.getDouble(3),
                sleepEfficiency = it.getDouble(4),
                hrvRmssd = it.getDouble(5),
                hydrationPercent = it.getDouble(6),
            )
        }
    }

    private fun readEcgSessionCounters(db: SQLiteDatabase, sessionId: String): EcgSessionCounters {
        val cursor = db.query(
            "ecg_sessions",
            arrayOf("started_at", "inserted_at", "sample_count", "lead_off_count"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            null,
            "1",
        )
        cursor.use {
            if (!it.moveToFirst()) return EcgSessionCounters()
            return EcgSessionCounters(
                startedAt = if (it.isNull(0)) null else it.getString(0),
                insertedAt = if (it.isNull(1)) null else it.getString(1),
                sampleCount = it.getInt(2),
                leadOffCount = it.getInt(3),
            )
        }
    }
}

data class WatchIngestDraft(
    val payload: ParsedWatchPayload,
    val dailySummariesUpserted: Int,
    val ecgSamplesInserted: Int,
)

private data class DailyPatch(
    val day: String,
    val heartRates: MutableList<Double> = mutableListOf(),
    var sleepHours: Double? = null,
    var sleepEfficiency: Double? = null,
)

private data class DailyBaseline(
    val restingBpm: Double,
    val averageHeartRate: Double,
    val dailySteps: Int,
    val sleepHours: Double,
    val sleepEfficiency: Double,
    val hrvRmssd: Double,
    val hydrationPercent: Double,
)

private data class EcgSessionCounters(
    val startedAt: String? = null,
    val insertedAt: String? = null,
    val sampleCount: Int = 0,
    val leadOffCount: Int = 0,
)

fun WatchIngestResult.toSummary(): String {
    val alertText = activeAlert?.let { " alert=${it.alertType}/${it.severity}" }.orEmpty()
    return "batch=$batchId source=$source daily=$dailySummariesUpserted ecg=$ecgSamplesInserted cardiac=${(riskPrediction.cardiacDecompScore * 100).roundToInt()}%$alertText"
}
