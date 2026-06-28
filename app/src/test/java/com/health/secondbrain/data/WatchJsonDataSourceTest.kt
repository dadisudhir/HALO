package com.health.secondbrain.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchJsonDataSourceTest {
    private val parser = WatchJsonDataSource()

    @Test
    fun parsesNormalizedWatchSamplesAsLiveDailySignals() {
        val payload = parser.parse(
            rawJson = """
                {
                  "source":"watch_json_live",
                  "captured_at":"2026-06-28T10:00:00Z",
                  "samples":[{
                    "timestamp":"2026-06-28T10:00:00Z",
                    "heart_rate_bpm":132,
                    "resting_hr_bpm":70,
                    "hrv_rmssd":44,
                    "steps":7600,
                    "sleep_hours":6.8,
                    "sleep_efficiency":77,
                    "event":"exercise_spike"
                  }]
                }
            """.trimIndent(),
            source = "watch_json_live",
        )

        assertEquals("watch_json", payload.payloadType)
        assertEquals("watch_json_live", payload.source)
        assertEquals(132.0, payload.latestHeartRateBpm)
        assertEquals(44.0, payload.hrvRmssd)
        assertEquals(7600, payload.steps)
        assertEquals(6.8, payload.sleepSamples.single().hours)
        assertEquals(77.0, payload.sleepSamples.single().efficiency)
        assertNull(payload.ecgPacket)
    }

    @Test
    fun parsesMiniBenchEcgSamplesAsEcgPacket() {
        val payload = parser.parse(
            rawJson = """
                {
                  "source":"watch_ecg_live",
                  "captured_at":"2026-06-28T10:00:00Z",
                  "session_id":"ecg_demo_001",
                  "sampling_hz":500,
                  "samples":[
                    {"index":0,"elapsedMs":0,"ecgMv":0.02,"leadOff":0},
                    {"index":1,"elapsedMs":2,"ecgMv":0.08,"leadOff":0}
                  ]
                }
            """.trimIndent(),
            source = "watch_ecg_live",
        )

        val ecg = assertNotNull(payload.ecgPacket)
        assertEquals("ecg_packet", payload.payloadType)
        assertEquals("ecg_demo_001", ecg.sessionId)
        assertEquals(500.0, ecg.samplingHz)
        assertEquals(2, ecg.samples.size)
        assertEquals(1L, ecg.samples[1].sampleIndex)
        assertEquals(0.08, ecg.samples[1].ecgMv)
    }
}
