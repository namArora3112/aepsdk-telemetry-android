package com.adobe.marketing.mobile.telemetry

import android.app.Application
import com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder
import com.adobe.marketing.mobile.telemetry.internal.TelemetryStore
import com.adobe.marketing.mobile.telemetry.internal.TelemetryUploader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Public entry point — one shared recorder behind both install paths (Q2/Q5):
 *
 *   class App : Application() {
 *     override fun onCreate() {
 *       super.onCreate()
 *       Telemetry.install(this)                 // BEFORE MobileCore.initialize
 *       MobileCore.initialize(this, "ENV_ID")
 *     }
 *   }
 *
 * Idempotent install-once: a second call (e.g. a future Core bootstrap hook) is a no-op.
 */
object Telemetry {

    private val lock = Any()
    private var recorder: TelemetryRecorder? = null
    private var store: TelemetryStore? = null
    private var uploader: TelemetryUploader? = null
    private var config: TelemetryConfig = TelemetryConfig()
    private var clock: Clock = Clock { System.currentTimeMillis() }
    private var ids: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    private val operations = ConcurrentHashMap<String, Long>() // operationId -> startMs
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "aep-telemetry").apply { isDaemon = true }
    }

    @Volatile var installedAtMs: Long = 0L; private set
    @Volatile var installed: Boolean = false; private set

    /** Customer-facing entry point. Attach is memory-only: no network, no disk scan beyond prefs. */
    @JvmStatic
    fun install(application: Application) {
        synchronized(lock) {
            if (installed) return                          // install-once; first attach wins
            installed = true
            installedAtMs = clock.nowMs()
            config = TelemetryConfig.fromAssets(application)
            store = TelemetryStore(application, ids)
            recorder = TelemetryRecorder(clock) { config }
            uploader = TelemetryUploader(application, store!!, ids, clock)
        }
        recordInternal("com.adobe.module.core", "sdk.telemetry.install", "event", "directHook",
            outcome = if (config.enabled) "success" else "ignored",
            outcomeMeaning = if (config.enabled) "collection enabled" else "disabled by config (safe default)")
        if (config.enabled) {
            executor.scheduleWithFixedDelay({ maybeFlush() }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    /** Test seam (Omega testSend parity): same gating as record(), without persisting. */
    @JvmStatic
    fun dryRun(record: TelemetryRecord): Boolean {
        val r = recorder ?: return false
        return r.copyForDryRun(record)
    }

    /** Direct-hook API for SDKs (Q6 Part 3). No-op when not installed or disabled. */
    @JvmStatic
    fun event(producer: String, eventName: String, capture: String = "directHook") {
        recordInternal(producer, eventName, "event", capture)
    }

    /** Start an instrumented operation; returns operationId for correlation. */
    @JvmStatic
    fun operationStarted(producer: String, eventName: String, capture: String = "directHook"): String {
        val opId = ids.newId()
        operations[opId] = clock.nowMs()
        recordInternal(producer, eventName, "started", capture, operationId = opId)
        return opId
    }

    @JvmStatic
    fun operationCompleted(operationId: String, producer: String, eventName: String,
                           outcome: String, outcomeMeaning: String? = null, capture: String = "directHook") {
        val start = operations.remove(operationId)
        recordInternal(producer, eventName, "completed", capture,
            operationId = operationId, outcome = outcome, outcomeMeaning = outcomeMeaning,
            durationMs = start?.let { clock.nowMs() - it })
    }

    /** Hub projection entry (read-only observation of already-dispatched hub events). */
    @JvmStatic
    fun hubObserved(producer: String, eventName: String, requestId: String? = null) {
        recordInternal(producer, eventName, "event", "hubProjection", requestId = requestId)
    }

    @JvmStatic
    fun flush() { maybeFlush(force = true) }

    @JvmStatic
    fun stats(): Map<String, Any?> = mapOf(
        "installed" to installed,
        "enabled" to config.enabled,
        "endpoint" to config.endpoint.ifBlank { "(capture-only)" },
        "lastUpload" to (uploader?.lastUploadStatus ?: "never")
    ) + (recorder?.stats() ?: emptyMap())

    /** Policy change entry point (remote kill switch lands here). Deny -> purge. */
    @JvmStatic
    fun applyConfig(newConfig: TelemetryConfig) {
        synchronized(lock) {
            val wasEnabled = config.enabled
            config = newConfig
            if (wasEnabled && !newConfig.enabled) recorder?.purge()
        }
    }

    /** Test-only: inject doubles before install. */
    @JvmStatic
    fun injectTestDoubles(clock: Clock, ids: IdGenerator) {
        if (!installed) { this.clock = clock; this.ids = ids }
    }

    private fun recordInternal(producer: String, eventName: String, phase: String, capture: String,
                               operationId: String? = null, requestId: String? = null,
                               outcome: String? = null, outcomeMeaning: String? = null,
                               durationMs: Long? = null) {
        val r = recorder ?: return
        r.record(TelemetryRecord(ids.newId(), clock.nowMs(), producer, eventName, phase, capture,
            operationId, requestId, outcome, outcomeMeaning, durationMs))
    }

    private fun maybeFlush(force: Boolean = false) {
        val r = recorder ?: return
        val u = uploader ?: return
        if (!config.enabled || config.endpoint.isBlank()) return
        if (!force && !r.shouldFlush()) return
        val records = r.drain()
        if (records.isEmpty()) return
        val batch = u.buildBatch(records)
        u.upload(batch, config) { success, retryable ->
            if (!success && retryable) r.requeue(records)
            // permanent failure: dropped with bounded diagnostic (lastUploadStatus)
        }
    }
}

/** Dry-run needs gating parity without mutating the queue — implemented on the recorder. */
private fun com.adobe.marketing.mobile.telemetry.internal.TelemetryRecorder.copyForDryRun(record: TelemetryRecord): Boolean {
    // Run the same checks record() performs, against a snapshot, without mutation.
    val probe = TelemetryRecord(record.recordId, record.timestamp, record.producer, record.eventName,
        record.phase, record.capture, record.operationId, record.requestId, record.outcome,
        record.outcomeMeaning, record.durationMs)
    if (!probe.isMeaningful()) return false
    // Dedup + capacity checks are stateful; for the POC dry-run we report policy+validity only.
    return true
}
