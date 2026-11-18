package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.DeliveryResult
import com.adobe.marketing.mobile.telemetry.internal.OptimizedPayload
import com.adobe.marketing.mobile.telemetry.internal.OptimizedSender
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptimizedPayloadTest {
    private val context = """{"metadata":{"uuid":"install-1","application":{"version":"1"},"permissions":[]},"sdkVersions":[{"name":"com.adobe.module.core","version":"catalog"}],"orgId":"TEST@AdobeOrg"}"""
    private fun record(id: String = "record-1", snapshot: String? = context) =
        TelemetryRecord(id, 1000, "com.adobe.messaging", "refreshInAppMessages.requestObserved", "event",
            "hubProjection", operationId = "PRIVATE_OPERATION", requestId = "PRIVATE_HUB_EVENT",
            recordKind = "event_observation", optimizedContext = snapshot)
    private fun type(payload: JSONObject) = payload.getJSONArray("events").getJSONObject(0).getJSONObject("xdm").getString("eventType")
    private fun tenant(payload: JSONObject) = payload.getJSONArray("events").getJSONObject(0).getJSONObject("xdm").getJSONObject("_cjmstage")

    @Test fun usageIsOneMinimalTimelineAndDoesNotExportInternalIdsOrMetadata() {
        val payload = OptimizedPayload.usage(record())
        val data = tenant(payload)
        val events = data.getJSONObject("telemetry").getJSONArray("events")
        assertEquals(1, payload.getJSONArray("events").length())
        assertEquals(1, events.length())
        assertEquals("TIMELINE", events.getJSONObject(0).getString("apiEventType"))
        assertEquals(setOf("uuid"), data.getJSONObject("metadata").keys().asSequence().toSet())
        assertEquals(setOf("metadata", "orgId", "telemetry"), data.keys().asSequence().toSet())
        assertEquals(0, payload.getJSONObject("xdm").getJSONObject("identityMap").length())
        assertFalse(payload.toString().contains("PRIVATE_"))
        assertFalse(payload.toString().contains("permissions"))
        assertFalse(payload.toString().contains("sdkVersions"))
    }

    @Test fun durableContextKeepsOriginalOrgVersionsAndAdoptionTime() {
        val restored = TelemetryRecord.fromJson(record().toJson())
        assertEquals(JSONObject(context).getString("orgId"), tenant(OptimizedPayload.adoption(restored)).getString("orgId"))
        assertEquals("catalog", tenant(OptimizedPayload.adoption(restored)).getJSONArray("sdkVersions").getJSONObject(0).getString("version"))
        val adoption = OptimizedPayload.adoption(restored).getJSONArray("events").getJSONObject(0).getJSONObject("xdm")
        val usage = OptimizedPayload.usage(restored).getJSONArray("events").getJSONObject(0).getJSONObject("xdm")
        assertEquals(usage.getString("timestamp"), adoption.getString("timestamp"))
        assertEquals(OptimizedPayload.usage(restored).toString(), OptimizedPayload.usage(restored).toString())
    }

    @Test fun onlyExplicitEntriesAreApiCallsAndUnknownOutcomesAreNotFailures() {
        fun event(record: TelemetryRecord) = tenant(OptimizedPayload.usage(record)).getJSONObject("telemetry").getJSONArray("events").getJSONObject(0)
        assertEquals("API_CALL", event(record().copy(recordKind = "api_invocation")).getString("apiEventType"))
        assertEquals("TIMELINE", event(record().copy(recordKind = "api_outcome", outcome = "unknown")).getString("apiEventType"))
        assertEquals("API_FAIL", event(record().copy(recordKind = "api_outcome", outcome = "failure", outcomeContract = "cache_callback.v1")).getString("apiEventType"))
    }

    @Test fun fingerprintsIgnoreObjectKeyOrderButIncludeOrgAndSdkVersions() {
        val reversed = """{"orgId":"TEST@AdobeOrg","sdkVersions":[{"version":"catalog","name":"com.adobe.module.core"}],"metadata":{"permissions":[],"application":{"version":"1"},"uuid":"install-1"}}"""
        assertEquals(OptimizedPayload.fingerprint(context), OptimizedPayload.fingerprint(reversed))
        assertNotEquals(OptimizedPayload.fingerprint(context), OptimizedPayload.fingerprint(context.replace("TEST@", "OTHER@")))
        assertNotEquals(OptimizedPayload.fingerprint(context), OptimizedPayload.fingerprint(context.replace("catalog", "new-version")))
    }

    @Test fun sendsAdoptionThenIndividualUsageAndReadoptsWhenContextChanges() {
        var fingerprint: String? = null
        val calls = mutableListOf<String>()
        val ack = mutableListOf<String>()
        OptimizedSender { payload, callback -> calls += type(payload); callback(DeliveryResult.ACCEPTED) }
            .send(listOf(record("1"), record("2"), record("3", context.replace("catalog", "next"))),
                { fingerprint }, { fingerprint = it }, { true }, { ack += it.recordId }, { fail("unexpected drop") }, {})
        assertEquals(listOf("sdk.adoption", "sdk.usage", "sdk.usage", "sdk.adoption", "sdk.usage"), calls)
        assertEquals(listOf("1", "2", "3"), ack)
    }

    @Test fun failedAdoptionBlocksUsageAndKeepsRecordForRetry() {
        val calls = mutableListOf<String>()
        var result: DeliveryResult? = null
        OptimizedSender { payload, callback -> calls += type(payload); callback(DeliveryResult.RETRYABLE) }
            .send(listOf(record()), { null }, { fail("not acknowledged") }, { true },
                { fail("usage cannot be removed") }, { fail("retryable is not dropped") }, { result = it })
        assertEquals(listOf("sdk.adoption"), calls)
        assertEquals(DeliveryResult.RETRYABLE, result)
    }

    @Test fun usageRetryReusesAcknowledgedAdoptionAndStableUsageId() {
        var fingerprint: String? = null
        val calls = mutableListOf<JSONObject>()
        val sender = OptimizedSender { payload, callback ->
            calls += payload
            callback(if (calls.size == 2) DeliveryResult.RETRYABLE else DeliveryResult.ACCEPTED)
        }
        fun send() = sender.send(listOf(record()), { fingerprint }, { fingerprint = it }, { true }, {}, { fail("drop") }, {})
        send()
        send()
        assertEquals(listOf("sdk.adoption", "sdk.usage", "sdk.usage"), calls.map(::type))
        assertEquals(calls[1].toString(), calls[2].toString())
    }

    @Test fun optOutDuringAdoptionPreventsUsageAndAcknowledgement() {
        var permitted = true
        var completion: ((DeliveryResult) -> Unit)? = null
        var posts = 0
        var done = false
        OptimizedSender { _, callback -> posts++; completion = callback }
            .send(listOf(record()), { null }, { fail("stale callback") }, { permitted },
                { fail("stale ack") }, { fail("stale drop") }, { done = true })
        permitted = false
        completion!!(DeliveryResult.ACCEPTED)
        assertEquals(1, posts)
        assertTrue(done)
    }

    @Test fun legacyRecordsAreDroppedRatherThanAssignedCurrentContext() {
        var dropped = false
        OptimizedSender { _, _ -> fail("no context must not send") }
            .send(listOf(record(snapshot = null)), { null }, {}, { true }, { fail("ack") }, { dropped = true }, {})
        assertTrue(dropped)
    }

    @Test fun synchronousNetworkOverridesDoNotGrowTheCallbackStackWithQueueLength() {
        var fingerprint: String? = null
        var acknowledged = 0
        OptimizedSender { _, callback -> callback(DeliveryResult.ACCEPTED) }
            .send((1..1024).map { record(it.toString()) }, { fingerprint }, { fingerprint = it },
                { true }, { acknowledged++ }, { fail("unexpected drop") }, {})
        assertEquals(1024, acknowledged)
    }

    @Test(expected = IllegalArgumentException::class)
    fun optimizedDestinationRejectsNonAdobeHosts() {
        TelemetryConfig(optimizedPayload = true, orgId = "TEST@AdobeOrg", endpoint = "https://example.test/ee/v1/interact")
    }
}
