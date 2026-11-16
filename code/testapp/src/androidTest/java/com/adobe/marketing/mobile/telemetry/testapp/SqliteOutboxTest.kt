package com.adobe.marketing.mobile.telemetry.testapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import com.adobe.marketing.mobile.telemetry.internal.SqliteOutbox
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteOutboxTest {
    private fun record(id: String, ts: Long = 100) = TelemetryRecord(
        id, ts, "sdk", "reviewed.event", "event", "hubProjection", requestId = id)

    @Before
    fun setup() {
        SqliteOutbox(ApplicationProvider.getApplicationContext()).clear()
    }

    @Test
    fun recordsSurviveOutboxRecreationLikeProcessRestart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = SqliteOutbox(context)
        first.insert(record("durable-1", 100))
        first.insert(record("durable-2", 200))
        // A new instance over the same database file simulates process death + relaunch.
        val second = SqliteOutbox(context)
        assertEquals(listOf("durable-1", "durable-2"), second.pending().map { it.recordId })
        assertEquals(100L, second.oldestTimestamp())
    }

    @Test
    fun peekBatchCapsBytesAndRemoveDeletesOnlySent() {
        val outbox = SqliteOutbox(ApplicationProvider.getApplicationContext())
        repeat(5) { outbox.insert(record("r$it")) }
        val batch = outbox.peekBatch(500)
        assertTrue(batch.isNotEmpty())
        assertTrue(batch.size < 5)
        outbox.remove(batch.map { it.recordId })
        assertEquals(5 - batch.size, outbox.count())
        outbox.clear()
        assertEquals(0, outbox.count())
    }
}
