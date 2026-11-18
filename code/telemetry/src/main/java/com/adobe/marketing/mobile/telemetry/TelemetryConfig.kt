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
    val flushIntervalMs: Long = 60_000,
    val optimizedPayload: Boolean = false,
    val orgId: String = "",
    val captureDirectHooks: Boolean = true
) {
    init {
        if (optimizedPayload) {
            require(maxQueueSize in 1..1024 && maxBatchBytes in 1024..262144)
            require(dedupeWindowMs in 0..3_600_000 && flushIntervalMs in 1000..3_600_000)
            require(orgId.length <= 128 && orgId.matches(Regex("[A-Za-z0-9]+@AdobeOrg")))
            if (endpoint.isNotEmpty()) {
                require(endpoint.length <= 2048)
                val uri = java.net.URI(endpoint)
                require(uri.scheme == "https" && uri.host in setOf("edge-int.adobedc.net", "edge.adobedc.net"))
                require(uri.userInfo == null && uri.fragment == null && uri.path == "/ee/v1/interact")
            }
        }
    }

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
            flushIntervalMs = json.optLong("flushIntervalMs", 60_000),
            optimizedPayload = json.optBoolean("optimizedPayload", false),
            orgId = json.optString("orgId", ""),
            captureDirectHooks = json.optBoolean("captureDirectHooks", true)
        )
    }
}
