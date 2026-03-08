package top.lyuy.luystatus

import android.app.Application
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.queue.QueueWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ApiProvider.init(this)
        QueueWorker.enqueuePeriodic(this)
    }
}
