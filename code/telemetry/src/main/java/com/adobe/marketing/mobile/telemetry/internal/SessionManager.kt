package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import java.util.UUID

/** Minimal persistence for session state; SharedPreferences-backed in production. */
internal interface SessionStore {
    var sessionId: String?
    var lastForegroundMs: Long
}

internal class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("adobe.telemetry.session", Context.MODE_PRIVATE)

    override var sessionId: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(value) {
            prefs.edit().putString(KEY_SESSION, value).apply()
        }

    override var lastForegroundMs: Long
        get() = prefs.getLong(KEY_LAST_FG, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_FG, value).apply()
        }

    private companion object {
        const val KEY_SESSION = "sessionId"
        const val KEY_LAST_FG = "lastForegroundMs"
    }
}

/**
 * 30-minute background-timeout sessions. Pure decision logic — Android lifecycle
 * wiring lives in TelemetryCollector; this type is unit-tested with fakes.
 *
 * Rules:
 *  - process start with no/expired session -> open a new session (session_start)
 *  - foreground after < timeout in background -> same session continues
 *  - foreground after >= timeout -> session_end for the old one, then session_start
 */
internal class SessionManager(
    private val store: SessionStore,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    private var backgroundedAtMs: Long? = null

    sealed class Transition {
        data class Started(val sessionId: String, val atMs: Long) : Transition()
        data class Renewed(val endedSessionId: String, val endedAtMs: Long, val newSessionId: String) : Transition()
        data class Continued(val sessionId: String) : Transition()
    }

    val currentSessionId: String
        @Synchronized get() = store.sessionId ?: openSession(clock())

    /** Process start / collector start. */
    @Synchronized
    fun onProcessStart(now: Long = clock()): Transition =
        resume(now, store.lastForegroundMs.takeIf { it > 0L })

    /** App moved to foreground. */
    @Synchronized
    fun onForeground(now: Long = clock()): Transition = resume(now, backgroundedAtMs)

    private fun resume(now: Long, backgroundedAt: Long?): Transition {
        val existing = store.sessionId
        val transition = if (existing == null) {
            Transition.Started(openSession(now), now)
        } else if (backgroundedAt != null && now - backgroundedAt >= timeoutMs) {
            val endedAt = backgroundedAt + timeoutMs
            val fresh = openSession(now)
            Transition.Renewed(existing, endedAt, fresh)
        } else {
            Transition.Continued(existing)
        }
        backgroundedAtMs = null
        store.lastForegroundMs = now
        return transition
    }

    /** App moved to background; the timeout is evaluated on the next foreground. */
    @Synchronized
    fun onBackground(now: Long = clock()) {
        backgroundedAtMs = now
        store.lastForegroundMs = now
    }

    private fun openSession(atMs: Long): String {
        val id = newId()
        store.sessionId = id
        store.lastForegroundMs = atMs
        return id
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
