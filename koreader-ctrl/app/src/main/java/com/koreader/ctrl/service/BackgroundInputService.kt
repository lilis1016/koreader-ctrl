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

/**
 * 后台手柄监听服务（Foreground Service）。
 *
 * ============================ 重要说明（务必阅读）===========================
 * Android 对「后台全局读取手柄输入」有明确的系统限制，本项目按
 * 真实的系统行为实现，不做任何夸大承诺：
 *
 * 1) Foreground Service 只能保证「进程存活、通知常驻、可以持续执行轮询任务」，
 *    但它本身 **不会** 让你的应用在其它 App 处于前台时抢到按键事件。
 *    Android 的输入分发是「焦点优先」：前台 App 先拿事件。
 *
 * 2) 在熄屏 / 锁屏状态下，系统通常不再把 MotionEvent 派发给普通应用，
 *    此时轴（摇杆）输入基本不可用；按键（KeyEvent）在部分机型上仍会派发。
 *
 * 3) 真正能在「其它 App 前台 / 熄屏」下稳定拿到按键的方式是
 *    AccessibilityService 的 onKeyEvent —— 这是目前唯一被广泛使用的可行路径，
 *    但需要用户在系统「无障碍」设置里手动开启（敏感权限，且部分 ROM 对游戏手柄键位
 *    仍不派发）。
 *
 * 4) 因此本服务同时实现：
 *      - 主路径：AccessibilityService（若用户已开启），见 GamepadAccessibilityService
 *      - 备用路径：本 Foreground Service 内的轮询 + Activity 事件接收
 *    两条路径共用同一个 ActionDispatcher，保证行为一致。
 *
 * 服务会如实汇报当前处于哪种监听模式，UI 中会明确显示。
 * ========================================================================
 */
class BackgroundInputService : Service() {

    companion object {
        const val ACTION_START = "com.koreader.ctrl.action.START"
        const val ACTION_STOP = "com.koreader.ctrl.action.STOP"
        const val ACTION_REFRESH = "com.koreader.ctrl.action.REFRESH"

        private const val TAG = "BgInputService"
        private const val POLL_INTERVAL_MS = 40L

        @Volatile
        var isRunning = false
            private set

        /** 全局分发器引用，供 AccessibilityService 复用 */
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

    /** 当前模式：FOREGROUND / ACCESSIBILITY */
    private var mode: String = BackgroundModes.MODE_FOREGROUND

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "screen on")
                    updateStatus()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "screen off")
                    updateStatus()
                }
                BluetoothStateReceiver.ACTION_BT_CHANGED -> {
                    Log.i(TAG, "bluetooth state changed")
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

        // 与 Activity 共用同一个 Engine 实例，避免摇杆方向被两个来源重复触发
        engine = InputHub.engine(applicationContext)

        // 服务在运行时，由服务负责派发动作
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
        val r = Runnable {
            try {
                engine?.poll()
                // 顺带刷新手柄列表，供动作解析使用
                InputHub.updatePads(currentPads())
            } catch (e: Exception) {
                Log.w(TAG, "poll error", e)
            }
        h.postDelayed(r, POLL_INTERVAL_MS)
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
            ACTION_START, ACTION_REFRESH, null -> {
                // no-op
            }
        }

        val status = buildStatus()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifier.CHANNEL_ID,
                    Notifier.build(this, status),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(Notifier.CHANNEL_ID, Notifier.build(this, status))
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
        val padTxt = if (pads.isEmpty()) "未检测到手柄" else "${pads.size} 个手柄"
        val acc = if (GamepadAccessibilityService.isEnabled) "｜辅助功能监听" else ""
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
        Notifier.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------
    // 供 Activity / AccessibilityService 调用的入口
    // ---------------------------------------------------------------

    /** 当前可见的手柄列表 */
    fun currentPads(): List<GamepadInfo> =
        try { engine?.listGamepads() ?: emptyList() } catch (e: Exception) { emptyList() }

    fun setMode(m: String) {
        mode = m
    }

    fun currentMode(): String = mode
}

/**
 * 监听模式常量。
 */
object BackgroundModes {
    const val MODE_FOREGROUND = "FOREGROUND"
    const val MODE_ACCESSIBILITY = "ACCESSIBILITY"
}

/**
 * 把「输入码」翻译成「动作」，再发给 Kindle。
 *
 * 映射解析顺序：
 *  1. 精确匹配该手柄绑定的预设
 *  2. 若该手柄未绑定预设，尝试用设备型号匹配一个预设（同一预设服务所有同型号手柄）
 *  3. 都没有则忽略
 */
class ActionDispatcher(private val context: Context) {

    private val prefs = Prefs(context)

    fun handleInput(deviceId: Int, code: String) {
        // 手柄列表来自 InputHub（无论服务还是 Activity 都会更新），
        // 这样在服务未运行时也能正确解析所属手柄。
        val pads = InputHub.currentPads()
        val pad = pads.firstOrNull { it.deviceId == deviceId }

        val action = resolveAction(pad, code) ?: return

        val ip = prefs.kindleIp
        KoreaderClient.sendAsync(ip, action.eventName) { ok, msg ->
            Log.i("ActionDispatcher", "${action.displayName} -> $msg")
            EventBus.post(EventBus.LogEvent("${action.displayName} (${if (ok) "OK" else "FAIL"}) $msg"))
        }
    }

    /**
     * 解析输入码对应的动作；返回 null 表示未绑定。
     *
     * 优先级：
     *  1. 该手柄 persistKey 精确绑定的预设
     *  2. 该手柄型号名匹配的预设（同一预设服务所有同型号手柄）
     *  3. 唯一的那个预设（单预设场景下的兜底）
     */
    fun resolveAction(pad: GamepadInfo?, code: String): Action? {
        if (pad == null) return null

        val (presets, devicePresets) = prefs.loadPresets()
        if (presets.isEmpty()) return null

        // 1. 精确按 persistKey 匹配
        var presetId = devicePresets[pad.persistKey]
        if (presetId == null) {
            // 设备可能重连后 deviceId 变化，用型号兜底
            presetId = devicePresets["MODEL:${pad.name}"]
        }

        var preset = presetId?.let { presets[it] }

        if (preset == null) {
            // 2. 型号名匹配：找名字里含型号的预设
            preset = presets.values.firstOrNull { it.name.contains(pad.name, ignoreCase = true) }
        }

        if (preset == null) {
            // 3. 只有一个预设时直接使用
            preset = presets.values.singleOrNull()
        }

        if (preset == null) return null

        return preset.bindings.entries.firstOrNull { (_, codes) -> codes.contains(code) }
            ?.key?.let { Action.byId(it) }
    }
}

/**
 * 简单的进程内事件总线，用于把后台日志回传到 UI。
 */
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