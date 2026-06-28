package com.health.secondbrain.data

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin

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

        val today = LocalDate.now(ZoneId.systemDefault())
        database.beginTransaction()
        try {
            for (daysAgo in 29 downTo 0) {
                val day = today.minusDays(daysAgo.toLong())
                val index = 29 - daysAgo
                val lateRecoveryPenalty = if (daysAgo <= 5) 1.0 else 0.0
                val wave = sin(index / 2.6)
                val resting = 59.5 + index * 0.10 + lateRecoveryPenalty * 3.2 + wave * 0.9
                val averageHr = 76.0 + index * 0.08 + lateRecoveryPenalty * 2.4 + wave * 1.3
                val steps = (8200 + wave * 900 - lateRecoveryPenalty * 1700 + (index % 4) * 260).toInt()
                val sleepHours = 7.45 - lateRecoveryPenalty * 0.72 - (index % 5) * 0.04
                val sleepEfficiency = 88.0 - lateRecoveryPenalty * 5.5 - (index % 4) * 0.8
                val hrv = 55.0 - index * 0.14 - lateRecoveryPenalty * 5.0 + wave * 1.6
                val hydration = 71.0 - lateRecoveryPenalty * 6.5 + (index % 3) * 1.2

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
                        put("source", "fake_periodic_seed")
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
