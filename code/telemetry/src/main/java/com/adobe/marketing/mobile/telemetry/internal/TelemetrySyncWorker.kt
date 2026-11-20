package com.adobe.marketing.mobile.telemetry.internal

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager retry entry for failed uploads. Enqueued as unique work with exponential
 * backoff (jittered; Retry-After honored by Sync when larger). Network-connected
 * constraint is set at enqueue time.
 */
class TelemetrySyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (Sync.runFromWorker(applicationContext)) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}
