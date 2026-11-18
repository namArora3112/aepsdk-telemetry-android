package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.ServiceProvider
import com.adobe.marketing.mobile.telemetry.Clock
import com.adobe.marketing.mobile.telemetry.IdGenerator
import com.adobe.marketing.mobile.telemetry.TelemetryBatch
import com.adobe.marketing.mobile.telemetry.TelemetryConfig
import android.content.pm.PackageManager
import android.os.Build
import com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
    @Volatile var lastUploadStatus: String = "never"
        private set

    private val metadata by lazy {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val permissions = JSONArray()
        listOf("android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS").forEach { name ->
            permissions.put(JSONObject().put("name", name)
                .put("granted", (pm.checkPermission(name, context.packageName) == PackageManager.PERMISSION_GRANTED).toString()))
        }
        JSONObject().put("uuid", store.installationId).put("platform", "android").put("osVersion", Build.VERSION.RELEASE)
            .put("application", JSONObject().put("package", context.packageName)
                .put("name", pm.getApplicationLabel(context.applicationInfo).toString()).put("version", version))
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("modelName", Build.MODEL))
            .put("permissions", permissions).toString()
    }

    fun captureContext(config: TelemetryConfig): String {
        val versions = JSONArray()
        TelemetryBridge.registeredSdkVersions().toSortedMap().forEach { (name, version) ->
            versions.put(JSONObject().put("name", name).put("version", version))
        }
        return JSONObject().put("metadata", JSONObject(metadata)).put("sdkVersions", versions)
            .put("orgId", config.orgId).toString()
    }

    fun uploadOptimized(payload: JSONObject, config: TelemetryConfig, done: (DeliveryResult) -> Unit) {
        val request = NetworkRequest(config.endpoint, HttpMethod.POST, payload.toString().toByteArray(Charsets.UTF_8),
            mapOf("Content-Type" to "application/json"), 10, 10)
        try {
            ServiceProvider.getInstance().networkService.connectAsync(request) { connection ->
                val code = connection?.responseCode ?: -1
                val result = try {
                    when {
                        code == -1 || code == 408 || code == 429 || code >= 500 -> DeliveryResult.RETRYABLE
                        code !in 200..299 -> DeliveryResult.REJECTED
                        else -> {
                            val bytes = ByteArrayOutputStream()
                            connection?.inputStream?.use { input ->
                                val buffer = ByteArray(1024)
                                while (bytes.size() <= 16_384) {
                                    val count = input.read(buffer, 0, minOf(buffer.size, 16_385 - bytes.size()))
                                    if (count < 0) break
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            if (bytes.size() > 16_384) DeliveryResult.RETRYABLE
                            else {
                                val text = bytes.toString("UTF-8")
                                val response = if (text.isBlank()) JSONObject() else JSONObject(text)
                                val errors = response.optJSONArray("errors")
                                if (errors != null && errors.length() > 0) DeliveryResult.REJECTED else DeliveryResult.ACCEPTED
                            }
                        }
                    }
                } catch (_: Exception) { DeliveryResult.RETRYABLE }
                finally { runCatching { connection?.close() } }
                // Never persist response bodies, state handles, location hints, or customer data.
                lastUploadStatus = "HTTP $code ${result.name} at ${clock.nowMs()}"
                done(result)
            }
        } catch (_: Exception) {
            lastUploadStatus = "transport error at ${clock.nowMs()}"
            done(DeliveryResult.RETRYABLE)
        }
    }

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
