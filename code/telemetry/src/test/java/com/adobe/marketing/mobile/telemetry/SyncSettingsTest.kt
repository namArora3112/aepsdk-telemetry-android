package com.adobe.marketing.mobile.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsTest {

    private val base = SyncSettings(endpoint = SyncSettings.DEFAULT_ENDPOINT, apiKey = "key")

    @Test
    fun clampsBatchSizeAndIntervalsToHardBounds() {
        val low = base.copy(batchSize = 0, flushIntervalMs = 1, minRemoteConfigFetchMs = 1).sanitized()
        assertEquals(SyncSettings.MIN_BATCH_SIZE, low.batchSize)
        assertEquals(SyncSettings.MIN_FLUSH_INTERVAL_MS, low.flushIntervalMs)
        assertEquals(SyncSettings.MIN_CONFIG_FETCH_MS, low.minRemoteConfigFetchMs)

        val high = base.copy(
            batchSize = Int.MAX_VALUE,
            flushIntervalMs = Long.MAX_VALUE / 2,
            minRemoteConfigFetchMs = Long.MAX_VALUE / 2
        ).sanitized()
        assertEquals(SyncSettings.MAX_BATCH_SIZE, high.batchSize)
        assertEquals(SyncSettings.MAX_FLUSH_INTERVAL_MS, high.flushIntervalMs)
        assertEquals(SyncSettings.MAX_CONFIG_FETCH_MS, high.minRemoteConfigFetchMs)
    }

    @Test
    fun stripsForbiddenHeadersCaseInsensitively() {
        val settings = base.copy(headers = mapOf(
            "Authorization" to "Bearer x",
            "HOST" to "evil.example.com",
            "Cookie" to "session=1",
            "Content-Length" to "5",
            "x-custom" to "kept"
        )).sanitized()
        assertEquals(mapOf("x-custom" to "kept"), settings.headers)
    }

    @Test
    fun capsHeaderCountAndValueLength() {
        val headers = (1..20).associate { "x-h$it" to "v".repeat(500) }
        val sanitized = base.copy(headers = headers).sanitized()
        assertTrue(sanitized.headers.size <= SyncSettings.MAX_CUSTOM_HEADERS)
        assertTrue(sanitized.headers.values.all { it.length <= 256 })
    }

    @Test
    fun rejectsNonHttpsAndDisallowedHostsToDefaultEndpoint() {
        assertEquals(SyncSettings.DEFAULT_ENDPOINT,
            base.copy(endpoint = "http://edge-int.adobedc.net/x").sanitized().endpoint)
        assertEquals(SyncSettings.DEFAULT_ENDPOINT,
            base.copy(endpoint = "https://evil.example.com/x").sanitized().endpoint)
        assertEquals(SyncSettings.DEFAULT_ENDPOINT,
            base.copy(endpoint = "not a url").sanitized().endpoint)
    }

    @Test
    fun keepsAllowlistedHttpsEndpoint() {
        val allowed = "https://edge.adobedc.net/ee/v1/interact"
        assertEquals(allowed, base.copy(endpoint = allowed).sanitized().endpoint)
    }

    @Test
    fun capsApiKeyAndConfigVersion() {
        val sanitized = base.copy(apiKey = "k".repeat(1000), configVersion = -3).sanitized()
        assertEquals(SyncSettings.MAX_API_KEY_LENGTH, sanitized.apiKey.length)
        assertEquals(0, sanitized.configVersion)
        assertFalse(sanitized.headers.keys.any { it.lowercase() in SyncSettings.FORBIDDEN_HEADERS })
    }

    @Test fun remoteHeadersCannotReplaceCredentialsOrInjectLines() {
        val sanitized = base.copy(headers = mapOf(
            "X-API-Key" to "override", "x-aep-telemetry-config-version" to "99",
            "x-newline" to "x\r\nHost: other", "x ok" to "invalid", "x-safe" to "yes"
        )).sanitized()
        assertEquals(mapOf("x-safe" to "yes"), sanitized.headers)
    }
}
