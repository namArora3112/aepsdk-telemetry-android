package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.AutoEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutoEventTest {

    @Test
    fun jsonRoundTripPreservesFields() {
        val event = AutoEvent("app_start", 1234L, "session-1", mapOf("cold_start_ms" to "42"))
        val restored = AutoEvent.fromJson(JSONObject(event.toJson().toString()))
        assertEquals(event, restored)
    }

    @Test
    fun rejectsPersonalOrOversizedPropertyShapes() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoEvent("app_start", 1L, "s", mapOf("Email" to "x")) // uppercase key rejected
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoEvent("app_start", 1L, "s", mapOf("ok" to "v".repeat(500)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutoEvent("app_start", 1L, "s", (1..17).associate { "k$it" to "v" })
        }
    }

    @Test
    fun rejectsBlankIdentityFields() {
        assertThrows(IllegalArgumentException::class.java) { AutoEvent("", 1L, "s") }
        assertThrows(IllegalArgumentException::class.java) { AutoEvent("app_start", 1L, "") }
        assertThrows(IllegalArgumentException::class.java) { AutoEvent("app_start", 0L, "s") }
    }
}
