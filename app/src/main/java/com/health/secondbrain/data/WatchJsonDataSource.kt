package com.health.secondbrain.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class WatchJsonDataSource {

    fun parse(rawJson: String, source: String): ParsedWatchPayload {
        val parsed = JSONTokener(rawJson).nextValue()
        val root = when (parsed) {
            is JSONObject -> parsed
            is JSONArray -> JSONObject().put("samples", parsed)
            else -> error("Watch payload must be a JSON object or array")
        }
        val receivedAt = Instant.now()
        val payloadType = when {
            root.has("samsungHealthVersion") || root.has("heartRate") || root.has("irregularRhythm") ->
                "samsung_health_export"
            root.has("samples") || root.has("ecg") -> "ecg_packet"
            else -> "watch_json"
        }
        val liveSource = normalizeLiveSource(
            source = source.ifBlank { root.optString("source") },
            fallback = when (payloadType) {
                "samsung_health_export" -> "samsung_health_live"
                "ecg_packet" -> "watch_ecg_live"
                else -> "watch_json_live"
            }
        )
        val capturedAt = parseInstantOrNull(
            root.optNullableString("captured_at")
                ?: root.optNullableString("capturedAt")
                ?: root.optNullableString("timestamp")
                ?: root.optNullableString("time")
        ) ?: receivedAt

        val heartSamples = parseHeartSamples(root, capturedAt)
        val sleepSamples = parseSleepSamples(root, capturedAt)
        val irregularRhythmEvents = parseIrregularRhythm(root, capturedAt)
        val ecgPacket = parseEcgPacket(root, liveSource, capturedAt)

        return ParsedWatchPayload(
            batchId = root.optNullableString("batch_id") ?: root.optNullableString("batchId") ?: UUID.randomUUID().toString(),
            receivedAt = receivedAt,
            capturedAt = capturedAt,
            source = liveSource,
            payloadType = payloadType,
            rawJson = root.toString(),
            heartSamples = heartSamples,
            sleepSamples = sleepSamples,
            irregularRhythmEvents = irregularRhythmEvents,
            restingBpm = root.optNumber("resting_hr_bpm") ?: root.optNumber("restingBpm"),
            hrvRmssd = root.optNumber("hrv_rmssd") ?: root.optNumber("hrvRmssd"),
            steps = root.optNumber("steps")?.toInt() ?: root.optNumber("daily_steps")?.toInt(),
            hydrationPercent = root.optNumber("hydration_percent") ?: root.optNumber("hydrationPercent"),
            ecgPacket = ecgPacket,
        )
    }

    private fun parseHeartSamples(root: JSONObject, capturedAt: Instant): List<HeartRateSample> {
        val samples = mutableListOf<HeartRateSample>()
        root.optNumber("heart_rate_bpm")?.let { samples += HeartRateSample(capturedAt, it) }
        root.optNumber("heartRateBpm")?.let { samples += HeartRateSample(capturedAt, it) }

        val heartRate = root.optJSONObject("heartRate")
        heartRate?.optNumber("latestBpm")?.let { bpm ->
            samples += HeartRateSample(parseInstantOrNull(heartRate.optNullableString("latestTime")) ?: capturedAt, bpm)
        }
        heartRate?.optJSONArray("sampleRows")?.forEachObject { row ->
            val bpm = row.optNumber("heartRate") ?: row.optNumber("bpm")
            if (bpm != null) {
                samples += HeartRateSample(
                    time = parseInstantOrNull(row.optNullableString("startTime") ?: row.optNullableString("time")) ?: capturedAt,
                    bpm = bpm,
                )
            }
        }
        root.optJSONArray("heartRateSamples")?.forEachObject { row ->
            val bpm = row.optNumber("heart_rate_bpm") ?: row.optNumber("heartRate") ?: row.optNumber("bpm")
            if (bpm != null) {
                samples += HeartRateSample(
                    time = parseInstantOrNull(row.optNullableString("time") ?: row.optNullableString("startTime")) ?: capturedAt,
                    bpm = bpm,
                )
            }
        }
        return samples
    }

    private fun parseSleepSamples(root: JSONObject, capturedAt: Instant): List<SleepSample> {
        val samples = mutableListOf<SleepSample>()
        val directHours = root.optNumber("sleep_hours") ?: root.optNumber("sleepHours")
        val directEfficiency = root.optNumber("sleep_efficiency") ?: root.optNumber("sleepEfficiency")
        if (directHours != null || directEfficiency != null) {
            samples += SleepSample(capturedAt, directHours, directEfficiency)
        }
        root.optJSONObject("sleep")?.optJSONArray("sampleRows")?.forEachObject { row ->
            val minutes = row.optNumber("durationMinutes")
            samples += SleepSample(
                time = parseInstantOrNull(row.optNullableString("endTime") ?: row.optNullableString("startTime")) ?: capturedAt,
                hours = minutes?.div(60.0),
                efficiency = row.optNumber("sleepScore") ?: row.optNumber("sleepEfficiency"),
            )
        }
        return samples
    }

    private fun parseIrregularRhythm(root: JSONObject, capturedAt: Instant): List<IrregularRhythmEvent> {
        val events = mutableListOf<IrregularRhythmEvent>()
        (root.optNullableString("irregular_rhythm_status") ?: root.optNullableString("irregularRhythmStatus"))?.let {
            events += IrregularRhythmEvent(capturedAt, it)
        }
        root.optJSONObject("irregularRhythm")?.optJSONArray("sampleRows")?.forEachObject { row ->
            val status = row.optNullableString("status")
            if (status != null) {
                events += IrregularRhythmEvent(
                    time = parseInstantOrNull(row.optNullableString("endTime") ?: row.optNullableString("startTime")) ?: capturedAt,
                    status = status,
                )
            }
        }
        return events
    }

    private fun parseEcgPacket(root: JSONObject, source: String, capturedAt: Instant): EcgPacket? {
        val ecg = root.optJSONObject("ecg")
        val sampleArray = root.optJSONArray("samples") ?: ecg?.optJSONArray("samples") ?: return null
        val samplingHz = root.optNumber("sampling_hz")
            ?: root.optNumber("samplingHz")
            ?: ecg?.optNumber("sampling_hz")
            ?: ecg?.optNumber("samplingHz")
        val startIndex = root.optNumber("sample_start_index")?.toLong()
            ?: root.optNumber("sampleStartIndex")?.toLong()
            ?: 0L
        val rows = mutableListOf<EcgSampleRow>()
        sampleArray.forEachObjectIndexed { index, row ->
            val sampleIndex = row.optNumber("sample_index")?.toLong()
                ?: row.optNumber("sampleIndex")?.toLong()
                ?: row.optNumber("index")?.toLong()
                ?: startIndex + index
            rows += EcgSampleRow(
                sampleTime = parseInstantOrNull(
                    row.optNullableString("t")
                        ?: row.optNullableString("sampleTime")
                        ?: row.optNullableString("time")
                ),
                sampleIndex = sampleIndex,
                elapsedMs = row.optNumber("elapsed_ms")?.toLong()
                    ?: row.optNumber("elapsedMs")?.toLong(),
                ecgMv = row.optNumber("mv") ?: row.optNumber("ecgMv") ?: row.optNumber("ecg_mv"),
                leadOff = row.optBooleanFlexible("leadOff") ?: row.optBooleanFlexible("lead_off") ?: false,
                rawJson = row.toString(),
            )
        }
        return EcgPacket(
            sessionId = root.optNullableString("session_id")
                ?: root.optNullableString("sessionId")
                ?: ecg?.optNullableString("session_id")
                ?: "watch-ecg-${capturedAt.toEpochMilli()}",
            batchId = root.optNullableString("batch_id") ?: root.optNullableString("batchId"),
            capturedAt = capturedAt,
            samplingHz = samplingHz,
            source = source,
            samples = rows,
        )
    }

    private fun parseInstantOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun normalizeLiveSource(source: String, fallback: String): String {
        val normalized = source.trim()
        if (normalized.isBlank() || normalized.isDemoHealthSource()) return fallback
        if (normalized == "samsung_health_data_sdk") return "samsung_health_live"
        return normalized
    }
}

data class ParsedWatchPayload(
    val batchId: String,
    val receivedAt: Instant,
    val capturedAt: Instant,
    val source: String,
    val payloadType: String,
    val rawJson: String,
    val heartSamples: List<HeartRateSample>,
    val sleepSamples: List<SleepSample>,
    val irregularRhythmEvents: List<IrregularRhythmEvent>,
    val restingBpm: Double?,
    val hrvRmssd: Double?,
    val steps: Int?,
    val hydrationPercent: Double?,
    val ecgPacket: EcgPacket?,
) {
    val latestHeartRateBpm: Double? = heartSamples.maxByOrNull { it.time }?.bpm
    val irregularRhythmDetected: Boolean =
        irregularRhythmEvents.any { it.status.equals("DETECTED", ignoreCase = true) }
}

data class HeartRateSample(val time: Instant, val bpm: Double)
data class SleepSample(val time: Instant, val hours: Double?, val efficiency: Double?)
data class IrregularRhythmEvent(val time: Instant, val status: String)

data class EcgPacket(
    val sessionId: String,
    val batchId: String?,
    val capturedAt: Instant,
    val samplingHz: Double?,
    val source: String,
    val samples: List<EcgSampleRow>,
)

data class EcgSampleRow(
    val sampleTime: Instant?,
    val sampleIndex: Long?,
    val elapsedMs: Long?,
    val ecgMv: Double?,
    val leadOff: Boolean,
    val rawJson: String,
)

fun Instant.toLocalDateString(): String = atZone(ZoneId.systemDefault()).toLocalDate().toString()

fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun JSONObject.optNumber(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.optBooleanFlexible(name: String): Boolean? {
    if (!has(name) || isNull(name)) return null
    return when (val value = opt(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1" || value.equals("yes", true)
        else -> null
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(block)
    }
}

private inline fun JSONArray.forEachObjectIndexed(block: (Int, JSONObject) -> Unit) {
    for (index in 0 until length()) {
        optJSONObject(index)?.let { block(index, it) }
    }
}
