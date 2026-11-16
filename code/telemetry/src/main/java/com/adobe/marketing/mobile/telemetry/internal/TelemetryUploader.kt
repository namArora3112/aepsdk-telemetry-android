package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.Clock
import com.adobe.marketing.mobile.telemetry.IdGenerator
import com.adobe.marketing.mobile.telemetry.TelemetryBatch
import com.adobe.marketing.mobile.telemetry.TelemetryConfig

/**
 * Uploader: builds the batch envelope and sends ONE classified attempt through Core Networking.
 * Q5: ServiceProvider constructs the default NetworkService eagerly, so this works even when
 * Core.initialize has not run (customer-install path). Service is resolved at send time so a
 * host override is honored. One retry owner: this uploader; Core never re-queues our batch.
 */
internal class TelemetryUploader(
    private val context: Context,
    private val store: TelemetryStore,
    private val ids: IdGenerator,
    private val clock: Clock
) {
    var lastUploadStatus: String = "never"
        private set

    fun buildBatch(records: List<com.adobe.marketing.mobile.telemetry.TelemetryRecord>): TelemetryBatch {
        val pm = context.packageManager
        val version = try {
            pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) { "unknown" }
        return TelemetryBatch(
            batchId = ids.newId(),
            createdAtMs = clock.nowMs(),
            installationId = store.installationId,
            appPackage = context.packageName,
            appVersion = version,
            sdkVersions = mapOf("com.adobe.module.core" to com.adobe.marketing.mobile.MobileCore.extensionVersion()),
            records = records
        )
    }

    /** One classified attempt. onDone(true) only on 2xx; 429/5xx -> retryable (caller requeues). */
    fun upload(batch: TelemetryBatch, config: TelemetryConfig, onDone: (success: Boolean, retryable: Boolean) -> Unit) {
        val body = batch.toJson().toString().toByteArray(Charsets.UTF_8)
        val request = NetworkRequest(
            config.endpoint, HttpMethod.POST, body,
            mapOf("Content-Type" to "application/json"), 10, 10
        )
        try {
            ServiceProvider.getInstance().networkService.connectAsync(request) { connection ->
                val code = connection?.responseCode ?: -1
                lastUploadStatus = "HTTP $code at ${clock.nowMs()}"
                connection?.close()
                when {
                    code in 200..299 -> onDone(true, false)
                    code == 429 || code >= 500 || code == -1 -> onDone(false, true)
                    else -> onDone(false, false) // permanent rejection: drop with bounded diagnostic
                }
            }
        } catch (e: Exception) {
            lastUploadStatus = "error: ${e.message}"
            onDone(false, true)
        }
    }
}
