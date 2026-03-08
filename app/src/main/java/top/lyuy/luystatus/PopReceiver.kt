package top.lyuy.luystatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.HttpException
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.notify.NotificationHelper
import androidx.core.content.edit
import top.lyuy.luystatus.queue.QueueWorker

class PopReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PopReceiver"
        private const val PREF_NAME = "queue_worker"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_CONFIRMED_INDEX = "last_confirmed_index"
        private const val KEY_PENDING_INDEX = "pending_index"
        private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"
    }

    override fun onReceive(context: Context, intent: Intent) {

        val sessionId = intent.getIntExtra(
            NotificationHelper.EXTRA_SESSION_ID,
            -1
        )
        val index = intent.getLongExtra(
            NotificationHelper.EXTRA_INDEX,
            -1L
        )

        if (sessionId < 0 || index < 0) {
            Log.w(TAG, "invalid action params")
            return
        }

        val prefs = context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val currentSession = prefs.getInt(KEY_SESSION_ID, 0)
        val pending = prefs.getLong(KEY_PENDING_INDEX, -1L)

        //  只允许确认当前 pending 的事件
        if (sessionId != currentSession || index != pending) {
            Log.w(
                TAG,
                "stale confirm ignored s=$sessionId i=$index pending=$pending"
            )
            return
        }

        /**
         * BroadcastReceiver 生命周期极短
         * 必须立即把耗时操作放到后台线程
         */
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiProvider.api.pop()

                if (response.success) {
                    onConfirmed(context)
                }

            } catch (e: HttpException) {
                when (e.code()) {
                    404 -> {
                        onConfirmed(context)
                    }

                    else -> {
                        Log.e(TAG, "pop failed", e)
                        // 失败：什么都不做，保留通知
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "pop exception", e)
                // 网络异常：保留通知，允许用户重试
            }
        }
    }

    private fun onConfirmed(context: Context) {
        val prefs = context
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val pending = prefs.getLong(KEY_PENDING_INDEX, -1L)
        if (pending < 0) return

        prefs.edit {
            putLong(KEY_LAST_CONFIRMED_INDEX, pending)
                .putLong(KEY_PENDING_INDEX, -1L)
                .putLong(KEY_LAST_NOTIFY_TIME, 0L)
        }

        NotificationHelper.cancelQueueNotification(context)

        WorkManager.getInstance(context)
            .enqueue(
                OneTimeWorkRequestBuilder<QueueWorker>().build()
            )
    }
}

