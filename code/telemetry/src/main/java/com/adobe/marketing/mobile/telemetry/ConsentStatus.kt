package com.adobe.marketing.mobile.telemetry

/**
 * Consent gate for the sync pipeline. Mirrors the host's privacy state; the SDK never
 * infers consent on its own.
 */
enum class ConsentStatus {
    /** Purge the queue and stop collecting immediately. */
    OPTED_OUT,
    /** Keep buffering locally; re-evaluated on every consent change. */
    UNKNOWN,
    /** Collection and upload may proceed. */
    OPTED_IN
}
