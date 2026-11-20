package com.adobe.marketing.mobile.telemetry

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelemetryConfigTest {
    @Test
    fun optimizedJsonUsesSdkStageRoutingWithoutOrgOrEndpointProperties() {
        val config = TelemetryConfig.fromJson(JSONObject("""{"enabled":true,"optimizedPayload":true}"""))
        assertTrue(config.enabled)
        assertTrue(config.optimizedPayload)
        assertEquals("745F37C35E4B776E0A49421B@AdobeOrg", config.orgId)
        assertEquals("https://edge-int.adobedc.net/ee/v1/interact?configId=6308f4cc-1c6b-4700-aaa8-d5622404f6c3", config.endpoint)
    }

    @Test
    fun defaultRoutingDoesNotEnableCollectionOrChangeTheLegacyCollector() {
        val optimized = TelemetryConfig.optimizedDefaults()
        val fromJson = TelemetryConfig.fromJson(JSONObject("""{"optimizedPayload":true}"""))
        assertFalse(optimized.enabled)
        assertEquals(optimized, fromJson)
        assertEquals(TelemetryConfig(), TelemetryConfig.fromJson(JSONObject()))
        assertTrue(TelemetryConfig().endpoint.isEmpty())
    }

    @Test
    fun explicitOrgAndEndpointOverrideDefaultsIncludingCaptureOnlyMode() {
        val custom = TelemetryConfig.fromJson(JSONObject("""{
            "optimizedPayload":true,
            "orgId":"OTHER@AdobeOrg",
            "endpoint":"https://edge.adobedc.net/ee/v1/interact?configId=custom"
        }"""))
        assertEquals("OTHER@AdobeOrg", custom.orgId)
        assertEquals("https://edge.adobedc.net/ee/v1/interact?configId=custom", custom.endpoint)
        val captureOnly = TelemetryConfig.fromJson(JSONObject("""{"optimizedPayload":true,"endpoint":""}"""))
        assertTrue(captureOnly.endpoint.isEmpty())
        assertEquals(TelemetryConfig.optimizedDefaults().orgId, captureOnly.orgId)
    }
}
