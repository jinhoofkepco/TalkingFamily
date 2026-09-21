package kr.family.homeway.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = AppRepository(applicationContext)
        if (!repo.configured) return Result.success()
        repo.flush()
        return if (repo.hasPending()) Result.retry() else Result.success()
    }
}
