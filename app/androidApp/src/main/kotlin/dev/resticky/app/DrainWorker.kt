package dev.resticky.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        log("INFO drain work started")
        (applicationContext as HubApp).pipeline.drain()
        return Result.success()
    }
}
