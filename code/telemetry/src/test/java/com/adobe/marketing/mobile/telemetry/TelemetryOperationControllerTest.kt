package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.plugin.TelemetryApiCapability
import com.adobe.marketing.mobile.plugin.TelemetryApiRecord
import com.adobe.marketing.mobile.telemetry.internal.TelemetryOperationController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TelemetryOperationControllerTest {
    private var now = 0L
    private val operations = mutableListOf<List<Any?>>()
    private val apiRecords = mutableListOf<TelemetryApiRecord>()
    private val controller = TelemetryOperationController(
        { id, producer, name, phase, outcome, meaning, duration ->
            operations += listOf(id, producer, name, phase, outcome, meaning, duration)
        },
        { apiRecords += it },
        { now }
    )

    @Test fun operationCorrelationAndDurationStayInTelemetry() {
        val id = controller.operationStarted("sdk.messaging", "refresh")
        assertTrue(id!!.isNotBlank())
        now += TimeUnit.MILLISECONDS.toNanos(15)
        controller.operationCompleted(id, "sdk.messaging", "refresh", "success", "completed")
        assertEquals(listOf("started", "completed"), operations.map { it[3] })
        assertEquals(id, operations[1][0])
        assertEquals("success", operations[1][4])
        assertEquals(15L, operations[1][6])
    }

    @Test fun completionMustMatchTheActiveOperation() {
        val id = controller.operationStarted("sdk.messaging", "refresh")!!
        controller.operationCompleted(id, "wrong", "refresh", "success", "")
        controller.operationCompleted(id, "sdk.messaging", "wrong", "success", "")
        assertEquals(1, operations.size)
        controller.operationCompleted(id, "sdk.messaging", "refresh", "failure", "failed")
        assertEquals(2, operations.size)
    }

    @Test fun declaredCapabilityControlsEntryAndOutcome() {
        assertTrue(controller.declareApiCapabilities(
            "sdk.messaging",
            listOf(TelemetryApiCapability(
                "refreshInAppMessages", "1.2.3",
                outcomes = setOf("success", "failure"), outcomeContract = "inbox.refresh"
            ))
        ))
        val id = controller.apiStarted("sdk.messaging", "refreshInAppMessages")
        assertTrue(id!!.isNotBlank())
        now += TimeUnit.MILLISECONDS.toNanos(7)
        controller.apiCompleted(id, "sdk.messaging", "refreshInAppMessages", "success")
        assertNull(controller.apiStarted("sdk.messaging", "unknown"))
        assertEquals(listOf("started", "completed"), apiRecords.map { it.phase })
        assertEquals(7L, apiRecords.last().durationMs)
        assertEquals("inbox.refresh", apiRecords.last().outcomeContract)
    }

    @Test fun invalidDeclarationsDoNotReplaceCurrentPolicy() {
        assertTrue(controller.declareApiCapabilities(
            "sdk.messaging", listOf(capability("valid"))
        ))
        assertFalse(controller.declareApiCapabilities(
            "sdk.messaging", listOf(capability("invalid").copy(outcomeContract = null))
        ))
        assertEquals(0, controller.migrationStatus()["declaredApis"])
        assertNull(controller.apiStarted("sdk.messaging", "valid"))
    }

    @Test fun expiryIsReportedAsUnknownObservation() {
        controller.declareApiCapabilities(
            "sdk.messaging", listOf(capability("refresh").copy(observationTtlMs = 10))
        )
        val id = controller.apiStarted("sdk.messaging", "refresh")!!
        now += TimeUnit.MILLISECONDS.toNanos(10)
        controller.expireApiOperations()
        assertEquals("unknown", apiRecords.last().outcome)
        assertEquals("completion_not_observed", apiRecords.last().meaning)
        controller.apiCompleted(id, "sdk.messaging", "refresh", "success")
        assertEquals(2, apiRecords.size)
        assertEquals(1L, controller.migrationStatus()["unknownOutcomes"])
        assertEquals(0, controller.migrationStatus()["pendingApis"])
    }

    @Test fun captureModeAndUnsupportedValuesAreSDKOwned() {
        assertFalse(controller.setCaptureMode("unexpected"))
        assertEquals("direct_only", controller.migrationStatus()["captureMode"])
        assertTrue(controller.setCaptureMode("hybrid"))
        assertEquals("hybrid", controller.migrationStatus()["captureMode"])
    }

    private fun capability(name: String) = TelemetryApiCapability(
        name, "1.2.3", outcomes = setOf("success", "failure"),
        outcomeContract = "sdk.contract"
    )
}
