package com.adobe.marketing.mobile.telemetry

import com.adobe.marketing.mobile.telemetry.internal.CoreNetworkTransport
import org.junit.Assert.*
import org.junit.Test

class RetryAfterTest {
    private val transport = CoreNetworkTransport()

    @Test fun handlesSecondsAndHttpDate() {
        assertEquals(12_000L, transport.parseRetryAfter("12", 0))
        assertEquals(12_000L, transport.parseRetryAfter("Thu, 01 Jan 1970 00:00:12 GMT", 0))
    }

    @Test fun rejectsNegativeAndMalformedValues() {
        assertNull(transport.parseRetryAfter("-1", 0))
        assertNull(transport.parseRetryAfter("not-a-date", 0))
        assertEquals(0L, transport.parseRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT", 1000))
    }

    @Test fun excessiveValuesStayWithinCompiledRetryLimit() {
        assertEquals(CoreNetworkTransport.MAX_RETRY_AFTER_MS,
            transport.parseRetryAfter(Long.MAX_VALUE.toString(), 0))
    }
}
