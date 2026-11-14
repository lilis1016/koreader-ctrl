package com.koreader.ctrl.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器。
 *
 * 注意：Android 8+ 禁止普通广播注册开机启动，需要用户在系统设置中允许
 * 「自启动 / 后台运行」，本 Receiver 会在被系统唤醒时尝试拉起前台服务。
 * 即使自启失败，用户手动打开一次 App 并启动服务后，
 * 服务会以 START_STICKY 方式在系统回收后被重启。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        fun startIfEnabled(context: Context) {
            val prefs = com.koreader.ctrl.data.Prefs(context)
            if (!prefs.autoStart) return
            Log.i(TAG, "auto start on boot")
            BackgroundInputService.start(context)
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                context?.let { startIfEnabled(it) }
            }
        }
    }
}