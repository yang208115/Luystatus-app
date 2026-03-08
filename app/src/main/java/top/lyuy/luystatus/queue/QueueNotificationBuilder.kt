package top.lyuy.luystatus.queue


import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

object QueueNotificationBuilder {

    private const val TAG = "QueueNotificationBuilder"

    /**
     * 构造通知展示内容
     *
     * 注意：
     * - 不递归解析 JSON
     * - 不保证字段顺序
     */
    fun buildNotification(data: JsonObject?): String {
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

        val errorMessage =
            data.getStringOrNull("errorMessage")

        val statusText: String? =
            data.get("status")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { statusObj ->
                    val current = statusObj.getStringOrNull("current")
                    val previous = statusObj.getStringOrNull("previous")

                    when {
                        !current.isNullOrBlank() && !previous.isNullOrBlank() ->
                            "$previous → $current"

                        !current.isNullOrBlank() ->
                            current

                        else -> null
                    }
                }


        if (timestamp != null && !name.isNullOrBlank()) {
            return buildString {
                appendLine("时间：${formatTime(timestamp)}")
                append("名称：").append(name)

                appendOptionalLine("状态", statusText)
                appendOptionalLine("错误消息", errorMessage)
                appendOptionalLine("消息", customMessage)
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

    // 将 JsonElement 渲染为稳定、可读的字符串
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

    // 转换 ISO 时间
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
}

    private fun StringBuilder.appendOptionalLine(
        label: String,
        value: String?
    ) {
        if (!value.isNullOrBlank()) {
            append('\n')
            append(label).append("：").append(value)
        }
    }
