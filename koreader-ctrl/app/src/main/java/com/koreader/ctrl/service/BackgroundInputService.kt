package com.koreader.ctrl.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.koreader.ctrl.data.Prefs
import com.koreader.ctrl.gamepad.GamepadEngine
import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.GamepadInfo
import com.koreader.ctrl.net.KoreaderClient

class BackgroundInputService : Service() {

    companion object {
        const val ACTION_START = "com.koreader.ctrl.action.START"
        const val ACTION_STOP = "com.koreader.ctrl.action.STOP"
        const val ACTION_REFRESH = "com.koreader.ctrl.action.REFRESH"

        private const val TAG = "BgInputService"
        private const val REFRESH_INTERVAL_MS = 1000L

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var dispatcher: ActionDispatcher? = null

        fun start(ctx: Context) {
            val intent = Intent(ctx, BackgroundInputService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, BackgroundInputService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                ctx.startService(intent)
            } catch (e: Exception) {
                ctx.stopService(Intent(ctx, BackgroundInputService::class.java))
            }
        }
    }

    private lateinit var prefs: Prefs
    private var engine: GamepadEngine? = null
    private var handler: Handler? = null
    private var pollRunnable: Runnable? = null
    private var receiver: BroadcastReceiver? = null

    private var mode: String = BackgroundModes.MODE_FOREGROUND

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                BluetoothStateReceiver.ACTION_BT_CHANGED -> {
                    updateStatus()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        isRunning = true
        prefs.serviceRunning = true

        val d = ActionDispatcher(applicationContext)
        dispatcher = d

        engine = InputHub.engine(applicationContext)

        InputHub.setActionListener { deviceId, code ->
            d.handleInput(deviceId, code)
        }

        handler = Handler(Looper.getMainLooper())
        startPolling()
        registerScreenReceiver()

        Log.i(TAG, "service created")
    }

    private fun registerScreenReceiver() {
        receiver = screenReceiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothStateReceiver.ACTION_BT_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun startPolling() {
        val h = handler ?: return
        val r = object : Runnable {
            override fun run() {
                try {
                    InputHub.updatePads(currentPads())
                } catch (e: Exception) {
                    Log.w(TAG, "poll error", e)
                }
                h.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
        pollRunnable = r
        h.post(r)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_REFRESH, null -> {}
        }

        val status = buildStatus()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifier.NOTIF_ID_CONST,
                    Notifier.build(this, status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(Notifier.NOTIF_ID_CONST, Notifier.build(this, status))
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        updateStatus()
        return START_STICKY
    }

    private fun buildStatus(): String {
        val ip = prefs.kindleIp
        val pads = try { engine?.listGamepads() ?: emptyList() } catch (e: Exception) { emptyList() }
        val padTxt = if (pads.isEmpty()) "未连接手柄" else "已连接 ${pads.size} 个手柄"
        val acc = if (GamepadAccessibilityService.isEnabled) "｜全局监听" else ""
        return "$padTxt｜Kindle $ip$acc"
    }

    private fun updateStatus() {
        Notifier.notify(this, buildStatus())
    }

    override fun onDestroy() {
        isRunning = false
        prefs.serviceRunning = false
        pollRunnable?.let { handler?.removeCallbacks(it) }
        try {
            receiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        dispatcher = null
        InputHub.setActionListener(null)
        Notifier.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun currentPads(): List<GamepadInfo> =
        try { engine?.listGamepads() ?: emptyList() } catch (e: Exception) { emptyList() }

    fun setMode(m: String) {
        mode = m
    }

    fun currentMode(): String = mode
}

object BackgroundModes {
    const val MODE_FOREGROUND = "FOREGROUND"
    const val MODE_ACCESSIBILITY = "ACCESSIBILITY"
}

class ActionDispatcher(private val context: Context) {

    private val prefs = Prefs(context)

    fun handleInput(deviceId: Int, code: String) {
        val pads = InputHub.currentPads()
        val pad = pads.firstOrNull { it.deviceId == deviceId }

        val action = resolveAction(pad, code) ?: return

        val ip = prefs.kindleIp
        KoreaderClient.sendAsync(ip, action.eventName) { ok, msg ->
            Log.i("ActionDispatcher", "${action.displayName} -> $msg")
            val keyDisplay = GamepadEngine.formatCodeForDisplay(code)
            EventBus.post(EventBus.LogEvent("${action.displayName} [$keyDisplay] (${if (ok) "成功" else "失败"}) $msg"))
        }
    }

    fun resolveAction(pad: GamepadInfo?, code: String): Action? {
        val (presets, devicePresets) = prefs.loadPresets()
        if (presets.isEmpty()) return null

        var preset = if (pad != null) {
            val presetId = devicePresets[pad.persistKey] ?: devicePresets["MODEL:${pad.name}"]
            presetId?.let { presets[it] }
                ?: presets.values.firstOrNull { it.name.contains(pad.name, ignoreCase = true) }
        } else null

        if (preset == null) {
            preset = presets.values.firstOrNull()
        }

        return preset?.findActionForCode(code)
    }
}

object EventBus {
    private val listeners = mutableListOf<(String) -> Unit>()

    data class LogEvent(val text: String)

    fun post(event: LogEvent) {
        listeners.toList().forEach { it.invoke(event.text) }
    }

    fun subscribe(fn: (String) -> Unit) {
        listeners += fn
    }

    fun unsubscribe(fn: (String) -> Unit) {
        listeners -= fn
    }
}
