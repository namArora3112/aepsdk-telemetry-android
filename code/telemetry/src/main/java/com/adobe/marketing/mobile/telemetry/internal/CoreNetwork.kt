package com.adobe.marketing.mobile.telemetry.internal

import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.SyncSettings
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Bounded response from Core Networking; only the explicitly requested headers are read. */
internal data class CoreHttpResponse(
    val code: Int,
    val body: String?,
    val headers: Map<String, String?>
)

/**
 * Synchronous bridge over Core's asynchronous NetworkService for the sync pipeline's
 * dedicated background executors (Sync worker thread and WorkManager thread — never the
 * main thread). The service is resolved at call time so a host override is honored, and
 * the default service is constructed eagerly, so this works before MobileCore.initialize.
 */
internal object CoreNetwork {

    fun execute(
        url: String,
        method: HttpMethod,
        body: ByteArray?,
        headers: Map<String, String>,
        readBody: Boolean,
        responseHeaders: List<String>,
        timeoutMs: Long = 30_000L
    ): CoreHttpResponse? {
        val latch = CountDownLatch(1)
        var result: CoreHttpResponse? = null
        val request = NetworkRequest(url, method, body, headers, 10, 10)
        try {
            ServiceProvider.getInstance().networkService.connectAsync(request) { connection ->
                try {
                    if (connection == null) {
                        result = null
                    } else {
                        val text = if (readBody) readBounded(connection.inputStream) else null
                        val wanted = responseHeaders.associate { name ->
                            name.lowercase() to runCatching {
                                connection.getResponsePropertyValue(name)
                            }.getOrNull()
                        }
                        result = CoreHttpResponse(connection.responseCode, text, wanted)
                    }
                } catch (_: Exception) {
                    result = null
                } finally {
                    runCatching { connection?.close() }
                    latch.countDown()
                }
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (_: Exception) {
            return null
        }
        return result
    }

    private fun readBounded(stream: java.io.InputStream?, maxBytes: Int = 64 * 1024): String? {
        stream ?: return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= maxBytes) { "Oversized telemetry config response" }
                out.write(buffer, 0, count)
            }
        }
        return out.toString("UTF-8")
    }
}

internal sealed class UploadResult {
    object Accepted : UploadResult()
    data class Retryable(val retryAfterMs: Long?) : UploadResult()
    object Rejected : UploadResult()
}

internal interface Transport {
    fun post(settings: SyncSettings, body: String): UploadResult
}

/**
 * Upload transport on top of Core Networking. Only reachable from Sync after
 * Telemetry.init has passed validation and consent — never from Phase-1 collection.
 */
internal class CoreNetworkTransport : Transport {

    override fun post(settings: SyncSettings, body: String): UploadResult {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "x-api-key" to settings.apiKey,
            "x-aep-telemetry-config-version" to settings.configVersion.toString()
        )
        settings.headers.forEach { (key, value) ->
            if (key.lowercase() !in SyncSettings.FORBIDDEN_HEADERS) headers[key] = value
        }
        val response = CoreNetwork.execute(
            settings.endpoint, HttpMethod.POST, body.toByteArray(Charsets.UTF_8), headers,
            readBody = false, responseHeaders = listOf("Retry-After")
        ) ?: return UploadResult.Retryable(null)
        return when (response.code) {
            in 200..299 -> UploadResult.Accepted
            408, 429, in 500..599 -> UploadResult.Retryable(parseRetryAfter(response.headers["retry-after"]))
            in 400..499 -> UploadResult.Rejected
            else -> UploadResult.Retryable(null)
        }
    }

    internal fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (value == null) return null
        value.trim().toLongOrNull()?.let {
            return if (it < 0) null else it.coerceAtMost(MAX_RETRY_AFTER_MS / 1000) * 1000
        }
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            (format.parse(value)!!.time - nowMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }.getOrNull()
    }

    companion object {
        internal const val MAX_RETRY_AFTER_MS = 24L * 60L * 60L * 1000L
    }
}
