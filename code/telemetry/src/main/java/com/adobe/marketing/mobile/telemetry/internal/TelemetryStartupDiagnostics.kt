package com.adobe.marketing.mobile.telemetry.internal

import android.app.Application
import android.content.pm.ApplicationInfo
import org.json.JSONObject

internal class TelemetryStartupDiagnostics(
    private val enabled: Boolean,
    private val output: (String) -> Unit = { android.util.Log.w(TAG, it); Unit }
) {
    fun milestone(name: String, details: String = "") = emit {
        "\n================ AEP TELEMETRY POC ================\n" +
            "$name; $details; logged_at_ms=${System.currentTimeMillis()}; " +
            "monotonic_ns=${System.nanoTime()}; thread=${Thread.currentThread().name}\n" +
            "=================================================="
    }

    fun persisted(id: Long, event: AutoEvent, total: () -> Int) = emit {
        "AUTO_EVENT_PERSISTED; row_id=$id; queue_rows=${total()}; " +
            "persisted_at_ms=${System.currentTimeMillis()}; event=${safeEvent(event)}"
    }

    fun snapshot(queue: EventQueue) {
        if (!enabled) return
        runCatching {
            val total = queue.size()
            val rows = queue.peekChunk(20)
            milestone("AUTO_QUEUE_SNAPSHOT", "database=adobe.telemetry.autoqueue; table=events; " +
                "total_rows=$total; shown=${rows.size}; order=oldest_first; session_ids=omitted")
            rows.forEach { row ->
                emit { "AUTO_QUEUE_ROW; row_id=${row.id}; event=${safeEvent(row.event)}" }
            }
        }
    }

    private fun safeEvent(event: AutoEvent): JSONObject = JSONObject()
        .put("name", event.name)
        .put("captured_at_ms", event.timestampMs)
        .put("properties", JSONObject(event.properties.filterKeys { it in SAFE_PROPERTIES }))

    private fun emit(message: () -> String) {
        if (enabled) runCatching { output(message()) }
    }

    companion object {
        private const val TAG = "AEPTelemetryPOC"
        private val SAFE_PROPERTIES = setOf(
            "cold_start_ms", "version_code", "previous_version_code", "screen", "exception_class"
        )

        @Volatile var current = TelemetryStartupDiagnostics(false)
            private set

        fun configure(application: Application) {
            val debug = runCatching {
                application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            }.getOrDefault(false)
            current = TelemetryStartupDiagnostics(debug)
        }
    }
}
