package app.lawnchair.oea.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.lawnchair.oea.data.OeaDataStore

/**
 * Small, safe background heartbeat. It performs no network or heavyweight AI work.
 */
class OeaMaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = OeaDataStore.get(applicationContext)
        store.lastCommand()
        return Result.success()
    }
}
