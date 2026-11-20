package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.telemetry.SyncSettings
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

/**
 * Remote config for the sync pipeline. Read at the moment of use, never cached in a
 * field. Every fetched document is parsed, sanitized (clamped min/max, forbidden
 * headers stripped, endpoint host allowlist enforced) and only then persisted as the
 * new last-known-good. Malformed fetches roll back to the previous LKG.
 *
 * Network entry point is only reachable from Sync (post-init, post-consent).
 */
internal class RemoteConfigManager(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val jitter: (Long) -> Long = { ThreadLocalRandom.current().nextLong(0, it + 1) }
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("adobe.telemetry.remoteconfig", Context.MODE_PRIVATE)

    /** Last-known-good sanitized settings, or null when nothing valid was ever stored. */
    fun lastKnownGood(base: SyncSettings): SyncSettings? {
        if (prefs.getString(KEY_BASE, null) != cacheKey(base)) return null
        return prefs.getString(KEY_LKG, null)?.let {
            runCatching {
                val parsed = parse(it).copy(apiKey = base.apiKey, configEndpoint = base.configEndpoint)
                require(parsed.isValid())
                parsed.sanitized()
            }.getOrNull()
        }
    }

    fun cachedVersion(): Int? = prefs.getString(KEY_LKG, null)?.let {
        runCatching {
            val cached = parse(it)
            require(SyncSettings.allowedEndpoint(cached.endpoint))
            cached.sanitized().configVersion
        }.getOrNull()
    }

    /**
     * Fetches remote config when the minimum interval (plus jitter) has elapsed.
     * Returns the effective settings: freshly fetched, LKG, or the sanitized base.
     */
    fun refreshIfDue(base: SyncSettings, canFetch: () -> Boolean = { true }): SyncSettings {
        if (!base.isValid()) return base
        val sanitizedBase = base.sanitized()
        val fallback = lastKnownGood(sanitizedBase) ?: sanitizedBase
        if (base.configEndpoint == null || !canFetch()) return fallback
        val sameBase = prefs.getString(KEY_BASE, null) == cacheKey(base)
        if (sameBase && clock() < prefs.getLong(KEY_NEXT_FETCH, 0L)) return fallback
        val interval = fetchInterval(fallback)
        prefs.edit().apply {
            if (!sameBase) remove(KEY_LKG).remove(KEY_ETAG)
            putLong(KEY_NEXT_FETCH, clock() + interval).putString(KEY_BASE, cacheKey(base))
        }.commit()
        if (!canFetch()) return fallback
        val fetched = fetch(sanitizedBase, if (sameBase) prefs.getString(KEY_ETAG, null) else null)
        return when (fetched) {
            is FetchResult.NotModified -> fallback
            is FetchResult.Failed -> fallback
            is FetchResult.Fresh -> {
                val merged = runCatching { mergeRemote(sanitizedBase, fetched.body) }.getOrNull()
                if (merged == null) {
                    fallback
                } else {
                    prefs.edit()
                        .putString(KEY_LKG, toJson(merged.copy(apiKey = "")).toString())
                        .putString(KEY_ETAG, fetched.etag)
                        .putLong(KEY_NEXT_FETCH, clock() + fetchInterval(merged))
                        .commit()
                    merged
                }
            }
        }
    }

    private fun fetchInterval(settings: SyncSettings): Long =
        settings.minRemoteConfigFetchMs + jitter(settings.minRemoteConfigFetchMs / 4)

    private sealed class FetchResult {
        data class Fresh(val body: String, val etag: String?) : FetchResult()
        object NotModified : FetchResult()
        object Failed : FetchResult()
    }

    private fun fetch(settings: SyncSettings, etag: String?): FetchResult {
        val headers = mutableMapOf("x-api-key" to settings.apiKey)
        etag?.let { headers["If-None-Match"] = it }
        val response = CoreNetwork.execute(
            settings.configEndpoint ?: return FetchResult.Failed, HttpMethod.GET, null, headers,
            readBody = true, responseHeaders = listOf("ETag")
        ) ?: return FetchResult.Failed
        return when (response.code) {
            304 -> FetchResult.NotModified
            200 -> FetchResult.Fresh(
                response.body ?: return FetchResult.Failed,
                response.headers["etag"]
            )
            else -> FetchResult.Failed
        }
    }

    companion object {
        private const val KEY_LKG = "lastKnownGood"
        private const val KEY_ETAG = "etag"
        private const val KEY_NEXT_FETCH = "nextFetchMs"
        private const val KEY_BASE = "base"
        private const val MAX_CONFIG_BYTES = 64 * 1024
        private fun cacheKey(base: SyncSettings) = "${base.endpoint}|${base.configEndpoint}"

        /** Pure merge: remote keys override base; result is always clamped via sanitized(). */
        fun mergeRemote(base: SyncSettings, remoteBody: String): SyncSettings {
            require(remoteBody.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES)
            val json = JSONObject(remoteBody)
            require(json.length() <= 32) { "remote config too large" }
            val headers = mutableMapOf<String, String>()
            json.optJSONObject("headers")?.let { headersJson ->
                require(headersJson.length() <= SyncSettings.MAX_CUSTOM_HEADERS)
                headersJson.keys().forEach { key -> headers[key] = headersJson.getString(key) }
            }
            val endpoint = if (json.has("endpoint")) json.getString("endpoint") else base.endpoint
            require(SyncSettings.allowedEndpoint(endpoint)) { "Unsafe remote telemetry endpoint" }
            return SyncSettings(
                endpoint = endpoint,
                apiKey = base.apiKey, // API key is never remotely configurable
                batchSize = if (json.has("batchSize")) json.getInt("batchSize") else base.batchSize,
                flushIntervalMs = if (json.has("flushIntervalMs")) json.getLong("flushIntervalMs") else base.flushIntervalMs,
                minRemoteConfigFetchMs = if (json.has("minRemoteConfigFetchMs")) {
                    json.getLong("minRemoteConfigFetchMs")
                } else {
                    base.minRemoteConfigFetchMs
                },
                headers = if (json.has("headers")) headers else base.headers,
                configVersion = if (json.has("configVersion")) json.getInt("configVersion") else base.configVersion,
                configEndpoint = base.configEndpoint
            ).sanitized()
        }

        fun parse(body: String): SyncSettings {
            val json = JSONObject(body)
            val headersJson = json.optJSONObject("headers") ?: JSONObject()
            val headers = mutableMapOf<String, String>()
            headersJson.keys().forEach { key -> headers[key] = headersJson.getString(key) }
            return SyncSettings(
                endpoint = json.getString("endpoint"),
                apiKey = json.getString("apiKey"),
                batchSize = json.getInt("batchSize"),
                flushIntervalMs = json.getLong("flushIntervalMs"),
                minRemoteConfigFetchMs = json.getLong("minRemoteConfigFetchMs"),
                headers = headers,
                configVersion = json.getInt("configVersion"),
                configEndpoint = json.optString("configEndpoint", "").takeIf { it.isNotBlank() }
            )
        }

        fun toJson(settings: SyncSettings): JSONObject = JSONObject()
            .put("endpoint", settings.endpoint)
            .put("apiKey", settings.apiKey)
            .put("batchSize", settings.batchSize)
            .put("flushIntervalMs", settings.flushIntervalMs)
            .put("minRemoteConfigFetchMs", settings.minRemoteConfigFetchMs)
            .put("headers", JSONObject(settings.headers))
            .put("configVersion", settings.configVersion)
            .put("configEndpoint", settings.configEndpoint ?: "")
    }
}
