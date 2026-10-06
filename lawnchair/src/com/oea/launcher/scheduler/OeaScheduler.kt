package com.oea.launcher.scheduler

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * OEA background initiation boundary.
 *
 * WorkManager owns delivery/retry. OEA only defines what work should be scheduled.
 */
object OeaScheduler {
    private const val UNIQUE_WORK = "oea-maintenance"

    fun initialize(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OeaMaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
