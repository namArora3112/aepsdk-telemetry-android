package com.adobe.marketing.mobile.telemetry

import org.json.JSONArray
import org.json.JSONObject

/** One telemetry record (schema 3). */
data class TelemetryRecord(
    val recordId: String,
    val timestamp: Long,
    val producer: String,                 // e.g. "com.adobe.messaging", "com.adobe.module.core"
    val eventName: String,                // catalog name, e.g. "sdk.messaging.refresh"
    val phase: String,                    // "started" | "completed" | "event"
    val capture: String,                  // "coreBootstrap" | "hubProjection" | "directHook"
    val operationId: String? = null,
    val requestId: String? = null,
    val outcome: String? = null,          // "success" | "failure" | "rejected" | "ignored"
    val outcomeMeaning: String? = null,
    val durationMs: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("recordId", recordId); put("timestamp", timestamp); put("producer", producer)
        put("eventName", eventName); put("phase", phase); put("capture", capture)
        operationId?.let { put("operationId", it) }
        requestId?.let { put("requestId", it) }
        outcome?.let { put("outcome", it) }
        outcomeMeaning?.let { put("outcomeMeaning", it) }
        durationMs?.let { put("durationMs", it) }
    }

    /** Omega empty-payload guard equivalent: a record with no identity/meaning is not recordable. */
    fun isMeaningful(): Boolean = producer.isNotBlank() && eventName.isBlank().not()

    /** Dedupe key: Omega-style type + stable content (recordId/operationId excluded). */
    fun dedupeKey(): String = "$producer|$eventName|$phase|$outcome"
}

/** Batch envelope (schema 3). */
data class TelemetryBatch(
    val batchId: String,
    val createdAtMs: Long,
    val installationId: String,
    val appPackage: String,
    val appVersion: String,
    val sdkVersions: Map<String, String>,
    val records: List<TelemetryRecord>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 3); put("catalogVersion", 1)
        put("batchId", batchId)
        put("createdAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(createdAtMs)))
        put("product", "aep-mobile-sdk"); put("environment", "stage")
        put("context", JSONObject().apply {
            put("installationId", installationId); put("platform", "android")
            put("application", JSONObject().apply { put("package", appPackage); put("version", appVersion) })
            put("sdkVersions", JSONArray().apply {
                sdkVersions.forEach { (n, v) -> put(JSONObject().apply { put("name", n); put("version", v) }) }
            })
        })
        put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })
    }
}
