package com.koreader.ctrl.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 蓝牙状态变化监听：用于在手柄连接/断开时刷新 UI，
 * 并在蓝牙关闭时给出提示。
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BT_CHANGED = "com.koreader.ctrl.action.BT_CHANGED"

        @Volatile
        var onChanged: (() -> Unit)? = null

        fun isBluetoothOn(): Boolean = try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.i("BtReceiver", "state changed: ${intent?.action}")
        onChanged?.invoke()
    }
}