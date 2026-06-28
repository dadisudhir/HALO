package com.health.secondbrain.data

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class RealHealthDataSource(private val fhirClient: FhirClient = FhirClient()) {

    fun importFromFhir(database: SQLiteDatabase): FhirImportResult {
        val patients = fhirClient.fetchFirstPatientBundle()
        val patientId = firstResourceId(patients)
            ?: return FhirImportResult(0, 0, 0, "FHIR server returned no patients")

        val observations = fhirClient.fetchObservationsForPatient(patientId)
        val conditions = fhirClient.fetchConditionsForPatient(patientId)

        database.beginTransaction()
        try {
            val patientCount = importBundle(database, patients, "intersystems_fhir")
            val observationCount = importBundle(database, observations, "intersystems_fhir")
            val conditionCount = importBundle(database, conditions, "intersystems_fhir")
            database.setTransactionSuccessful()
            return FhirImportResult(patientCount, observationCount, conditionCount, "real_fhir_imported")
        } finally {
            database.endTransaction()
        }
    }

    fun seedSyntheticFallback(database: SQLiteDatabase, reason: String?): FhirImportResult {
        val existing = DatabaseUtils.queryNumEntries(
            database,
            "clinical_records",
            "source = ?",
            arrayOf("synthetic_fhir_seed")
        )
        if (existing > 0) {
            return FhirImportResult(
                patientCount = countRecords(database, "Patient", "synthetic_fhir_seed"),
                observationCount = countRecords(database, "Observation", "synthetic_fhir_seed"),
                conditionCount = countRecords(database, "Condition", "synthetic_fhir_seed"),
                status = "synthetic_fhir_seeded_existing"
            )
        }

        database.beginTransaction()
        try {
            val patientCount = importBundle(database, syntheticPatientBundle(), "synthetic_fhir_seed")
            val observationCount = importBundle(database, syntheticObservationBundle(), "synthetic_fhir_seed")
            val conditionCount = importBundle(database, syntheticConditionBundle(), "synthetic_fhir_seed")
            database.setTransactionSuccessful()
            val reasonText = reason?.take(28)?.let { " after: $it" }.orEmpty()
            return FhirImportResult(
                patientCount = patientCount,
                observationCount = observationCount,
                conditionCount = conditionCount,
                status = "synthetic_fhir_seeded$reasonText"
            )
        } finally {
            database.endTransaction()
        }
    }

    private fun importBundle(database: SQLiteDatabase, bundle: JSONObject, source: String): Int {
        val entries = bundle.optJSONArray("entry") ?: JSONArray()
        var count = 0
        for (i in 0 until entries.length()) {
            val resource = entries.optJSONObject(i)?.optJSONObject("resource") ?: continue
            val type = resource.optString("resourceType", "Unknown")
            val recordId = database.insert(
                "clinical_records",
                null,
                ContentValues().apply {
                    put("resource_type", type)
                    put("source", source)
                    putStringOrNull("external_id", resource.optNullableString("id"))
                    put("recorded_at", Instant.now().toString())
                    put("raw_json", resource.toString())
                }
            )
            if (type == "Observation") {
                database.insert("clinical_observations", null, observationValues(resource, recordId))
            }
            count += 1
        }
        return count
    }

    private fun countRecords(database: SQLiteDatabase, type: String, source: String): Int =
        DatabaseUtils.queryNumEntries(
            database,
            "clinical_records",
            "resource_type = ? AND source = ?",
            arrayOf(type, source)
        ).toInt()

    private fun firstResourceId(bundle: JSONObject): String? {
        val entries = bundle.optJSONArray("entry") ?: return null
        for (i in 0 until entries.length()) {
            val id = entries.optJSONObject(i)
                ?.optJSONObject("resource")
                ?.optString("id")
                ?.takeIf { it.isNotBlank() }
            if (id != null) return id
        }
        return null
    }

    private fun observationValues(resource: JSONObject, clinicalRecordId: Long): ContentValues {
        val code = resource.optJSONObject("code")
        val coding = code?.optJSONArray("coding")?.optJSONObject(0)
        val quantity = resource.optJSONObject("valueQuantity")
        return ContentValues().apply {
            put("clinical_record_id", clinicalRecordId)
            putStringOrNull("code_system", coding?.optNullableString("system"))
            putStringOrNull("code", coding?.optNullableString("code"))
            putStringOrNull("display", coding?.optNullableString("display") ?: code?.optNullableString("text"))
            putStringOrNull("value_text", resource.optNullableString("valueString"))
            if (quantity != null && quantity.has("value")) put("value_number", quantity.optDouble("value"))
            putStringOrNull("unit", quantity?.optNullableString("unit"))
            putStringOrNull(
                "observed_at",
                resource.optNullableString("effectiveDateTime") ?: resource.optNullableString("issued")
            )
            put("raw_json", resource.toString())
        }
    }

    private fun ContentValues.putStringOrNull(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun syntheticPatientBundle(): JSONObject =
        bundleOf(
            JSONObject()
                .put("resourceType", "Patient")
                .put("id", "halo-synthetic-patient")
                .put(
                    "name",
                    JSONArray().put(
                        JSONObject()
                            .put("family", "Demo")
                            .put("given", JSONArray().put("HALO"))
                    )
                )
                .put("gender", "male")
                .put("birthDate", "1998-06-27")
        )

    private fun syntheticObservationBundle(): JSONObject =
        bundleOf(
            observation("obs-hr", "8867-4", "Heart rate", 92.0, "beats/minute"),
            observation("obs-rhr", "40443-4", "Resting heart rate", 66.0, "beats/minute"),
            observation("obs-spo2", "2708-6", "Oxygen saturation", 97.0, "%"),
            observation("obs-glucose", "2339-0", "Glucose", 104.0, "mg/dL"),
            observation("obs-systolic", "8480-6", "Systolic blood pressure", 122.0, "mmHg"),
            observation("obs-creatinine", "2160-0", "Creatinine", 0.92, "mg/dL")
        )

    private fun syntheticConditionBundle(): JSONObject =
        bundleOf(
            condition("cond-sleep", "G47.9", "Sleep disorder, unspecified"),
            condition("cond-palpitations", "R00.2", "Palpitations"),
            condition("cond-dehydration-risk", "E86.0", "Dehydration")
        )

    private fun observation(
        id: String,
        code: String,
        display: String,
        value: Double,
        unit: String,
    ): JSONObject =
        JSONObject()
            .put("resourceType", "Observation")
            .put("id", id)
            .put("status", "final")
            .put(
                "code",
                JSONObject().put(
                    "coding",
                    JSONArray().put(
                        JSONObject()
                            .put("system", "http://loinc.org")
                            .put("code", code)
                            .put("display", display)
                    )
                )
            )
            .put("subject", JSONObject().put("reference", "Patient/halo-synthetic-patient"))
            .put("effectiveDateTime", Instant.now().toString())
            .put(
                "valueQuantity",
                JSONObject()
                    .put("value", value)
                    .put("unit", unit)
            )

    private fun condition(id: String, code: String, display: String): JSONObject =
        JSONObject()
            .put("resourceType", "Condition")
            .put("id", id)
            .put(
                "code",
                JSONObject().put(
                    "coding",
                    JSONArray().put(
                        JSONObject()
                            .put("system", "http://hl7.org/fhir/sid/icd-10-cm")
                            .put("code", code)
                            .put("display", display)
                    )
                )
            )
            .put("subject", JSONObject().put("reference", "Patient/halo-synthetic-patient"))
            .put("recordedDate", Instant.now().toString())

    private fun bundleOf(vararg resources: JSONObject): JSONObject {
        val entries = JSONArray()
        resources.forEach { resource ->
            entries.put(JSONObject().put("resource", resource))
        }
        return JSONObject()
            .put("resourceType", "Bundle")
            .put("type", "searchset")
            .put("entry", entries)
    }
}
