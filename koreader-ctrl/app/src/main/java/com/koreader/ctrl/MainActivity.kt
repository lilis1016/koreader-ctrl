package com.koreader.ctrl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.koreader.ctrl.data.Prefs
import com.koreader.ctrl.gamepad.GamepadEngine
import com.koreader.ctrl.model.Action
import com.koreader.ctrl.model.GamepadInfo
import com.koreader.ctrl.model.MappingPreset
import com.koreader.ctrl.net.KoreaderClient
import com.koreader.ctrl.service.ActivityInputDispatcher
import com.koreader.ctrl.service.BackgroundInputService
import com.koreader.ctrl.service.BluetoothStateReceiver
import com.koreader.ctrl.service.EventBus
import com.koreader.ctrl.service.GamepadAccessibilityService
import com.koreader.ctrl.service.InputHub
import com.koreader.ctrl.service.Notifier
import java.util.UUID

/**
 * 主界面。功能与原 HTML 版本一一对应：
 *  Kindle IP、翻页/返回/确认、日夜模式/亮度/刷新/截屏、游戏大厅、
 *  手柄检测与按键映射（多手柄多预设）、后台服务开关。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var engine: GamepadEngine

    private var pads: List<GamepadInfo> = emptyList()
    private var currentPad: GamepadInfo? = null
    private var presets: MutableMap<String, MappingPreset> = mutableMapOf()
    private var devicePresets: MutableMap<String, String> = mutableMapOf()
    private var currentPreset: MappingPreset? = null

    private var inputDispatcher: ActivityInputDispatcher? = null
    private var logListener: ((String) -> Unit)? = null

    /** 正在等待用户按下的绑定项 */
    private var capturing: Pair<Action, MappingPreset>? = null

    private lateinit var inputIp: EditText
    private lateinit var txtIpStatus: TextView
    private lateinit var txtDevice: TextView
    private lateinit var txtPreset: TextView
    private lateinit var txtMode: TextView
    private lateinit var txtPermStatus: TextView
    private lateinit var txtGamesNote: TextView
    private lateinit var mapContainer: LinearLayout
    private lateinit var statusLog: LinearLayout
    private lateinit var chkAutoStart: CheckBox
    private lateinit var btnService: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        Notifier.createChannel(this)

        engine = InputHub.engine(this)

        bindViews()
        setupActions()

        prefs.ensureDefaultPreset()
        reloadPresets()

        inputIp.setText(prefs.kindleIp)
        chkAutoStart.isChecked = prefs.autoStart

        requestNeededPermissions()
        subscribeLog()
    }

    private fun bindViews() {
        inputIp = findViewById(R.id.inputIp)
        txtIpStatus = findViewById(R.id.txtIpStatus)
        txtDevice = findViewById(R.id.txtDevice)
        txtPreset = findViewById(R.id.txtPreset)
        txtMode = findViewById(R.id.txtMode)
        txtPermStatus = findViewById(R.id.txtPermStatus)
        txtGamesNote = findViewById(R.id.txtGamesNote)
        mapContainer = findViewById(R.id.mapContainer)
        statusLog = findViewById(R.id.statusLog)
        chkAutoStart = findViewById(R.id.chkAutoStart)
        btnService = findViewById(R.id.btnService)

        txtGamesNote.text =
            "「游戏大厅」依赖 Kindle 上自行安装的 games 插件及其远程接口。" +
                    "点击会发送 ShowGames 事件；若未安装该插件，Kindle 端不会有反应。"
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnTest).setOnClickListener { testConnection() }

        bindAction(R.id.btnPrev, Action.PREV_PAGE)
        bindAction(R.id.btnNext, Action.NEXT_PAGE)
        bindAction(R.id.btnBack, Action.BACK)
        bindAction(R.id.btnConfirm, Action.CONFIRM)
        bindAction(R.id.btnNight, Action.TOGGLE_NIGHT_MODE)
        bindAction(R.id.btnFlInc, Action.INCREASE_FL)
        bindAction(R.id.btnFlDec, Action.DECREASE_FL)
        bindAction(R.id.btnRefresh, Action.FULL_REFRESH)
        bindAction(R.id.btnScreenshot, Action.SCREENSHOT)
        bindAction(R.id.btnGames, Action.GAME_HALL)

        findViewById<Button>(R.id.btnNewPreset).setOnClickListener { dialogNewPreset() }
        findViewById<Button>(R.id.btnRenamePreset).setOnClickListener { dialogRenamePreset() }
        findViewById<Button>(R.id.btnDeletePreset).setOnClickListener { dialogDeletePreset() }
        findViewById<Button>(R.id.btnAssignPreset).setOnClickListener { dialogAssignPreset() }

        btnService.setOnClickListener {
            if (BackgroundInputService.isRunning) {
                BackgroundInputService.stop(this)
                prefs.serviceRunning = false
                updateServiceUi()
                appendLog("已停止后台服务")
            } else {
                BackgroundInputService.start(this)
                prefs.serviceRunning = true
                updateServiceUi()
                appendLog("已启动后台服务")
            }
        }

        findViewById<Button>(R.id.btnPerm).setOnClickListener { openPermissionSettings() }

        chkAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
            appendLog(if (checked) "已开启开机自启" else "已关闭开机自启")
        }

        BluetoothStateReceiver.onChanged = { runOnUiThread { refreshPads() } }
    }

    private fun bindAction(id: Int, action: Action) {
        findViewById<Button>(id).setOnClickListener {
            prefs.kindleIp = inputIp.text.toString()
            KoreaderClient.sendAsync(prefs.kindleIp, action.eventName) { _, msg ->
                runOnUiThread {
                    appendLog("${action.displayName}: $msg")
                    Toast.makeText(this, "${action.displayName} $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // ---------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        reloadPresets()
        refreshPads()
        updateServiceUi()
        updatePermStatus()

        // 捕获监听：绑定按键时由 InputHub 回调
        InputHub.setCaptureListener { deviceId, code ->
            onGamepadInput(deviceId, code)
        }

        if (!BackgroundInputService.isRunning) {
            // 服务未运行时，Activity 自行处理输入事件（仅前台有效）
            inputDispatcher = ActivityInputDispatcher(engine)
        }
    }

    /**
     * 按键事件统一入口。App 在前台时，系统的输入分发最终会走到这里。
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val d = inputDispatcher
        if (d != null && d.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * 摇杆 / 通用运动事件入口。
     */
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val d = inputDispatcher
        if (d != null && d.dispatchGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        inputDispatcher = null
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothStateReceiver.onChanged = null
        InputHub.setCaptureListener(null)
        logListener?.let { EventBus.unsubscribe(it) }
        logListener = null
    }

    // ---------------------------------------------------------------
    // 手柄输入
    // ---------------------------------------------------------------
    private fun onGamepadInput(deviceId: Int, code: String) {
        runOnUiThread {
            val cap = capturing
            if (cap != null) {
                val (action, preset) = cap
                val codes = preset.codesFor(action).toMutableList()
                if (!codes.contains(code)) codes.add(code)
                preset.setCodes(action, codes)
                savePresets()
                capturing = null
                appendLog("已绑定 ${action.displayName} ← ${friendlyCode(code)}")
                renderMap()
                return@runOnUiThread
            }

            val dispatcher = BackgroundInputService.dispatcher
            if (dispatcher != null) {
                dispatcher.handleInput(deviceId, code)
            } else {
                val action = resolveLocal(code)
                if (action != null) {
                    KoreaderClient.sendAsync(prefs.kindleIp, action.eventName) { _, msg ->
                        runOnUiThread { appendLog("${action.displayName} $msg") }
                    }
                }
            }
        }
    }

    private fun resolveLocal(code: String): Action? {
        val preset = currentPreset ?: return null
        return preset.bindings.entries.firstOrNull { it.value.contains(code) }
            ?.key?.let { Action.byId(it) }
    }

    // ---------------------------------------------------------------
    // 设备 / 预设
    // ---------------------------------------------------------------

    private fun reloadPresets() {
        val (p, dp) = prefs.loadPresets()
        presets = p.toMutableMap()
        devicePresets = dp.toMutableMap()

        if (currentPad == null || pads.none { it.persistKey == currentPad?.persistKey }) {
            currentPad = pads.firstOrNull()
        }

        val presetId = currentPad?.let { devicePresets[it.persistKey] }
        currentPreset = presetId?.let { presets[it] } ?: presets.values.firstOrNull()

        renderDeviceInfo()
        renderMap()
    }

    private fun refreshPads() {
        try {
            pads = engine.listGamepads()
        } catch (e: Exception) {
            Log.w("MainActivity", "list pads failed", e)
            pads = emptyList()
        }
        InputHub.updatePads(pads)
        if (currentPad == null || pads.none { it.persistKey == currentPad?.persistKey }) {
            currentPad = pads.firstOrNull()
        }
        currentPad?.let { pad ->
            val presetId = devicePresets[pad.persistKey]
            currentPreset = presetId?.let { presets[it] } ?: currentPreset
        }
        renderDeviceInfo()
        renderMap()
    }

    private fun renderDeviceInfo() {
        if (pads.isEmpty()) {
            txtDevice.text = "当前手柄：未检测到手柄（请通过蓝牙连接）"
        } else {
            val sb = StringBuilder("当前手柄：")
            pads.forEachIndexed { i, p ->
                if (i > 0) sb.append("   ")
                sb.append("[${p.shortModel}]")
            }
            txtDevice.text = sb.toString()
        }

        txtPreset.text = "当前预设：" + (currentPreset?.name ?: "（未选择）")

        val a11y = GamepadAccessibilityService.isEnabled
        txtMode.text = buildString {
            append("监听模式：")
            append(if (a11y) "辅助功能（全局）" else "前台窗口")
            append("  ·  服务：")
            append(if (BackgroundInputService.isRunning) "运行中" else "已停止")
        }

        updateServiceUi()
    }

    private fun updateServiceUi() {
        btnService.text = if (BackgroundInputService.isRunning)
            getString(R.string.btn_stop_service) else getString(R.string.btn_start_service)
    }

    private fun savePresets() {
        prefs.savePresets(presets, devicePresets)
    }
    // ---------------------------------------------------------------
    // 映射编辑 UI
    // ---------------------------------------------------------------

    private fun renderMap() {
        mapContainer.removeAllViews()
        val preset = currentPreset
        if (preset == null) {
            val tv = TextView(this).apply {
                text = "尚无预设，点击「新建预设」创建。"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_sub))
                setPadding(0, 8, 0, 0)
            }
            mapContainer.addView(tv)
            return
        }
        Action.values().forEach { action ->
            mapContainer.addView(buildActionRow(action, preset))
        }
    }

    private fun buildActionRow(action: Action, preset: MappingPreset): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }

        val label = TextView(this).apply {
            text = action.displayName
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(label)

        val codes = preset.codesFor(action)
        val chipText = if (codes.isEmpty()) getString(R.string.none)
        else codes.joinToString(", ") { friendlyCode(it) }

        val chip = Button(this).apply {
            text = chipText
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.panel))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (codes.isEmpty()) R.drawable.bg_chip else R.drawable.bg_chip_active
            )
            textSize = 11f
            minHeight = 34
            minWidth = 96
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                capturing = action to preset
                appendLog("请按下要绑定到「${action.displayName}」的按键…")
                Toast.makeText(this, getString(R.string.hint_capture), Toast.LENGTH_LONG).show()
            }
        }
        row.addView(chip)

        val clear = Button(this).apply {
            text = "×"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warn))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
            textSize = 14f
            minWidth = 40
            minHeight = 34
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6 }
            setOnClickListener {
                preset.setCodes(action, emptyList())
                savePresets()
                renderMap()
            }
        }
        row.addView(clear)

        return row
    }

    /** 把内部输入码转成人可读的名字 */
    private fun friendlyCode(code: String): String {
        return when {
            code.startsWith("Gamepad_") -> {
                when (code.removePrefix("Gamepad_")) {
                    "0" -> "A / 底部键"
                    "1" -> "B / 底部键"
                    "2" -> "X"
                    "3" -> "Y"
                    "4" -> "LB / L1"
                    "5" -> "RB / R1"
                    "6" -> "LT / L2"
                    "7" -> "RT / R2"
                    "8" -> "Select"
                    "9" -> "Start"
                    "10" -> "左摇杆按下"
                    "11" -> "右摇杆按下"
                    "12" -> "十字键 ↑"
                    "13" -> "十字键 ↓"
                    "14" -> "十字键 ←"
                    "15" -> "十字键 →"
                    else -> "按键 ${code.removePrefix("Gamepad_")}"
                }
            }
            code.startsWith("Axis_") -> {
                val parts = code.removePrefix("Axis_").split("_")
                if (parts.size == 2) {
                    val dir = when (parts[1]) {
                        "-1" -> "←"
                        "1" -> "→"
                        else -> "?"
                    }
                    "摇杆${parts[0]} $dir"
                } else code
            }
            else -> code
        }
    }
    // ---------------------------------------------------------------
    // 预设对话框
    // ---------------------------------------------------------------

    private fun dialogNewPreset() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_new_preset)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_new_preset)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val p = MappingPreset(UUID.randomUUID().toString(), name)
                presets[p.id] = p
                currentPreset = p
                currentPad?.let { devicePresets[it.persistKey] = p.id }
                savePresets()
                renderMap()
                renderDeviceInfo()
                appendLog("已创建预设「$name」")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dialogRenamePreset() {
        val preset = currentPreset ?: run {
            Toast.makeText(this, "请先创建预设", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(preset.name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_preset)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    preset.name = name
                    savePresets()
                    renderDeviceInfo()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dialogDeletePreset() {
        val preset = currentPreset ?: return
        AlertDialog.Builder(this)
            .setTitle("删除预设")
            .setMessage("确定删除「${preset.name}」？")
            .setPositiveButton(R.string.delete) { _, _ ->
                presets.remove(preset.id)
                devicePresets.entries.removeAll { it.value == preset.id }
                savePresets()
                currentPreset = presets.values.firstOrNull()
                renderMap()
                renderDeviceInfo()
                appendLog("已删除预设「${preset.name}」")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dialogAssignPreset() {
        if (pads.isEmpty()) {
            Toast.makeText(this, "未检测到手柄", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = currentPad ?: pads.first()
        val names = presets.values.map { it.name }.toTypedArray()
        if (names.isEmpty()) {
            Toast.makeText(this, "请先创建预设", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = presets.values.indexOfFirst { it.id == devicePresets[pad.persistKey] }
        AlertDialog.Builder(this)
            .setTitle("为「${pad.shortModel}」分配预设")
            .setSingleChoiceItems(names, checked) { dialog: android.content.DialogInterface, which: Int ->
                val preset = presets.values.toList()[which]
                devicePresets[pad.persistKey] = preset.id
                savePresets()
                currentPreset = preset
                renderMap()
                renderDeviceInfo()
                appendLog("已为 ${pad.shortModel} 分配预设「${preset.name}」")
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------
    // 连接测试 / 权限 / 日志
    // ---------------------------------------------------------------

    private fun testConnection() {
        val ip = inputIp.text.toString().trim()
        prefs.kindleIp = ip
        txtIpStatus.text = "测试中…"
        KoreaderClient.sendAsync(ip, Action.FULL_REFRESH.eventName) { ok, msg ->
            runOnUiThread {
                txtIpStatus.text = if (ok) "连通 · $msg" else "失败 · $msg"
                txtIpStatus.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (ok) R.color.ok else R.color.warn
                    )
                )
                appendLog("连接测试 $ip -> $msg")
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    private fun openPermissionSettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("无法打开无障碍设置：${e.message}")
        }
    }

    private fun updatePermStatus() {
        val a11y = GamepadAccessibilityService.isEnabled
        val battery = isIgnoringBatteryOptimizations()
        txtPermStatus.text = buildString {
            append("无障碍服务：")
            append(if (a11y) "已开启（可后台收键）" else "未开启（仅前台收键）")
            append("\n忽略电池优化：")
            append(if (battery) "已设置" else "未设置（建议开启，否则后台可能被杀）")
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                true
            } else {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun subscribeLog() {
        logListener = { text ->
            runOnUiThread { appendLog(text) }
        }
        EventBus.subscribe(logListener!!)
    }

    private fun appendLog(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_sub))
            textSize = 11f
            setPadding(0, 2, 0, 2)
        }
        statusLog.addView(tv)
        // 只保留最近 30 条
        while (statusLog.childCount > 30) {
            statusLog.removeViewAt(0)
        }
        statusLog.post { scrollLogToBottom() }
    }

    /** 日志区域滚动到底部。statusLog 是 LinearLayout，需借助父 ScrollView 实现。 */
    private fun scrollLogToBottom() {
        try {
            val parent = statusLog.parent
            if (parent is android.widget.ScrollView) {
                parent.post { parent.fullScroll(View.FOCUS_DOWN) }
            }
        } catch (e: Exception) {
            // 滚动失败不影响功能
        }
    }
}
