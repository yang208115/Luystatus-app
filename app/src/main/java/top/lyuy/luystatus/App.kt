package top.lyuy.luystatus

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.queue.QueueWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ApiProvider.init(this)
        startQueueWorker()
    }

    private fun startQueueWorker() {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "queue_poll_worker",
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
