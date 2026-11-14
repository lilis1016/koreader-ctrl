package com.koreader.ctrl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.koreader.ctrl.MainActivity
import com.koreader.ctrl.R

object Notifier {

    const val CHANNEL_ID = "koreader_ctrl_service"
    const val NOTIF_ID_CONST = 1001

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "KOReader 后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示手柄监听服务的运行状态"
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun build(ctx: Context, status: String): Notification {
        createChannel(ctx)

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(ctx, BackgroundInputService::class.java).apply {
            action = BackgroundInputService.ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            ctx, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("KOReader 控制器运行中")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_koreader)
            .setContentIntent(pi)
            .addAction(0, "停止服务", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notify(ctx: Context, status: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIF_ID_CONST, build(ctx, status))
        } catch (e: Exception) {
            Log.w("Notifier", "notify failed", e)
        }
    }

    fun cancel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_CONST)
    }
}
