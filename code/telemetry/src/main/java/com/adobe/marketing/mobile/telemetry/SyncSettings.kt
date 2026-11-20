package com.adobe.marketing.mobile.telemetry

/**
 * Settings for the Phase-2 sync gate, supplied once via [Telemetry.init].
 * First config wins; later init calls are no-ops.
 *
 * Remote config can narrow these values at runtime, but every key is clamped to the
 * compiled-in hard min/max below and falls back to the last-known-good sanitized config.
 */
data class SyncSettings(
    /** HTTPS upload endpoint; its host must be on [ALLOWED_HOSTS]. */
    val endpoint: String,
    /** Non-blank API key sent as `x-api-key`. */
    val apiKey: String,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    val minRemoteConfigFetchMs: Long = DEFAULT_MIN_CONFIG_FETCH_MS,
    /** Extra request headers. Authorization/Host/Cookie/Content-Length are always stripped. */
    val headers: Map<String, String> = emptyMap(),
    /** Version of the remote config these settings were merged from; echoed on every upload. */
    val configVersion: Int = 0,
    /** Optional, separate HTTPS configuration document; never GET the upload URL. */
    val configEndpoint: String? = null
) {
    internal fun isValid(): Boolean =
        allowedEndpoint(endpoint) && (configEndpoint == null || allowedEndpoint(configEndpoint)) &&
            apiKey.isNotBlank() && apiKey.length <= MAX_API_KEY_LENGTH &&
            apiKey.none { it == '\r' || it == '\n' }

    internal fun sanitized(): SyncSettings {
        val safeEndpoint = if (allowedEndpoint(endpoint)) {
            endpoint
        } else {
            DEFAULT_ENDPOINT
        }
        return SyncSettings(
            endpoint = safeEndpoint,
            apiKey = apiKey.take(MAX_API_KEY_LENGTH),
            batchSize = batchSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE),
            flushIntervalMs = flushIntervalMs.coerceIn(MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS),
            minRemoteConfigFetchMs = minRemoteConfigFetchMs.coerceIn(MIN_CONFIG_FETCH_MS, MAX_CONFIG_FETCH_MS),
            headers = headers
                .filterKeys { it.lowercase() !in FORBIDDEN_HEADERS && it.matches(Regex("[A-Za-z0-9-]{1,64}")) }
                .filterValues { '\r' !in it && '\n' !in it }
                .mapValues { it.value.take(256) }
                .entries.take(MAX_CUSTOM_HEADERS)
                .associate { it.toPair() },
            configVersion = configVersion.coerceAtLeast(0),
            configEndpoint = configEndpoint?.takeIf(::allowedEndpoint)
        )
    }

    companion object {
        /** Compiled-in endpoint host allowlist; remote config can never redirect uploads elsewhere. */
        val ALLOWED_HOSTS = setOf("edge-int.adobedc.net", "edge.adobedc.net")
        const val DEFAULT_ENDPOINT = "https://edge-int.adobedc.net/ee/v1/interact"

        const val MIN_BATCH_SIZE = 1
        const val MAX_BATCH_SIZE = 100
        const val DEFAULT_BATCH_SIZE = 20

        const val MIN_FLUSH_INTERVAL_MS = 5_000L
        const val MAX_FLUSH_INTERVAL_MS = 15L * 60L * 1000L
        const val DEFAULT_FLUSH_INTERVAL_MS = 30_000L

        const val MIN_CONFIG_FETCH_MS = 60_000L
        const val MAX_CONFIG_FETCH_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_MIN_CONFIG_FETCH_MS = 15L * 60L * 1000L

        const val MAX_CUSTOM_HEADERS = 8
        const val MAX_API_KEY_LENGTH = 256

        /** Never settable from remote config or extras. */
        val FORBIDDEN_HEADERS = setOf(
            "authorization", "host", "cookie", "content-length", "x-api-key",
            "content-type", "x-aep-telemetry-config-version"
        )

        internal fun allowedEndpoint(endpoint: String): Boolean {
            val uri = runCatching { java.net.URI(endpoint) }.getOrNull() ?: return false
            return endpoint.length <= 2048 && uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase() in ALLOWED_HOSTS && uri.userInfo == null &&
                uri.fragment == null && (uri.port == -1 || uri.port == 443)
        }
    }
}
