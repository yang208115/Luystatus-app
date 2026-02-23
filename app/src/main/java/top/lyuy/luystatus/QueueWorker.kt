package top.lyuy.luystatus

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.notify.NotificationHelper
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant


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
        private const val KEY_LAST_INDEX = "last_handled_index"
        private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        val prefs = applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        try {
            val listResponse = ApiProvider.api.list()
            val items = listResponse.items

            if (items.isEmpty()) return Result.success()

            var sessionId = prefs.getInt(KEY_SESSION_ID, 0)
            var lastConfirmed = prefs.getLong(KEY_LAST_CONFIRMED_INDEX, -1L)
            var pending: Long = prefs.getLong(KEY_PENDING_INDEX, -1L)
            val lastNotifyTime = prefs.getLong(KEY_LAST_NOTIFY_TIME, 0L)
            val now = System.currentTimeMillis()

            val maxIndex = items.maxOf { it.index }

            //  index 回退 → 新 session
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

            // 如果当前没有 pending，找下一个
            if (pending == -1L) {
                val next = items
                    .filter { it.index > lastConfirmed }
                    .minByOrNull { it.index }

                if (next == null) return Result.success()

                pending = next.index.toLong()
                prefs.edit {
                    putLong(KEY_PENDING_INDEX, pending)
                    putLong(KEY_LAST_NOTIFY_TIME, 0L)
                }
            }

            // 判断是否需要提醒，每60s至多一次
            val shouldNotify =
                lastNotifyTime == 0L || now - lastNotifyTime >= 60_000L

            if (shouldNotify) {
                val item = items.firstOrNull { it.index.toLong() == pending }
                    ?: run {
                        // 如果已被其他程序 pop 掉
                        prefs.edit {
                            putLong(KEY_PENDING_INDEX, -1L)
                            putLong(KEY_LAST_NOTIFY_TIME, 0L)
                        }
                        return Result.success()
                    }

                val content = buildNotificationContent(item.data)

                NotificationHelper.showQueueNotification(
                    context = applicationContext,
                    content = content,
                    index = pending,
                    sessionId = sessionId
                )

                prefs.edit {
                    putLong(KEY_LAST_NOTIFY_TIME, now)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "queue poll failed", e)
        } finally {
            enqueueNext()
        }

        return Result.success()
    }


    /**
     * 构造通知展示内容
     * 注意：
     * - 此方法刻意不递归解析 JSON
     * - 不保证字段顺序，仅保证信息可读和行为稳定
     */


    private fun buildNotificationContent(data: JsonObject?): String {
        Log.d(TAG, "Build notification content, raw data=$data")

        if (data == null || data.entrySet().isEmpty()) {
            Log.w(TAG, "Queue data is null or empty")
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
            data.getAsJsonPrimitive("name")?.asString
                ?: data.getAsJsonObject("site")
                    ?.getAsJsonPrimitive("name")
                    ?.asString

        val customMessage =
            data.getAsJsonPrimitive("customMessage")?.asString
        //同时存在这些字段时
        if (timestamp != null && !name.isNullOrBlank() && !customMessage.isNullOrBlank()) {
            Log.d(TAG, "Use strict protocol format")

            return buildString {
                append("时间：")
                append(formatTime(timestamp))
                append('\n')
                append("名称：")
                append(name)
                append('\n')
                append("消息：")
                append(customMessage)
            }
        }

        //  否则
        return buildString {
            val entries = data.entrySet().toList()
            entries.forEachIndexed { index, entry ->
                append(entry.key)
                append("：")
                append(renderJsonValue(entry.value))
                if (index != entries.lastIndex) append('\n')
            }
        }
    }

    private fun renderJsonValue(value: JsonElement): String {
        return when {
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
            else -> value.toString() // object / array
        }
    }

    //ISO-8601 字符串转换
    private fun parseIsoTime(value: String): Long? {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
    //格式化时间
    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        )
        return sdf.format(Date(timestamp))
    }

    // 每25s轮询一次
    private fun enqueueNext() {
        val request = OneTimeWorkRequestBuilder<QueueWorker>()
            .setInitialDelay(25, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueue(request)
    }
}
