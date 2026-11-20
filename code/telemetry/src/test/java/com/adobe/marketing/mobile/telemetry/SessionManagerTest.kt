package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.SessionManager
import com.adobe.marketing.mobile.telemetry.internal.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionManagerTest {

    private class FakeStore : SessionStore {
        override var sessionId: String? = null
        override var lastForegroundMs: Long = 0L
    }

    private var now = 1_000_000L
    private var idCounter = 0
    private lateinit var sessions: SessionManager

    @Before
    fun setup() {
        sessions = SessionManager(FakeStore(), { now }, { "session-${++idCounter}" })
    }

    @Test
    fun processStartOpensFirstSession() {
        val transition = sessions.onProcessStart()
        assertTrue(transition is SessionManager.Transition.Started)
        assertEquals("session-1", sessions.currentSessionId)
    }

    @Test
    fun processStartContinuesExistingSession() {
        sessions.onProcessStart()
        val again = sessions.onProcessStart()
        assertEquals(SessionManager.Transition.Continued("session-1"), again)
    }

    @Test
    fun foregroundWithinTimeoutContinuesSameSession() {
        sessions.onProcessStart()
        sessions.onBackground()
        now += 10 * 60 * 1000L // 10 minutes in background
        assertEquals(SessionManager.Transition.Continued("session-1"), sessions.onForeground())
        assertEquals("session-1", sessions.currentSessionId)
    }

    @Test
    fun foregroundAfterTimeoutEndsOldSessionAndStartsNew() {
        sessions.onProcessStart()
        val backgroundedAt = now
        sessions.onBackground()
        now += 31 * 60 * 1000L // past the 30-minute timeout
        val transition = sessions.onForeground()
        assertEquals(
            SessionManager.Transition.Renewed("session-1", backgroundedAt + SessionManager.DEFAULT_TIMEOUT_MS, "session-2"),
            transition
        )
        assertEquals("session-2", sessions.currentSessionId)
    }

    @Test
    fun foregroundExactlyAtTimeoutRenews() {
        sessions.onProcessStart()
        sessions.onBackground()
        now += SessionManager.DEFAULT_TIMEOUT_MS
        assertTrue(sessions.onForeground() is SessionManager.Transition.Renewed)
    }

    @Test
    fun processStartRejectsExpiredSessionBeforeReturningItsId() {
        sessions.onProcessStart()
        val backgroundedAt = now
        sessions.onBackground()
        now += 31 * 60 * 1000L
        assertEquals(
            SessionManager.Transition.Renewed(
                "session-1", backgroundedAt + SessionManager.DEFAULT_TIMEOUT_MS, "session-2"
            ),
            sessions.onProcessStart()
        )
        assertEquals("session-2", sessions.currentSessionId)
        assertEquals(SessionManager.Transition.Continued("session-2"), sessions.onForeground())
    }

    @Test
    fun longForegroundDoesNotCountAsBackgroundTimeout() {
        sessions.onProcessStart()
        sessions.onForeground()
        now += 31 * 60 * 1000L
        assertEquals(SessionManager.Transition.Continued("session-1"), sessions.onForeground())
    }

    @Test
    fun delayedWorkerUsesForegroundObservationTime() {
        sessions.onProcessStart()
        sessions.onBackground()
        now += 10 * 60 * 1000L
        val foregroundAt = now
        now += 31 * 60 * 1000L
        assertEquals(SessionManager.Transition.Continued("session-1"), sessions.onForeground(foregroundAt))
    }

    @Test
    fun backgroundStampsLastForegroundTime() {
        sessions.onProcessStart()
        now += 5_000L
        sessions.onBackground()
        now += 10_000L
        // Returning 15s later continues; expiry is measured from background time.
        assertTrue(sessions.onForeground() is SessionManager.Transition.Continued)
    }
}
