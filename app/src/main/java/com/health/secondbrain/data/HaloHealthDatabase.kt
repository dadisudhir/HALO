package com.health.secondbrain.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HaloHealthDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createRealtimeWatchTables(db)
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS health_components (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                system_label TEXT NOT NULL,
                component_type TEXT NOT NULL,
                icon_asset TEXT NOT NULL,
                accent_hex TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_health_summaries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL UNIQUE,
                resting_bpm REAL NOT NULL,
                average_heart_rate REAL NOT NULL,
                daily_steps INTEGER NOT NULL,
                sleep_hours REAL NOT NULL,
                sleep_efficiency REAL NOT NULL,
                hrv_rmssd REAL NOT NULL,
                hydration_percent REAL NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clinical_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                resource_type TEXT NOT NULL,
                source TEXT NOT NULL,
                external_id TEXT,
                recorded_at TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS clinical_observations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                clinical_record_id INTEGER,
                code_system TEXT,
                code TEXT,
                display TEXT,
                value_text TEXT,
                value_number REAL,
                unit TEXT,
                observed_at TEXT,
                raw_json TEXT NOT NULL,
                FOREIGN KEY(clinical_record_id) REFERENCES clinical_records(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS user_inputs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                input_type TEXT NOT NULL,
                value_text TEXT,
                value_number REAL,
                unit TEXT,
                occurred_at TEXT NOT NULL,
                raw_json TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS feature_vectors (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                generated_at TEXT NOT NULL,
                features_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS risk_predictions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                feature_vector_id INTEGER,
                generated_at TEXT NOT NULL,
                arrhythmia_score REAL NOT NULL,
                cardiac_decomp_score REAL NOT NULL,
                sleep_impairment_score REAL NOT NULL,
                raw_json TEXT NOT NULL,
                FOREIGN KEY(feature_vector_id) REFERENCES feature_vectors(id)
            )
            """.trimIndent()
        )
    }

    private fun createRealtimeWatchTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS watch_json_batches (
                id TEXT PRIMARY KEY,
                received_at TEXT NOT NULL,
                source TEXT NOT NULL,
                payload_type TEXT NOT NULL,
                raw_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ecg_sessions (
                session_id TEXT PRIMARY KEY,
                started_at TEXT,
                ended_at TEXT,
                sampling_hz REAL,
                source TEXT NOT NULL,
                inserted_at TEXT NOT NULL,
                last_received_at TEXT NOT NULL,
                sample_count INTEGER NOT NULL DEFAULT 0,
                lead_off_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ecg_samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                received_at TEXT NOT NULL,
                sample_time TEXT,
                sample_index INTEGER,
                elapsed_ms INTEGER,
                sampling_hz REAL,
                ecg_mv REAL,
                lead_off INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL,
                batch_id TEXT,
                raw_json TEXT NOT NULL,
                FOREIGN KEY(session_id) REFERENCES ecg_sessions(session_id),
                FOREIGN KEY(batch_id) REFERENCES watch_json_batches(id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS active_alerts (
                id TEXT PRIMARY KEY,
                alert_type TEXT NOT NULL,
                severity TEXT NOT NULL,
                created_at TEXT NOT NULL,
                source TEXT NOT NULL,
                evidence_json TEXT NOT NULL,
                acknowledged INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            createRealtimeWatchTables(db)
            return
        }
        db.execSQL("DROP TABLE IF EXISTS risk_predictions")
        db.execSQL("DROP TABLE IF EXISTS feature_vectors")
        db.execSQL("DROP TABLE IF EXISTS user_inputs")
        db.execSQL("DROP TABLE IF EXISTS clinical_observations")
        db.execSQL("DROP TABLE IF EXISTS clinical_records")
        db.execSQL("DROP TABLE IF EXISTS daily_health_summaries")
        db.execSQL("DROP TABLE IF EXISTS health_components")
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "halo_health.db"
        private const val DATABASE_VERSION = 3
    }
}
