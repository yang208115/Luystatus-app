package top.lyuy.luystatus.queue


import android.content.SharedPreferences
import top.lyuy.luystatus.notify.NotificationHelper
import top.lyuy.luystatus.api.ListItem
import androidx.core.content.edit

object QueueStateMachine {

    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_LAST_CONFIRMED_INDEX = "last_confirmed_index"
    private const val KEY_PENDING_INDEX = "pending_index"
    private const val KEY_FIRST_NOTIFY_TIME = "first_notify_time"
    private const val KEY_LAST_NOTIFY_TIME = "last_notify_time"

    data class Decision(
        val shouldNotify: Boolean,
        val pendingIndex: Long?,
        val sessionId: Int,
        val channelId: String?
    )

    /**
     * 队列状态机的核心决策入口。
     *
     * 职责概述：
     * - 读取并维护队列的持久化状态（session / pending / notify 时间）
     * - 根据当前队列列表与时间，推进队列状态
     * - 决定本轮是否需要发送通知，以及通知的展示级别
     *
     * 输入说明：
     * - prefs：
     *   用于存储和读取队列状态的 SharedPreferences，
     *   状态机会在内部直接更新其内容
     *
     * - items：
     *   当前从后端获取的 Queue 列表快照，
     *   被视为“事实来源（source of truth）”
     *
     * - now：
     *   当前时间戳（毫秒），由 Worker 统一提供，
     *   用于通知频率控制与超时判断
     *
     * 状态字段语义：
     * - session_id：
     *   队列生命周期标识。
     *   当检测到后端 index 回退（maxIndex < lastConfirmed）时递增，
     *   表示进入一个新的队列会话
     *
     * - last_confirmed_index：
     *   已确认处理完成的最大 index，
     *   用于在 session 内定位下一个待处理项
     *
     * - pending_index：
     *   当前正在等待用户处理、尚未确认完成的 index，
     *   同一时间只会存在一个 pending 项
     *
     * - first_notify_time：
     *   当前 pending 项第一次触发通知的时间，
     *   用于判断是否需要升级为紧急通知
     *
     * - last_notify_time：
     *   最近一次发送通知的时间，
     *   用于限制通知频率，避免刷屏
     *
     * 决策流程：
     * 1. 若列表为空，直接返回“不通知”
     * 2. 检测 index 是否回退，必要时重置 session 与状态
     * 3. 若当前无 pending 项，选择下一个待处理 index 作为 pending
     * 4. 校验 pending 是否仍存在于当前列表中，不存在则清理状态
     * 5. 判断是否命中通知频率限制（10 秒）
     * 6. 根据首次通知时间与已持续时间，选择通知通道：
     *    - 新任务通知
     *    - 重复提醒
     *    - 紧急提醒
     * 7. 更新通知相关时间戳并返回决策结果
     *
     * 输出说明：
     * - Decision.shouldNotify：
     *   表示本轮是否需要发送通知
     *
     * - Decision.pendingIndex：
     *   需要展示的队列项 index，仅在 shouldNotify == true 时有效
     *
     * - Decision.sessionId：
     *   当前队列会话 ID，用于通知去重与展示
     *
     * - Decision.channelId：
     *   本轮通知应使用的 Notification Channel，
     *   仅在 shouldNotify == true 时有效
     *
     * 设计原则：
     * - evaluate() 是一个“有副作用的状态机”：
     *   - 会读取并更新 SharedPreferences
     *   - 但不会触发任何 I/O 或 UI 行为
     *
     * - 状态推进与通知决策集中在此处，
     *   保证 Worker 本身保持简单、可读
     */
    fun evaluate(
        prefs: SharedPreferences,
        items: List<ListItem>,
        now: Long
    ): Decision {

        var sessionId = prefs.getInt(KEY_SESSION_ID, 0)
        var lastConfirmed = prefs.getLong(KEY_LAST_CONFIRMED_INDEX, -1L)
        var pending = prefs.getLong(KEY_PENDING_INDEX, -1L)
        val firstNotifyTime = prefs.getLong(KEY_FIRST_NOTIFY_TIME, 0L)
        val lastNotifyTime = prefs.getLong(KEY_LAST_NOTIFY_TIME, 0L)

        if (items.isEmpty()) {
            return Decision(false, null, sessionId, null)
        }

        val maxIndex = items.maxOf { it.index }

        // session reset
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

        // pending 推进
        if (pending == -1L) {
            val nextItem = items
                .filter { it.index > lastConfirmed }
                .minByOrNull { it.index }

            if (nextItem != null) {
                pending = nextItem.index.toLong()
                prefs.edit {
                    putLong(KEY_PENDING_INDEX, pending)
                    putLong(KEY_FIRST_NOTIFY_TIME, 0L)
                    putLong(KEY_LAST_NOTIFY_TIME, 0L)
                }
            }
        }

        // pending 丢失
        val pendingItem =
            items.firstOrNull { it.index.toLong() == pending }

        if (pendingItem == null) {
            prefs.edit {
                putLong(KEY_PENDING_INDEX, -1L)
                putLong(KEY_FIRST_NOTIFY_TIME, 0L)
                putLong(KEY_LAST_NOTIFY_TIME, 0L)
            }

            return Decision(false, null, sessionId, null)
        }

        // 频率限制：1 秒
        if (lastNotifyTime != 0L && now - lastNotifyTime < 1_000L) {
            return Decision(false, null, sessionId, null)
        }

        // 通道决策
        val channelId = when {
            firstNotifyTime == 0L -> {
                prefs.edit { putLong(KEY_FIRST_NOTIFY_TIME, now) }
                NotificationHelper.CHANNEL_NEW
            }

            now - firstNotifyTime >= 5 * 60_000L ->
                NotificationHelper.CHANNEL_URGENT

            else ->
                NotificationHelper.CHANNEL_REPEAT
        }

        prefs.edit { putLong(KEY_LAST_NOTIFY_TIME, now) }

        return Decision(
            shouldNotify = true,
            pendingIndex = pending,
            sessionId = sessionId,
            channelId = channelId
        )
    }
}
