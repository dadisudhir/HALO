package com.health.secondbrain.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Thin wrapper around Health Connect. The UI today renders demo data from
 * OrganRegistry; this class is the seam where live S25 Ultra Health Connect
 * records get pulled in for the live demo.
 */
class HealthConnectRepository(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
    )

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
    }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun readRestingHrLast7Days(): List<Double> {
        val c = client ?: return emptyList()
        val end = Instant.now()
        val start = end.minus(7, ChronoUnit.DAYS)
        val res = c.readRecords(
            ReadRecordsRequest(
                recordType = RestingHeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )
        return res.records.map { it.beatsPerMinute.toDouble() }
    }
}
