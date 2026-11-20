package com.adobe.marketing.mobile.telemetry.internal

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase-1 orchestrator: starts automatic, disk-only collection at process launch.
 *
 *  - Idempotent (AtomicBoolean CAS); Telemetry.init() calls this as a fallback when the
 *    host removed the App Startup provider.
 *  - Light on the main thread: registers callbacks and returns; all disk I/O runs on a
 *    single background executor, except the crash write which is synchronous by design.
 *  - Main process only. Host opt-out: manifest meta-data
 *    `com.adobe.marketing.mobile.telemetry.AUTO_START = false` (or removing the
 *    initializer's meta-data with tools:node="remove").
 *  - Never fetches remote config here; the Sync path owns network after Telemetry.init.
 */
internal object TelemetryCollector {

    const val AUTO_START_METADATA = "com.adobe.marketing.mobile.telemetry.AUTO_START"

    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aep-telemetry-collector").apply { isDaemon = true }
    }

    @Volatile private var queue: EventQueue? = null
    @Volatile private var sessionManager: SessionManager? = null
    @Volatile private var collecting = false

    /** Test seam: observe enqueued events without Android storage. */
    @Volatile internal var testQueue: EventQueue? = null
    @Volatile internal var testSessionStore: SessionStore? = null
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    @JvmStatic
    fun start(application: Application) {
        if (!started.compareAndSet(false, true)) {
            android.util.Log.d("AEPTelemetryAuto", "collector_start_skipped; reason=already_started")
            return
        }
        if (!isMainProcess(application) || !autoStartEnabled(application)) {
            android.util.Log.d("AEPTelemetryAuto", "collector_start_skipped; reason=process_or_manifest")
            return
        }

        TelemetryStartupDiagnostics.configure(application)
        Sync.attach(application)
        val now = clock()
        val coldStart = coldStartProps()
        TelemetryStartupDiagnostics.current.milestone(
            "AUTO_COLLECTION_STARTING",
            "phase=disk_only; app_start_candidate_at_ms=$now; consent_check=pending; persistence=not_yet_confirmed"
        )
        val state by lazy {
            Sync.prepareCollection(application)
            CollectorState(
                testQueue ?: DiskEventQueue(application),
                SessionManager(testSessionStore ?: PrefsSessionStore(application), clock)
            ).also {
                queue = it.queue
                sessionManager = it.sessions
            }
        }
        collecting = true

        executor.execute {
            val ready = state
            if (Sync.collectionAllowed()) {
                recordProcessStartEvents(application, ready.queue, ready.sessions, now, coldStart)
                Sync.collectIfAllowed {
                    TelemetryStartupDiagnostics.current.snapshot(ready.queue)
                }
            }
        }
        registerLifecycle(application) { state }
        installCrashHandler { state }
        TelemetryStartupDiagnostics.current.milestone(
            "AUTO_COLLECTION_CALLBACKS_REGISTERED", "phase=disk_only; uploads_not_enabled_by_this_step"
        )
        android.util.Log.d("AEPTelemetryAuto", "collector_registered; phase=disk_only")
    }

    private data class CollectorState(val queue: EventQueue, val sessions: SessionManager)

    /** Visible for Sync: enqueue an automatic event and notify the post-init gate. */
    internal fun enqueue(queue: EventQueue, sessions: SessionManager, event: AutoEvent) {
        Sync.collectIfAllowed {
            queue.enqueue(event)
            android.util.Log.d("AEPTelemetryAuto",
                "event_persisted; name=${event.name}; timestamp=${event.timestampMs}; thread=${Thread.currentThread().name}")
            Sync.onEventEnqueued(queue.size())
        }
    }

    @JvmStatic
    internal fun resetForTests() {
        started.set(false)
        collecting = false
        queue = null
        sessionManager = null
        testQueue = null
        testSessionStore = null
        clock = { System.currentTimeMillis() }
    }

    private fun recordProcessStartEvents(
        application: Application,
        queue: EventQueue,
        sessions: SessionManager,
        now: Long,
        coldStart: Map<String, String>
    ) {
        when (val transition = sessions.onProcessStart(now)) {
            is SessionManager.Transition.Started ->
                enqueue(queue, sessions, AutoEvent("session_start", now, transition.sessionId))
            is SessionManager.Transition.Renewed -> {
                enqueue(queue, sessions, AutoEvent("session_end", transition.endedAtMs, transition.endedSessionId))
                enqueue(queue, sessions, AutoEvent("session_start", now, transition.newSessionId))
            }
            is SessionManager.Transition.Continued -> Unit
        }
        val session = sessions.currentSessionId
        enqueue(queue, sessions, AutoEvent("app_start", now, session, coldStart))
        when (val install = detectInstallState(application)) {
            is InstallState.FreshInstall ->
                enqueue(queue, sessions, AutoEvent("app_install", now, session, mapOf("version_code" to install.versionCode.toString())))
            is InstallState.Updated ->
                enqueue(queue, sessions, AutoEvent("app_update", now, session, mapOf(
                    "version_code" to install.versionCode.toString(),
                    "previous_version_code" to install.previousVersionCode.toString()
                )))
            InstallState.Unchanged -> Unit
        }
    }

    private fun coldStartProps(): Map<String, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mapOf("cold_start_ms" to
                (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).coerceAtLeast(0L).toString())
        } else {
            emptyMap()
        }

    private sealed class InstallState {
        data class FreshInstall(val versionCode: Long) : InstallState()
        data class Updated(val versionCode: Long, val previousVersionCode: Long) : InstallState()
        object Unchanged : InstallState()
    }

    private fun detectInstallState(application: Application): InstallState {
        val prefs = application.getSharedPreferences("adobe.telemetry.install", Context.MODE_PRIVATE)
        val current = runCatching {
            val info = application.packageManager.getPackageInfo(application.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrNull() ?: return InstallState.Unchanged
        val stored = prefs.getLong(KEY_VERSION_CODE, Long.MIN_VALUE)
        prefs.edit().putLong(KEY_VERSION_CODE, current).apply()
        return when {
            stored == Long.MIN_VALUE -> InstallState.FreshInstall(current)
            stored != current -> InstallState.Updated(current, stored)
            else -> InstallState.Unchanged
        }
    }

    private fun registerLifecycle(application: Application, state: () -> CollectorState) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!collecting || !Sync.collectionAllowed()) return
                val now = clock()
                val screen = activity.javaClass.name.take(128)
                executor.execute {
                    val (queue, sessions) = state()
                    if (!Sync.collectionAllowed()) return@execute
                    enqueue(queue, sessions, AutoEvent(
                        "screen_view", now, sessions.currentSessionId,
                        mapOf("screen" to screen)
                    ))
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!collecting || !Sync.collectionAllowed()) return
                val now = clock()
                executor.execute {
                    val (queue, sessions) = state()
                    if (!Sync.collectionAllowed()) return@execute
                    when (val transition = sessions.onForeground(now)) {
                        is SessionManager.Transition.Started ->
                            enqueue(queue, sessions, AutoEvent("session_start", now, transition.sessionId))
                        is SessionManager.Transition.Renewed -> {
                            enqueue(queue, sessions, AutoEvent("session_end", transition.endedAtMs, transition.endedSessionId))
                            enqueue(queue, sessions, AutoEvent("session_start", now, transition.newSessionId))
                        }
                        is SessionManager.Transition.Continued -> Unit
                    }
                    enqueue(queue, sessions, AutoEvent("app_foreground", now, sessions.currentSessionId))
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!collecting || !Sync.collectionAllowed()) return
                val now = clock()
                executor.execute {
                    val (queue, sessions) = state()
                    if (!Sync.collectionAllowed()) return@execute
                    sessions.onBackground(now)
                    enqueue(queue, sessions, AutoEvent("app_background", now, sessions.currentSessionId))
                    Sync.onAppBackgrounded()
                }
            }
        })
    }

    private fun installCrashHandler(state: () -> CollectorState) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (collecting) {
                // Synchronous write: the process is about to die; record only the
                // exception class and thread name — never message text or stack frames.
                runCatching {
                    val (queue, sessions) = state()
                    Sync.collectIfAllowed {
                        queue.enqueue(AutoEvent("app_crash", System.currentTimeMillis(), sessions.currentSessionId,
                            mapOf(
                                "exception_class" to throwable.javaClass.name.take(128),
                                "thread" to thread.name.take(128)
                            )))
                        android.util.Log.d("AEPTelemetryAuto", "crash_persisted; chaining_previous_handler")
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
                ?: Process.killProcess(Process.myPid())
        }
    }

    private fun isMainProcess(application: Application): Boolean {
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = Process.myPid()
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        } ?: return false
        return current == application.packageName
    }

    private fun autoStartEnabled(application: Application): Boolean = runCatching {
        val info = application.packageManager.getApplicationInfo(
            application.packageName, PackageManager.GET_META_DATA
        )
        info.metaData?.getBoolean(AUTO_START_METADATA, true) ?: true
    }.getOrDefault(true)

    private const val KEY_VERSION_CODE = "versionCode"
}
