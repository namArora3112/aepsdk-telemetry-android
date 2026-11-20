package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adobe.marketing.mobile.telemetry.ConsentStatus
import com.adobe.marketing.mobile.telemetry.SyncSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Serialized sync state; only the consent fence is also read by collection threads. */
internal object Sync {
    private const val TAG = "AEPTelemetrySync"
    internal const val UNIQUE_WORK = "aep-telemetry-sync-retry"
    private const val PREFS = "adobe.telemetry.sync"
    private const val KEY_CONSENT = "consent"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_RETRY_AT = "retryAtMs"
    private const val KEY_RETRY_ATTEMPT = "retryAttempt"
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "aep-telemetry-sync").apply { isDaemon = true }
    }
    private val initialized = AtomicBoolean(false)
    private val consent = AtomicReference<ConsentStatus?>(null)
    private val privacyLock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var settings: SyncSettings? = null
    private var base: SyncSettings? = null
    private var engine: SyncEngine? = null
    private var queue: EventQueue? = null
    private var remoteConfig: RemoteConfigManager? = null
    private var timer: ScheduledFuture<*>? = null
    private var retryAtMs = 0L
    private var retryAttempt = 0
    private var rejectedVersion: Int? = null
    private var inWorker = false

    @Volatile internal var testTransport: Transport? = null
    @Volatile internal var testQueue: EventQueue? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    fun attach(context: Context) {
        UploadControl.configure(context)
        appContext = context.applicationContext
    }

    /** Called on the collector thread, never the main thread; no network or gate opening. */
    fun prepareCollection(context: Context) {
        attach(context)
        consent.compareAndSet(null, restoreConsent(context))
        val cached = RemoteConfigManager(context).cachedVersion()
        Log.d(TAG, "collection_policy_loaded; consent=${status()}; cachedConfigVersion=$cached; network=false")
        if (status() == ConsentStatus.OPTED_OUT) {
            synchronized(privacyLock) { DiskEventQueue(context).clear() }
        }
    }

    fun collectionAllowed(): Boolean = status() != ConsentStatus.OPTED_OUT

    fun collectIfAllowed(write: () -> Unit) {
        synchronized(privacyLock) {
            if (collectionAllowed()) write()
        }
    }

    fun init(context: Context, initial: SyncSettings) {
        attach(context)
        if (!initialized.compareAndSet(false, true)) {
            Log.d(TAG, "init_skipped; reason=first_config_wins")
            return
        }
        val snapshot = initial.copy(headers = initial.headers.toMap())
        Log.d(TAG, "init_requested")
        executor.execute {
            configure(snapshot)
            refreshAndDrain()
            scheduleTimer()
        }
    }

    private fun configure(initial: SyncSettings) {
        val context = appContext ?: return
        consent.compareAndSet(null, restoreConsent(context))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Consent notified before init must not be overwritten by persisted UNKNOWN.
        prefs.edit().putString(KEY_CONSENT, status().name).commit()
        base = initial
        settings = if (initial.isValid()) initial.sanitized() else initial
        queue = testQueue ?: DiskEventQueue(context)
        remoteConfig = RemoteConfigManager(context, clock)
        retryAtMs = prefs.getLong(KEY_RETRY_AT, 0L)
        retryAttempt = prefs.getInt(KEY_RETRY_ATTEMPT, 0)
        engine = SyncEngine(queue!!, testTransport ?: CoreNetworkTransport(),
            { status() }, { settings ?: initial }, { Log.w(TAG, it) },
            uploadsEnabled = { UploadControl.enabled })
        if (initial.isValid()) {
            prefs.edit().putString(KEY_SETTINGS, RemoteConfigManager.toJson(initial.sanitized()).toString()).commit()
            settings = remoteConfig?.lastKnownGood(initial.sanitized()) ?: initial.sanitized()
        } else {
            prefs.edit().remove(KEY_SETTINGS).commit()
            Log.w(TAG, "init_buffering; reason=invalid_config")
        }
        Log.d(TAG, "gate_configured; valid=${initial.isValid()}; consent=${status()}")
    }

    fun onConsentChanged(value: ConsentStatus) {
        synchronized(privacyLock) { consent.set(value) }
        Log.d(TAG, "consent_changed; status=$value")
        executor.execute {
            val context = appContext ?: return@execute
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CONSENT, value.name).commit()
            if (status() == ConsentStatus.OPTED_OUT) {
                synchronized(privacyLock) { (queue ?: DiskEventQueue(context)).clear() }
                timer?.cancel(false)
                clearRetry()
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
                Log.d(TAG, "opted_out; queue_purged; collection_paused")
            } else if (initialized.get()) {
                refreshAndDrain()
                scheduleTimer()
            }
        }
    }

    fun optOut() = onConsentChanged(ConsentStatus.OPTED_OUT)

    fun onEventEnqueued(queueSize: Int) {
        val active = settings ?: return
        if (queueSize >= active.sanitized().batchSize) drainAsync()
    }

    fun onAppBackgrounded() = drainAsync()

    fun onUploadsEnabled() = drainAsync()

    private fun drainAsync() {
        if (!initialized.get()) return
        executor.execute { refreshAndDrain() }
    }

    private fun refreshAndDrain(): SyncEngine.Outcome? {
        if (status() == ConsentStatus.OPTED_OUT) {
            synchronized(privacyLock) { queue?.clear() }
            return SyncEngine.Outcome.OptedOut
        }
        if (status() != ConsentStatus.OPTED_IN) {
            Log.d(TAG, "gate_buffering; reason=unknown_consent")
            return SyncEngine.Outcome.Buffering
        }
        if (!UploadControl.enabled) return SyncEngine.Outcome.Buffering
        if (clock() < retryAtMs) return SyncEngine.Outcome.Buffering
        val initial = base ?: return null
        if (initial.isValid()) {
            settings = remoteConfig?.refreshIfDue(initial) {
                status() == ConsentStatus.OPTED_IN && UploadControl.enabled
            } ?: settings
        }
        if (rejectedVersion == settings?.configVersion) return SyncEngine.Outcome.RejectedHead
        val outcome = engine?.drain()
        Log.d(TAG, "drain_finished; outcome=${outcome?.javaClass?.simpleName}; remaining=${queue?.size()}")
        when (outcome) {
            is SyncEngine.Outcome.Failed -> scheduleWorkRetry(outcome.retryAfterMs)
            SyncEngine.Outcome.Drained -> {
                clearRetry()
                if (!inWorker) appContext?.let { WorkManager.getInstance(it).cancelUniqueWork(UNIQUE_WORK) }
            }
            SyncEngine.Outcome.RejectedHead -> rejectedVersion = settings?.configVersion
            else -> Unit
        }
        return outcome
    }

    private fun scheduleTimer() {
        timer?.cancel(false)
        if (!initialized.get() || !collectionAllowed()) return
        val interval = settings?.sanitized()?.flushIntervalMs ?: return
        timer = executor.schedule({
            refreshAndDrain()
            scheduleTimer()
        }, interval, TimeUnit.MILLISECONDS)
    }

    private fun scheduleWorkRetry(retryAfterMs: Long?) {
        val context = appContext ?: return
        if (status() == ConsentStatus.OPTED_OUT) return
        val exponential = (10_000L shl retryAttempt.coerceIn(0, 10)).coerceAtMost(18_000_000L)
        val minimum = maxOf(exponential, retryAfterMs ?: 0L)
            .coerceAtMost(CoreNetworkTransport.MAX_RETRY_AFTER_MS)
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(minimum / 4 + 1)
        val delay = minimum + jitter
        retryAtMs = clock() + delay
        retryAttempt++
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_RETRY_AT, retryAtMs).putInt(KEY_RETRY_ATTEMPT, retryAttempt).commit()
        val work = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10_000L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong("notBeforeMs", retryAtMs).build())
            .build()
        val policy = if (inWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, work).result.get()
        Log.d(TAG, "retry_scheduled; attempt=$retryAttempt; delayMs=$delay")
    }

    /** WorkManager recreates the gate from app-private settings after process death. */
    fun runFromWorker(context: Context): Boolean {
        attach(context)
        return executor.submit<Boolean> {
            inWorker = true
            try {
                if (initialized.compareAndSet(false, true)) {
                    val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null)
                    val restored = json?.let { runCatching { RemoteConfigManager.parse(it) }.getOrNull() }
                    if (restored == null || !restored.isValid()) {
                        initialized.set(false)
                        return@submit true
                    }
                    configure(restored)
                    Log.d(TAG, "worker_restored_gate")
                }
                if (clock() < retryAtMs) {
                    // A worker can be awakened early by wall-clock changes.
                    val remaining = retryAtMs - clock()
                    val work = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
                        .setInitialDelay(remaining, TimeUnit.MILLISECONDS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work
                    ).result.get()
                } else {
                    refreshAndDrain()
                }
                scheduleTimer()
                true
            } finally {
                inWorker = false
            }
        }.get()
    }

    private fun clearRetry() {
        retryAtMs = 0L
        retryAttempt = 0
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.remove(KEY_RETRY_AT)?.remove(KEY_RETRY_ATTEMPT)?.commit()
    }

    private fun status(): ConsentStatus = consent.get() ?: ConsentStatus.UNKNOWN

    private fun restoreConsent(context: Context): ConsentStatus = runCatching {
        ConsentStatus.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONSENT, ConsentStatus.UNKNOWN.name)!!)
    }.getOrDefault(ConsentStatus.UNKNOWN)

    @JvmStatic internal fun awaitIdleForTests() { executor.submit {}.get() }

    @JvmStatic internal fun resetForTests() {
        executor.submit {
            timer?.cancel(false)
            initialized.set(false)
            consent.set(null)
            settings = null
            base = null
            engine = null
            queue = null
            remoteConfig = null
            retryAtMs = 0L
            retryAttempt = 0
            rejectedVersion = null
            testTransport = null
            testQueue = null
            clock = { System.currentTimeMillis() }
        }.get()
    }
}
