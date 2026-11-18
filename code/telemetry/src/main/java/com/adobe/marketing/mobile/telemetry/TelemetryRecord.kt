package com.adobe.marketing.mobile.telemetry

import org.json.JSONArray
import org.json.JSONObject

/** One telemetry record (POC schema 4). */
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
    val durationMs: Long? = null,
    val recordKind: String = if (capture == "hubProjection") "event_observation" else "operation",
    val extensionVersion: String? = null,
    val contractVersion: Int? = null,
    val outcomeContract: String? = null,
    val coverage: String? = null,
    val captureMode: String? = null,
    val optimizedContext: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("recordId", recordId); put("timestamp", timestamp); put("producer", producer)
        put("eventName", eventName); put("phase", phase); put("capture", capture)
        operationId?.let { put("operationId", it) }
        requestId?.let { put("requestId", it) }
        outcome?.let { put("outcome", it) }
        outcomeMeaning?.let { put("outcomeMeaning", it) }
        durationMs?.let { put("durationMs", it) }
        put("recordKind", recordKind)
        extensionVersion?.let { put("extensionVersion", it) }
        contractVersion?.let { put("contractVersion", it) }
        outcomeContract?.let { put("outcomeContract", it) }
        coverage?.let { put("coverage", it) }
        captureMode?.let { put("captureMode", it) }
        optimizedContext?.let { put("optimizedContext", JSONObject(it)) }
    }

    /** Omega empty-payload guard equivalent: a record with no identity/meaning is not recordable. */
    fun isMeaningful(): Boolean = producer.isNotBlank() && eventName.isBlank().not()

    /** Correlated work is distinct; only repeated capture of that work is a duplicate. */
    fun dedupeKey(): String =
        "$recordKind|$producer|$eventName|$phase|$outcome|${requestId ?: operationId ?: ""}"

    companion object {
        fun fromJson(json: JSONObject): TelemetryRecord = TelemetryRecord(
            recordId = json.getString("recordId"),
            timestamp = json.getLong("timestamp"),
            producer = json.getString("producer"),
            eventName = json.getString("eventName"),
            phase = json.getString("phase"),
            capture = json.getString("capture"),
            operationId = json.optString("operationId").ifBlank { null },
            requestId = json.optString("requestId").ifBlank { null },
            outcome = json.optString("outcome").ifBlank { null },
            outcomeMeaning = json.optString("outcomeMeaning").ifBlank { null },
            durationMs = if (json.has("durationMs")) json.getLong("durationMs") else null,
            recordKind = json.optString("recordKind").ifBlank {
                if (json.getString("capture") == "hubProjection") "event_observation" else "operation"
            },
            extensionVersion = json.optString("extensionVersion").ifBlank { null },
            contractVersion = if (json.has("contractVersion")) json.getInt("contractVersion") else null,
            outcomeContract = json.optString("outcomeContract").ifBlank { null },
            coverage = json.optString("coverage").ifBlank { null },
            captureMode = json.optString("captureMode").ifBlank { null },
            optimizedContext = json.optJSONObject("optimizedContext")?.toString()
        )
    }
}

/** Batch envelope (POC schema 4). */
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
        put("schemaVersion", 4); put("catalogVersion", 2)
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
