package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Wiki 4106177746 XDM shape. Context is captured with the durable record, not reconstructed later. */
internal object OptimizedPayload {
    private val constantName = Regex("[A-Za-z0-9_.:-]{1,160}")

    fun fingerprint(context: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(JSONObject(context)).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    fun adoption(record: TelemetryRecord): JSONObject =
        envelope("sdk.adoption", "adoption-${record.recordId}", record.timestamp, JSONObject(requireNotNull(record.optimizedContext)))

    fun usage(record: TelemetryRecord): JSONObject {
        require(constantName.matches(record.producer) && constantName.matches(record.eventName))
        val context = JSONObject(requireNotNull(record.optimizedContext))
        val apiEventType = when {
            record.recordKind == "api_invocation" -> "API_CALL"
            record.recordKind == "api_outcome" && record.outcome in setOf("failure", "rejected", "timed_out") -> "API_FAIL"
            else -> "TIMELINE"
        }
        val suffix = if (record.recordKind == "api_outcome") ".${record.outcome ?: "unknown"}"
            else if (record.recordKind == "operation") ".${record.phase}" else ""
        val event = JSONObject()
            .put("apiEventType", apiEventType).put("timestamp", record.timestamp)
            .put("extension", record.producer).put("apiName", record.eventName + suffix)
        if (apiEventType == "API_FAIL") {
            event.put("errorCode", record.outcome)
                .put("errorName", record.outcomeContract ?: "sdk_outcome")
        }
        val tenant = JSONObject().put("metadata", JSONObject().put("uuid", context.getJSONObject("metadata").getString("uuid")))
            .put("orgId", context.getString("orgId"))
            .put("telemetry", JSONObject().put("events", JSONArray().put(event)))
        return envelope("sdk.usage", record.recordId, record.timestamp, tenant)
    }

    private fun envelope(type: String, id: String, time: Long, tenant: JSONObject): JSONObject {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
        val xdm = JSONObject().put("_id", id).put("eventType", type).put("timestamp", timestamp)
            .put("_cjmstage", tenant)
        return JSONObject().put("events", JSONArray().put(JSONObject().put("xdm", xdm)))
            .put("xdm", JSONObject().put("identityMap", JSONObject()))
    }
}

internal enum class DeliveryResult { ACCEPTED, RETRYABLE, REJECTED }

/** One outstanding request. A failed adoption never permits usage of that context. */
internal class OptimizedSender(
    private val post: (JSONObject, (DeliveryResult) -> Unit) -> Unit
) {
    fun send(
        records: List<TelemetryRecord>,
        currentFingerprint: () -> String?,
        markAdopted: (String) -> Unit,
        permitted: () -> Boolean,
        acknowledged: (TelemetryRecord) -> Unit,
        rejected: (TelemetryRecord) -> Unit,
        done: (DeliveryResult) -> Unit
    ) {
        val iterator = records.iterator()
        val advanceLock = Any()
        var advancing = false
        var requested = false
        lateinit var next: () -> Unit
        fun advance() {
            synchronized(advanceLock) {
                requested = true
                if (advancing) return
                advancing = true
            }
            while (true) {
                synchronized(advanceLock) {
                    if (!requested) { advancing = false; return }
                    requested = false
                }
                next()
            }
        }
        next = nextRecord@ {
            if (!permitted() || !iterator.hasNext()) {
                done(DeliveryResult.ACCEPTED)
                return@nextRecord
            }
            val record = iterator.next()
            val context = record.optimizedContext
            if (context == null) {
                rejected(record)
                advance()
                return@nextRecord
            }
            val payload = try { OptimizedPayload.usage(record) } catch (_: Exception) {
                rejected(record)
                advance()
                return@nextRecord
            }
            fun sendUsage() {
                if (!permitted()) { done(DeliveryResult.ACCEPTED); return }
                post(payload) { result ->
                    if (!permitted()) { done(result); return@post }
                    when (result) {
                        DeliveryResult.ACCEPTED -> { acknowledged(record); advance() }
                        DeliveryResult.REJECTED -> { rejected(record); done(result) }
                        DeliveryResult.RETRYABLE -> done(result)
                    }
                }
            }
            val fingerprint = OptimizedPayload.fingerprint(context)
            if (currentFingerprint() == fingerprint) {
                sendUsage()
            } else {
                post(OptimizedPayload.adoption(record)) { result ->
                    if (!permitted()) { done(result); return@post }
                    when (result) {
                        DeliveryResult.ACCEPTED -> { markAdopted(fingerprint); sendUsage() }
                        DeliveryResult.REJECTED -> { rejected(record); done(result) }
                        DeliveryResult.RETRYABLE -> done(result)
                    }
                }
            }
        }
        advance()
    }
}
