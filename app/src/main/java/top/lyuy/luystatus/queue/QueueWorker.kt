package top.lyuy.luystatus.queue

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import top.lyuy.luystatus.api.ApiProvider
import top.lyuy.luystatus.notify.NotificationHelper
import java.util.concurrent.TimeUnit

class QueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "QueueWorker"
        private const val PREF_NAME = "queue_worker"

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "QueueWorkerImmediate",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }

    /**
     * WorkManager 执行入口。
     *
     * 职责概述：
     * 1. 轮询后端 Queue API 获取最新列表数据
     * 2. 将当前列表 + 本地持久化状态交给 QueueStateMachine 做决策
     * 3. 若状态机返回需要通知，则构建通知内容并触发系统通知
     * 4. 无论成功或失败，都调度下一次轮询
     *
     * 设计原则：
     * - Worker 只负责“流程编排”（orchestration）
     * - 不包含业务决策逻辑（由 QueueStateMachine 负责）
     * - 不包含通知内容构建逻辑（由 QueueNotificationBuilder 负责）
     * - 不直接处理状态细节（通过 SharedPreferences 交由状态机管理）
     *
     * 执行流程：
     * - 调用 API 获取队列数据
     * - 获取当前时间戳
     * - 调用 QueueStateMachine.evaluate() 生成决策结果
     * - 若 shouldNotify == true：
     *     - 根据 pendingIndex 查找对应 item
     *     - 使用 QueueNotificationBuilder 构造展示内容
     *     - 调用 NotificationHelper 发送通知
     * - 调用 enqueueNext() 安排下一次轮询
     *
     * 异常策略：
     * - 捕获所有异常并记录日志
     * - 返回 Result.retry() 交由 WorkManager 重试
     * - 即使异常，也会继续调度下一次轮询，保证轮询不中断
     *
     * 生命周期说明：
     * - 本 Worker 采用 OneTimeWork + 自行 enqueue 的方式模拟定时轮询
     * - 使用 ExistingWorkPolicy.KEEP 防止重复调度
     *
     * 注意：
     * - Worker 可能在任意时间、任意线程被系统唤醒执行
     * - 不应在此处保存 UI 状态或持有 Activity 引用
     */

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {

        val prefs =
            applicationContext.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
            )

        try {
            val items = ApiProvider.api.list().items
            Log.d("QueueWorker","item=$items")
            val now = System.currentTimeMillis()

            val decision =
                QueueStateMachine.evaluate(
                    prefs = prefs,
                    items = items,
                    now = now
                )

            if (decision.shouldNotify &&
                decision.pendingIndex != null &&
                decision.channelId != null
            ) {
                val item = items.firstOrNull {
                    it.index.toLong() == decision.pendingIndex
                }

                if (item != null) {
                    val content =
                        QueueNotificationBuilder.buildNotification(item.data)

                    NotificationHelper.showQueueNotification(
                        context = applicationContext,
                        channelId = decision.channelId,
                        content = content,
                        index = decision.pendingIndex,
                        sessionId = decision.sessionId
                    )
                }
            }

            enqueueNext()
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "queue poll failed", e)
            enqueueNext()
            return Result.retry()
        }
    }

    fun enqueueNext() {
        val request =
            OneTimeWorkRequestBuilder<QueueWorker>()
                .setInitialDelay(25, TimeUnit.SECONDS)
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "QueueWorker",
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
