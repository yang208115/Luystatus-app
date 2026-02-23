package top.lyuy.luystatus.notify


import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

object FullScreenIntentHelper {
    /**
     * 是否可以使用 FullScreenIntent
     */
    fun canUse(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) {
            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        } else {
            //  Android 14 以下默认允许
            true
        }
    }

    /**
     * 引导用户去系统设置授权
     * 仅在 Android 14+ 且未授权时调用
     */
    fun launchPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
