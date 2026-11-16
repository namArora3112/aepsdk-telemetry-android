package com.adobe.marketing.mobile.telemetry

import android.content.Context
import org.json.JSONObject

/**
 * Telemetry configuration. Load order: asset file `telemetry-config.json` (customer-install path)
 * merged over hard defaults. The kill switch (`enabled=false`) stops capture AND purges the queue.
 */
data class TelemetryConfig(
    val enabled: Boolean = false,              // safe default: OFF when policy unknown
    val endpoint: String = "",                 // collector URL; empty = capture-only mode
    val maxQueueSize: Int = 256,               // bounded queue (validated size)
    val maxBatchBytes: Int = 32 * 1024,        // size-based sealing threshold
    val dedupeWindowMs: Long = 5000,           // Omega-style duplicate suppression window
    val flushIntervalMs: Long = 60_000
) {
    companion object {
        fun fromAssets(context: Context): TelemetryConfig {
            return try {
                val text = context.assets.open("telemetry-config.json").bufferedReader().use { it.readText() }
                fromJson(JSONObject(text))
            } catch (e: Exception) {
                TelemetryConfig() // missing/invalid config -> safe defaults (disabled)
            }
        }

        fun fromJson(json: JSONObject): TelemetryConfig = TelemetryConfig(
            enabled = json.optBoolean("enabled", false),
            endpoint = json.optString("endpoint", ""),
            maxQueueSize = json.optInt("maxQueueSize", 256),
            maxBatchBytes = json.optInt("maxBatchBytes", 32 * 1024),
            dedupeWindowMs = json.optLong("dedupeWindowMs", 5000),
            flushIntervalMs = json.optLong("flushIntervalMs", 60_000)
        )
    }
}
