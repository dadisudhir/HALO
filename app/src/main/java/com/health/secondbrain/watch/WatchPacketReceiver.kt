package com.health.secondbrain.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.health.secondbrain.data.HaloHealthRepository
import com.health.secondbrain.data.toSummary
import com.health.secondbrain.notify.DemoNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

@Suppress("DEPRECATION")
class WatchPacketReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val action = intent.action.orEmpty()
                val rawJson = when (action) {
                    ACTION_WATCH_PACKET -> readWatchJson(intent)
                    ACTION_MINIBENCH_ECG_PACKET -> readMiniBenchEcgJson(intent)
                    else -> error("Unsupported watch packet action: $action")
                }
                val source = intent.getStringExtra("source").orEmpty().ifBlank {
                    if (action == ACTION_MINIBENCH_ECG_PACKET) "watch_ecg_live" else ""
                }
                val result = HaloHealthRepository(context.applicationContext).ingestWatchJson(rawJson, source)
                result.activeAlert?.let { DemoNotifications(context.applicationContext).sendAlert(it) }
                Log.i(TAG, "Ingested watch packet: ${result.toSummary()}")
            } catch (error: Throwable) {
                Log.e(TAG, "Watch packet ingest failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun readWatchJson(intent: Intent): String {
        val raw = intent.getStringExtra("raw_json")
            ?: intent.getStringExtra("payload")
            ?: intent.getStringExtra("json")
        if (!raw.isNullOrBlank()) return raw
        intent.getStringExtra("samples_json")?.takeIf { it.isNotBlank() }?.let { samplesJson ->
            val samples = JSONTokener(samplesJson).nextValue().let { value ->
                when (value) {
                    is JSONArray -> value
                    is JSONObject -> value.optJSONArray("samples") ?: JSONArray().put(value)
                    else -> JSONArray()
                }
            }
            return JSONObject()
                .put("source", intent.getStringExtra("source") ?: "watch_json_live")
                .put("captured_at", intent.getStringExtra("captured_at"))
                .put("samples", samples)
                .toString()
        }
        return extrasToJson(intent.extras).toString()
    }

    private fun readMiniBenchEcgJson(intent: Intent): String {
        val samplesJson = intent.getStringExtra("samples_json")
            ?: intent.getStringExtra("samples_b64")?.let { encoded ->
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }
            ?: "[]"
        val samples = JSONTokener(samplesJson).nextValue().let { value ->
            when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("samples") ?: JSONArray().put(value)
                else -> JSONArray()
            }
        }
        return JSONObject()
            .put("source", intent.getStringExtra("source") ?: "watch_ecg_live")
            .put("captured_at", intent.getStringExtra("captured_at"))
            .put("session_id", intent.getStringExtra("session_id"))
            .put("batch_id", intent.getStringExtra("batch_id"))
            .put("sampling_hz", intent.extras?.get("sampling_hz"))
            .put("sample_start_index", intent.extras?.get("sample_start_index"))
            .put("samples", samples)
            .toString()
    }

    private fun extrasToJson(extras: Bundle?): JSONObject {
        val root = JSONObject()
        extras?.keySet()?.forEach { key ->
            val value = extras.get(key)
            if (value != null) root.put(key, value)
        }
        return root
    }

    companion object {
        const val ACTION_WATCH_PACKET = "com.health.secondbrain.WATCH_PACKET"
        const val ACTION_MINIBENCH_ECG_PACKET = "org.pytorch.minibench.ECG_PACKET"
        private const val TAG = "WatchPacketReceiver"
    }
}
