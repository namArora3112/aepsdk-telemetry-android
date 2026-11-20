package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.RemoteConfigManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteConfigManagerTest {

    private val base = SyncSettings(
        endpoint = SyncSettings.DEFAULT_ENDPOINT,
        apiKey = "local-key"
    )

    @Test
    fun remoteValuesOverrideAndAreClamped() {
        val merged = RemoteConfigManager.mergeRemote(base, """
            {"configVersion": 7, "batchSize": 100000, "flushIntervalMs": 1}
        """)
        assertEquals(7, merged.configVersion)
        assertEquals(SyncSettings.MAX_BATCH_SIZE, merged.batchSize)
        assertEquals(SyncSettings.MIN_FLUSH_INTERVAL_MS, merged.flushIntervalMs)
        // Untouched keys keep the base values.
        assertEquals(base.minRemoteConfigFetchMs, merged.minRemoteConfigFetchMs)
    }

    @Test
    fun unsafeRemoteEndpointThrowsSoCallerRetainsLastKnownGood() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteConfigManager.mergeRemote(base, """{"endpoint": "https://evil.example.com/x"}""")
        }
    }

    @Test
    fun remoteCanMoveEndpointWithinAllowlist() {
        val merged = RemoteConfigManager.mergeRemote(base, """{"endpoint": "https://edge.adobedc.net/ee/v9/interact"}""")
        assertEquals("https://edge.adobedc.net/ee/v9/interact", merged.endpoint)
    }

    @Test
    fun apiKeyIsNeverRemotelyConfigurable() {
        val merged = RemoteConfigManager.mergeRemote(base, """{"apiKey": "remote-key", "configVersion": 2}""")
        assertEquals("local-key", merged.apiKey)
    }

    @Test
    fun forbiddenRemoteHeadersAreStripped() {
        val merged = RemoteConfigManager.mergeRemote(base, """
            {"headers": {"Authorization": "Bearer x", "Cookie": "a=b", "x-ok": "1"}}
        """)
        assertEquals(mapOf("x-ok" to "1"), merged.headers)
    }

    @Test
    fun malformedRemoteConfigThrowsSoCallerRollsBack() {
        assertThrows(Exception::class.java) { RemoteConfigManager.mergeRemote(base, "not json") }
        assertThrows(Exception::class.java) {
            RemoteConfigManager.mergeRemote(base, """{"batchSize": "NaN"}""")
        }
    }

    @Test
    fun lastKnownGoodRoundTrips() {
        val settings = base.copy(batchSize = 42, configVersion = 9, headers = mapOf("x-a" to "b"))
        val parsed = RemoteConfigManager.parse(RemoteConfigManager.toJson(settings).toString())
        assertEquals(settings, parsed)
    }

    @Test fun explicitEmptyRemoteHeadersClearPreviousHeaders() {
        val merged = RemoteConfigManager.mergeRemote(
            base.copy(headers = mapOf("x-old" to "1")), """{"headers": {}}"""
        )
        assertEquals(emptyMap<String, String>(), merged.headers)
    }
}
