package com.health.secondbrain.data

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.ZoneId

class FakeHealthDataSource {

    fun seedComponentsIfEmpty(database: SQLiteDatabase) {
        if (DatabaseUtils.queryNumEntries(database, "health_components") > 0) return

        val components = listOf(
            component("heart", "Heart", "Cardiovascular system", "body_system", "organs/heart-organ.svg", "#FF5C7A", 10),
            component("sleep", "Sleep", "Rest & recovery", "health_domain", "organs/thymus-gland-organ.svg", "#8FA7FF", 20),
            component("liver", "Liver", "Metabolic load", "body_system", "organs/stomach-organ.svg", "#F1B45A", 30),
            component("lungs", "Lungs", "Respiratory", "body_system", "organs/lungs-organ.svg", "#71D5E4", 40),
            component("kidney", "Kidney", "Renal", "body_system", "organs/kidney-organ.svg", "#63D493", 50),
            component("brain", "Brain", "Cognitive", "body_system", "organs/brain-organ.svg", "#C792EA", 60),
            component("gut", "Gut", "Digestive system", "body_system", "organs/intestine-organ.svg", "#B8D66B", 70),
        )

        database.beginTransaction()
        try {
            components.forEach { values ->
                database.insertWithOnConflict(
                    "health_components",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun seedIfEmpty(database: SQLiteDatabase) {
        if (DatabaseUtils.queryNumEntries(database, "daily_health_summaries") > 0) return

        // --- Cardio + Sleep demo narrative (calibrated against HealthRiskEngine) ---
        // Staged onset within the last 7 days, anchored relative to today so the
        // story always reads as "this week":
        //   7d ago  sleep efficiency 89->77, sleep hours 7.6->6.8  (sleep degrades first)
        //   6d ago  HRV 58->44                                     (autonomic stress next)
        //   4d ago  resting 61->70, avg HR 71->80, steps 9300->7600 (cardiac load last)
        // Target risk scores: arrhythmia ~0.80, cardiac ~0.74, sleep ~0.73.
        val today = LocalDate.now(ZoneId.systemDefault())
        database.beginTransaction()
        try {
            for (daysAgo in 29 downTo 0) {
                val day = today.minusDays(daysAgo.toLong())

                var resting = 61.0
                var averageHr = 71.0
                var steps = 9300
                var sleepHours = 7.6
                var sleepEfficiency = 89.0
                var hrv = 58.0
                var hydration = 71.0

                if (daysAgo <= 7) {            // sleep disruption begins
                    sleepEfficiency = 77.0
                    sleepHours = 6.8
                    hydration = 67.0
                }
                if (daysAgo <= 6) {            // HRV collapse follows
                    hrv = 44.0
                }
                if (daysAgo <= 4) {            // cardiovascular strain last
                    resting = 70.0
                    averageHr = 80.0
                    steps = 7600
                }

                database.insertWithOnConflict(
                    "daily_health_summaries",
                    null,
                    ContentValues().apply {
                        put("day", day.toString())
                        put("resting_bpm", resting)
                        put("average_heart_rate", averageHr)
                        put("daily_steps", steps)
                        put("sleep_hours", sleepHours)
                        put("sleep_efficiency", sleepEfficiency)
                        put("hrv_rmssd", hrv)
                        put("hydration_percent", hydration)
                        put("source", "cardio_sleep_demo")
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun component(
        id: String,
        displayName: String,
        systemLabel: String,
        type: String,
        iconAsset: String,
        accentHex: String,
        sortOrder: Int,
    ): ContentValues =
        ContentValues().apply {
            put("id", id)
            put("display_name", displayName)
            put("system_label", systemLabel)
            put("component_type", type)
            put("icon_asset", iconAsset)
            put("accent_hex", accentHex)
            put("sort_order", sortOrder)
            put("enabled", 1)
        }
}
