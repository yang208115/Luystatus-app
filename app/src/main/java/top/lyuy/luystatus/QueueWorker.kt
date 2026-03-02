package top.lyuy.luystatus

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.notify.NotificationHelper
import java.util.concurrent.TimeUnit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class QueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "QueueWorker"
        private const val PREF_NAME = "queue_worker"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_CONFIRMED_INDEX = "last_confirmed_index"
        private const val KEY_PENDING_INDEX = "pending_index"
        private const val KEY_FIRST_NOTIFY_TIME = "first_notify_time"
        private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueWorker>()
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "QueueWorkerImmediate",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }

    /**
     * WorkManager 的核心执行入口。
     *
     * 职责说明：
     * - 轮询后端 Queue API
     * - 维护本地队列状态（session / pending / confirmed）
     * - 根据时间与状态决定是否发送通知
     * - 保证通知频率受控，避免刷屏
     * - 在任何结果下都调度下一次轮询
     *
     * 设计原则：
     * - 本 Worker 使用 OneTimeWork + 自行 enqueue 的方式模拟定时任务，
     *   以获得更精细的失败重试与调度控制
     *
     * 注意：
     * - Worker 可能在任意线程 / 任意时间被系统调用
     * - 任何异常都会触发 retry，但不会中断后续轮询的调度
     */

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val prefs = applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        var result: Result = Result.success()

        try {
            val listResponse = ApiProvider.api.list()
            val items = listResponse.items
            if (items.isEmpty()) {
                result = Result.success()
                Log.e(TAG,"list列表为空")
                Log.e(TAG,"item=$items")
            } else {
                var sessionId = prefs.getInt(KEY_SESSION_ID, 0)
                var lastConfirmed = prefs.getLong(KEY_LAST_CONFIRMED_INDEX, -1L)
                var pending = prefs.getLong(KEY_PENDING_INDEX, -1L)

                val firstNotifyTime = prefs.getLong(KEY_FIRST_NOTIFY_TIME, 0L)
                val lastNotifyTime = prefs.getLong(KEY_LAST_NOTIFY_TIME, 0L)
                val now = System.currentTimeMillis()

                val maxIndex = items.maxOf { it.index }

                if (maxIndex < lastConfirmed) {
                    sessionId++
                    lastConfirmed = -1
                    pending = -1
                    prefs.edit {
                        putInt(KEY_SESSION_ID, sessionId)
                        putLong(KEY_LAST_CONFIRMED_INDEX, lastConfirmed)
                        putLong(KEY_PENDING_INDEX, pending)
                    }
                }

                if (pending == -1L) {
                    val nextItem = items
                        .filter { it.index > lastConfirmed }
                        .minByOrNull { it.index }

                    if (nextItem == null) {
                        result = Result.success()
                    } else {
                        pending = nextItem.index.toLong()
                        prefs.edit {
                            putLong(KEY_PENDING_INDEX, pending)
                            putLong(KEY_FIRST_NOTIFY_TIME, 0L)
                            putLong(KEY_LAST_NOTIFY_TIME, 0L)
                        }
                    }
                }

                // 每 10s 最多提醒一次
                if (lastNotifyTime != 0L && now - lastNotifyTime < 10_000L) {
                    result = Result.success()
                } else {
                    val item = items.firstOrNull { it.index.toLong() == pending }
                    if (item == null) {
                        prefs.edit {
                            putLong(KEY_PENDING_INDEX, -1L)
                            putLong(KEY_FIRST_NOTIFY_TIME, 0L)
                            putLong(KEY_LAST_NOTIFY_TIME, 0L)
                        }
                        result = Result.success()
                    } else {
                        val channelId = when {
                            firstNotifyTime == 0L -> {
                                prefs.edit { putLong(KEY_FIRST_NOTIFY_TIME, now) }
                                NotificationHelper.CHANNEL_NEW
                            }

                            now - firstNotifyTime >= 10 * 60_000L ->
                                NotificationHelper.CHANNEL_URGENT

                            else ->
                                NotificationHelper.CHANNEL_REPEAT
                        }

                        val content = buildNotificationContent(item.data)

                        NotificationHelper.showQueueNotification(
                            context = applicationContext,
                            channelId = channelId,
                            content = content,
                            index = pending,
                            sessionId = sessionId
                        )

                        prefs.edit {
                            putLong(KEY_LAST_NOTIFY_TIME, now)
                        }

                        result = Result.success()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queue poll failed", e)
            result = Result.retry()
        }

        //  只在一个地方 enqueue
        enqueueNext()

        return result
    }


    /**
     * 构造通知展示内容
     * 注意：
     * - 此方法刻意不递归解析 JSON
     * - 不保证字段顺序，仅保证信息可读和行为稳定
     */
    private fun buildNotificationContent(data: JsonObject?): String {
        Log.d(TAG, "raw data:$data")

        if (data == null || data.entrySet().isEmpty()) {
            return "收到一个 Queue 事件"
        }

        val timestamp = data.get("timestamp")?.let {
            when {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber ->
                    it.asLong
                it.isJsonPrimitive && it.asJsonPrimitive.isString ->
                    parseIsoTime(it.asString)
                else -> null
            }
        }

        val name =
            data.getStringOrNull("name")
                ?: data.get("site")
                    ?.asJsonObject
                    ?.getStringOrNull("name")

        val customMessage =
            data.getStringOrNull("customMessage")

        if (timestamp != null && !name.isNullOrBlank()) {
            return buildString {
                append("时间：").append(formatTime(timestamp)).append('\n')
                append("名称：").append(name)
                if (!customMessage.isNullOrBlank()) {
                    append('\n')
                    append("消息：").append(customMessage)
                }
            }
        }


        // fallback：稳定 key:value 输出
        return buildString {
            data.entrySet().forEachIndexed { index, entry ->
                append(entry.key).append(": ")
                append(renderJsonValue(entry.value))
                if (index != data.entrySet().size - 1) append('\n')
            }
        }
    }


    //将 JsonElement 渲染为稳定、可读的字符串
    private fun renderJsonValue(value: JsonElement): String =
        when {
            value.isJsonNull -> "null"
            value.isJsonPrimitive -> {
                val p = value.asJsonPrimitive
                when {
                    p.isString -> p.asString
                    p.isNumber -> p.asNumber.toString()
                    p.isBoolean -> p.asBoolean.toString()
                    else -> p.toString()
                }
            }

            else -> value.toString()
        }

    //转换数字时间戳与ISO字符串
    private fun parseIsoTime(value: String): Long? =
        try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            null
        }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        val p = e.asJsonPrimitive
        if (!p.isString) return null
        return p.asString
    }


    //25s轮询一次
     fun enqueueNext() {
        //Log.d("QueueWorker", "enqueue from:\n" + Log.getStackTraceString(Throwable()))
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "QueueWorker",
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
