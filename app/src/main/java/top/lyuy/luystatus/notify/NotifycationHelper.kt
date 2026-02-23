package top.lyuy.luystatus.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import top.lyuy.luystatus.PopReceiver
import top.lyuy.luystatus.R

object NotificationHelper {

    const val NOTIFICATION_ID = 1

    const val EXTRA_SESSION_ID = "extra_session_id"
    const val EXTRA_INDEX = "extra_index"

    /** 三个 Channel */
    const val CHANNEL_NEW = "queue_channel_new"
    const val CHANNEL_REPEAT = "queue_channel_repeat"
    const val CHANNEL_URGENT = "queue_channel_urgent"

    @SuppressLint("FullScreenIntentPolicy")
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showQueueNotification(
        context: Context,
        channelId: String,
        content: String,
        sessionId: Int,
        index: Long
    ) {
        createChannels(context)

        val popIntent = Intent(context, PopReceiver::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_INDEX, index)
        }

        val popPendingIntent = PendingIntent.getBroadcast(
            context,
            index.toInt(),
            popIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPendingIntent: PendingIntent? =
            if (channelId == CHANNEL_NEW) {
                PendingIntent.getBroadcast(
                    context,
                    index.toInt(),
                    popIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("收到一个 Queue")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.ic_done,
                "收到",
                popPendingIntent
            )

        //  仅新队列 + 已授权才使用全屏
        if (
            channelId == CHANNEL_NEW &&
            fullScreenPendingIntent != null &&
            FullScreenIntentHelper.canUse(context)
        ) {
            builder
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(fullScreenPendingIntent, true)
        }

        NotificationManagerCompat.from(context)
            .notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelQueueNotification(context: Context) {
        NotificationManagerCompat.from(context)
            .cancel(NOTIFICATION_ID)
    }

    /**
     * 一次性创建全部 Channel
     */
    private fun createChannels(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val defaultSound =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val newChannel = NotificationChannel(
            CHANNEL_NEW,
            "Queue 新队列",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Queue 第一次提醒"
            setSound(defaultSound, audioAttrs)
        }

        val repeatChannel = NotificationChannel(
            CHANNEL_REPEAT,
            "Queue 重复提醒",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Queue 未确认的重复提醒"
            setSound(null, null)
        }

        val urgentChannel = NotificationChannel(
            CHANNEL_URGENT,
            "Queue 紧急提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Queue 长时间未确认"
            setSound(defaultSound, audioAttrs)
        }

        manager.createNotificationChannel(newChannel)
        manager.createNotificationChannel(repeatChannel)
        manager.createNotificationChannel(urgentChannel)
    }
}
