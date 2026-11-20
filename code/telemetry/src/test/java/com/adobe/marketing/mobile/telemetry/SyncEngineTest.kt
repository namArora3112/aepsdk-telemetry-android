package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.AutoEvent
import com.adobe.marketing.mobile.telemetry.internal.EventQueue
import com.adobe.marketing.mobile.telemetry.internal.QueuedEvent
import com.adobe.marketing.mobile.telemetry.internal.SyncEngine
import com.adobe.marketing.mobile.telemetry.internal.Transport
import com.adobe.marketing.mobile.telemetry.internal.UploadResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncEngineTest {

    private class FakeQueue : EventQueue {
        val events = mutableListOf<QueuedEvent>()
        var nextId = 1L
        override fun enqueue(event: AutoEvent) {
            events.add(QueuedEvent(nextId++, event))
        }
        override fun peekChunk(maxEvents: Int): List<QueuedEvent> = events.take(maxEvents)
        override fun delete(ids: Collection<Long>) {
            events.removeAll { it.id in ids }
        }
        override fun clear() { events.clear() }
        override fun size(): Int = events.size
    }

    private class FakeTransport(vararg val scripted: UploadResult) : Transport {
        val bodies = mutableListOf<String>()
        var index = 0
        override fun post(settings: SyncSettings, body: String): UploadResult {
            bodies.add(body)
            return if (index < scripted.size) scripted[index++] else UploadResult.Accepted
        }
    }

    private lateinit var queue: FakeQueue
    private var consent = ConsentStatus.OPTED_IN
    private var settings = SyncSettings(SyncSettings.DEFAULT_ENDPOINT, "key", batchSize = 2)
    private val retries = mutableListOf<Long>()
    private val warnings = mutableListOf<String>()
    private var uploadsEnabled = true

    private fun engine(transport: Transport) = SyncEngine(
        queue = queue,
        transport = transport,
        consent = { consent },
        settings = { settings },
        warn = { warnings.add(it) },
        scheduleRetry = { retries.add(it) },
        uploadsEnabled = { uploadsEnabled }
    )

    @Before
    fun setup() {
        queue = FakeQueue()
        consent = ConsentStatus.OPTED_IN
        settings = SyncSettings(SyncSettings.DEFAULT_ENDPOINT, "key", batchSize = 2)
        retries.clear()
        warnings.clear()
        uploadsEnabled = true
    }

    private fun enqueue(count: Int) {
        repeat(count) { queue.enqueue(AutoEvent("app_start", 1000L + it, "s1")) }
    }

    @Test
    fun pausedUploadsKeepCollectingAndResumeDrainsTheSameRows() {
        uploadsEnabled = false
        enqueue(3)
        val transport = FakeTransport()
        val engine = engine(transport)
        assertEquals(SyncEngine.Outcome.Buffering, engine.drain())
        assertTrue(transport.bodies.isEmpty())
        enqueue(1)
        assertEquals(4, queue.size())
        uploadsEnabled = true
        assertEquals(SyncEngine.Outcome.Drained, engine.drain())
        assertEquals(2, transport.bodies.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun pauseDuringRequestRetainsAcknowledgedRowsUntilResume() {
        enqueue(2)
        val transport = object : Transport {
            override fun post(settings: SyncSettings, body: String): UploadResult {
                uploadsEnabled = false
                return UploadResult.Accepted
            }
        }
        assertEquals(SyncEngine.Outcome.Buffering, engine(transport).drain())
        assertEquals(2, queue.size())
        assertTrue(retries.isEmpty())
    }

    @Test
    fun invalidConfigKeepsBufferingAndWarns() {
        settings = SyncSettings("https://evil.example.com/x", "key")
        enqueue(3)
        val outcome = engine(FakeTransport()).drain()
        assertEquals(SyncEngine.Outcome.InvalidConfig, outcome)
        assertEquals(3, queue.size())
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun blankApiKeyKeepsBuffering() {
        settings = SyncSettings(SyncSettings.DEFAULT_ENDPOINT, " ")
        enqueue(1)
        assertEquals(SyncEngine.Outcome.InvalidConfig, engine(FakeTransport()).drain())
        assertEquals(1, queue.size())
    }

    @Test
    fun optedOutClearsQueueAndUploadsNothing() {
        consent = ConsentStatus.OPTED_OUT
        enqueue(5)
        val transport = FakeTransport()
        assertEquals(SyncEngine.Outcome.OptedOut, engine(transport).drain())
        assertEquals(0, queue.size())
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun unknownConsentKeepsBuffering() {
        consent = ConsentStatus.UNKNOWN
        enqueue(5)
        val transport = FakeTransport()
        assertEquals(SyncEngine.Outcome.Buffering, engine(transport).drain())
        assertEquals(5, queue.size())
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun optedInDrainsInChunksAndDeletesOnlyAfterSuccess() {
        enqueue(5) // batchSize 2 -> chunks of 2, 2, 1
        val transport = FakeTransport()
        assertEquals(SyncEngine.Outcome.Drained, engine(transport).drain())
        assertEquals(0, queue.size())
        assertEquals(3, transport.bodies.size)
        transport.bodies.forEach { body ->
            assertEquals(0, JSONObject(body).getInt("configVersion"))
        }
    }

    @Test
    fun retryableFailureKeepsChunkAndSchedulesRetry() {
        enqueue(4)
        val transport = FakeTransport(UploadResult.Retryable(12_000L))
        val outcome = engine(transport).drain()
        assertEquals(SyncEngine.Outcome.Failed(12_000L), outcome)
        assertEquals(4, queue.size()) // nothing deleted
        assertEquals(listOf(12_000L), retries) // Retry-After honored
    }

    @Test
    fun rejectedHeadStopsDrainWithoutDeleting() {
        enqueue(4)
        val transport = FakeTransport(UploadResult.Rejected)
        assertEquals(SyncEngine.Outcome.RejectedHead, engine(transport).drain())
        assertEquals(4, queue.size())
        assertTrue(retries.isEmpty())
    }

    @Test
    fun consentIsRecheckedBeforeEveryChunk() {
        enqueue(4)
        var posts = 0
        val transport = object : Transport {
            override fun post(settings: SyncSettings, body: String): UploadResult {
                posts++
                if (posts == 1) consent = ConsentStatus.UNKNOWN // flips mid-drain
                return UploadResult.Accepted
            }
        }
        val outcome = engine(transport).drain()
        assertEquals(SyncEngine.Outcome.Buffering, outcome)
        assertEquals(1, posts)          // stopped before the second chunk
        assertEquals(2, queue.size())  // only the accepted chunk was deleted
    }

    @Test
    fun emptyQueueDrainsImmediately() {
        assertEquals(SyncEngine.Outcome.Drained, engine(FakeTransport()).drain())
    }

    @Test fun optOutPurgesEvenWhenConfigurationIsInvalid() {
        consent = ConsentStatus.OPTED_OUT
        settings = settings.copy(apiKey = "")
        enqueue(3)
        assertEquals(SyncEngine.Outcome.OptedOut, engine(FakeTransport()).drain())
        assertEquals(0, queue.size())
    }

    @Test fun optOutDuringUploadPurgesRemainingChunks() {
        enqueue(4)
        var posts = 0
        val transport = object : Transport {
            override fun post(settings: SyncSettings, body: String): UploadResult {
                posts++
                consent = ConsentStatus.OPTED_OUT
                return UploadResult.Accepted
            }
        }
        assertEquals(SyncEngine.Outcome.OptedOut, engine(transport).drain())
        assertEquals(1, posts)
        assertEquals(0, queue.size())
    }

    @Test fun settingsAreReadAgainForEachChunk() {
        enqueue(5)
        val sizes = mutableListOf<Int>()
        val versions = mutableListOf<Int>()
        val transport = object : Transport {
            override fun post(active: SyncSettings, body: String): UploadResult {
                sizes.add(JSONObject(body).getJSONArray("events").length())
                versions.add(JSONObject(body).getInt("configVersion"))
                settings = settings.copy(batchSize = 1, configVersion = 7)
                return UploadResult.Accepted
            }
        }
        engine(transport).drain()
        assertEquals(listOf(2, 1, 1, 1), sizes)
        assertEquals(listOf(0, 7, 7, 7), versions)
    }

    @Test fun endpointWithUserInfoIsInvalid() {
        settings = settings.copy(endpoint = "https://user:password@edge-int.adobedc.net/x")
        enqueue(1)
        assertEquals(SyncEngine.Outcome.InvalidConfig, engine(FakeTransport()).drain())
        assertEquals(1, queue.size())
    }
}
