package com.koreader.ctrl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var engine: GamepadEngine
    private lateinit var inputManager: InputManager

    private var pads: List<GamepadInfo> = emptyList()
    private var currentPad: GamepadInfo? = null
    private var presets: MutableMap<String, MappingPreset> = mutableMapOf()
    private var devicePresets: MutableMap<String, String> = mutableMapOf()
    private var currentPreset: MappingPreset? = null

    private var inputDispatcher: ActivityInputDispatcher? = null
    private var logListener: ((String) -> Unit)? = null

    /** 正在等待用户按下手柄按键绑定的目标功能 */
    private var capturing: Pair<Action, MappingPreset>? = null

    private lateinit var txtBtStatus: TextView
    private lateinit var inputIp: EditText
    private lateinit var txtIpStatus: TextView
    private lateinit var txtDevice: TextView
    private lateinit var txtPreset: TextView
    private lateinit var txtMode: TextView
    private lateinit var txtPermStatus: TextView
    private lateinit var txtGamesNote: TextView
    private lateinit var captureBanner: LinearLayout
    private lateinit var txtCapturePrompt: TextView
    private lateinit var mapContainer: LinearLayout
    private lateinit var statusLog: LinearLayout
    private lateinit var chkAutoStart: CheckBox
    private lateinit var btnService: Button

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshPads()
        override fun onInputDeviceRemoved(deviceId: Int) {
            engine.clearState(deviceId)
            refreshPads()
        }
        override fun onInputDeviceChanged(deviceId: Int) = refreshPads()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        Notifier.createChannel(this)
        engine = InputHub.engine(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

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
        txtBtStatus = findViewById(R.id.txtBtStatus)
        inputIp = findViewById(R.id.inputIp)
        txtIpStatus = findViewById(R.id.txtIpStatus)
        txtDevice = findViewById(R.id.txtDevice)
        txtPreset = findViewById(R.id.txtPreset)
        txtMode = findViewById(R.id.txtMode)
        txtPermStatus = findViewById(R.id.txtPermStatus)
        txtGamesNote = findViewById(R.id.txtGamesNote)
        captureBanner = findViewById(R.id.captureBanner)
        txtCapturePrompt = findViewById(R.id.txtCapturePrompt)
        mapContainer = findViewById(R.id.mapContainer)
        statusLog = findViewById(R.id.statusLog)
        chkAutoStart = findViewById(R.id.chkAutoStart)
        btnService = findViewById(R.id.btnService)

        txtGamesNote.text = "提示：「游戏大厅」发送 ShowGames 指令，需 Kindle 已安装对应插件。"
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

        findViewById<Button>(R.id.btnOpenMapping).setOnClickListener { dialogMapping() }
        findViewById<Button>(R.id.btnOpenQuickActions).setOnClickListener { dialogQuickActions() }
        findViewById<Button>(R.id.btnResetDefault).setOnClickListener { confirmResetDefault() }
        findViewById<Button>(R.id.btnCancelCapture).setOnClickListener {
            capturing = null
            updateCaptureBanner()
            renderMap()
        }

        findViewById<Button>(R.id.btnNewPreset).setOnClickListener { dialogNewPreset() }
        findViewById<Button>(R.id.btnRenamePreset).setOnClickListener { dialogRenamePreset() }
        findViewById<Button>(R.id.btnDeletePreset).setOnClickListener { dialogDeletePreset() }
        findViewById<Button>(R.id.btnAssignPreset).setOnClickListener { dialogAssignPreset() }

        btnService.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1002)
                Toast.makeText(this, "请先授予蓝牙连接权限后再启动后台服务", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (BackgroundInputService.isRunning) {
                BackgroundInputService.stop(this)
                prefs.serviceRunning = false
                InputHub.setActionListener(null)
                inputDispatcher = ActivityInputDispatcher(engine)
                renderDeviceInfo()
                appendLog("已停止后台服务")
            } else {
                BackgroundInputService.start(this)
                prefs.serviceRunning = true
                renderDeviceInfo()
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


    private fun showPanelDialog(panelId: Int, title: String) {
        val panel = findViewById<LinearLayout>(panelId)
        val parent = panel.parent as? ViewGroup ?: return
        val originalIndex = parent.indexOfChild(panel)
        parent.removeView(panel)
        panel.visibility = View.VISIBLE
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            (panel.parent as? ViewGroup)?.removeView(panel)
            parent.addView(panel, originalIndex)
            panel.visibility = View.GONE
            renderMap()
        }
        dialog.show()
    }

    private fun dialogMapping() = showPanelDialog(R.id.mappingPanel, "按键映射配置")

    private fun dialogQuickActions() = showPanelDialog(R.id.quickActionsPanel, "快捷功能测试")

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

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(deviceListener, null)
        reloadPresets()
        refreshPads()
        updatePermStatus()

        inputDispatcher = ActivityInputDispatcher(engine)

        InputHub.setCaptureListener { deviceId, code ->
            onGamepadInput(deviceId, code)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val d = inputDispatcher
        if (d != null && d.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val d = inputDispatcher
        if (d != null && d.dispatchGenericMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(deviceListener)
        inputDispatcher = null
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothStateReceiver.onChanged = null
        InputHub.setCaptureListener(null)
        logListener?.let { EventBus.unsubscribe(it) }
        logListener = null
    }

    private fun onGamepadInput(deviceId: Int, code: String): Boolean {
        val cap = capturing
        if (cap != null) {
            runOnUiThread {
                val (action, preset) = cap
                val displaced = preset.bindUnique(action, code)
                savePresets()
                capturing = null
                updateCaptureBanner()
                renderMap()

                val keyName = friendlyCode(code)
                val displacedMsg = if (displaced.isNotEmpty()) {
                    "（已自动解除「${displaced.joinToString("、") { it.displayName }}」的旧绑定）"
                } else ""
                val msg = "已将「${action.displayName}」绑定为：$keyName$displacedMsg"
                appendLog(msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        if (!BackgroundInputService.isRunning) {
            runOnUiThread {
                val action = currentPreset?.findActionForCode(code)
                if (action != null) {
                    prefs.kindleIp = inputIp.text.toString().trim()
                    KoreaderClient.sendAsync(prefs.kindleIp, action.eventName) { _, msg ->
                        runOnUiThread { appendLog("${action.displayName} (${friendlyCode(code)}) -> $msg") }
                    }
                } else {
                    appendLog("收到未绑定按键：${friendlyCode(code)}")
                }
            }
            return true
        }
        return false
    }

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
        val btOn = BluetoothStateReceiver.isBluetoothOn()
        txtBtStatus.text = if (btOn) "蓝牙连接状态：已开启" else "蓝牙连接状态：未开启（请先打开手机蓝牙）"
        txtBtStatus.setTextColor(
            ContextCompat.getColor(this, if (btOn) R.color.ok else R.color.warn)
        )

        if (pads.isEmpty()) {
            txtDevice.text = "游戏手柄连接状态：未连接（请连接蓝牙游戏手柄）"
            txtDevice.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        } else {
            val names = pads.joinToString("、") { it.shortModel }
            txtDevice.text = "游戏手柄连接状态：已连接 $names"
            txtDevice.setTextColor(ContextCompat.getColor(this, R.color.ok))
        }

        txtPreset.text = "当前映射预设：" + (currentPreset?.name ?: "默认预设")

        val a11y = GamepadAccessibilityService.isEnabled
        txtMode.text = buildString {
            append("监听模式：")
            append(if (a11y) "无障碍全局监听" else "前台窗口监听")
            append("  ｜  后台服务：")
            append(if (BackgroundInputService.isRunning) "运行中" else "未启动")
        }

        updateServiceUi()
    }

    private fun updateServiceUi() {
        btnService.text = if (BackgroundInputService.isRunning)
            getString(R.string.btn_stop_service) else getString(R.string.btn_start_service)
    }

    private fun updateCaptureBanner() {
        val cap = capturing
        if (cap == null) {
            captureBanner.visibility = View.GONE
        } else {
            captureBanner.visibility = View.VISIBLE
            txtCapturePrompt.text = "正在设置「${cap.first.displayName}」：请按下游戏手柄上的按键"
        }
    }

    private fun savePresets() {
        prefs.kindleIp = inputIp.text.toString().trim()
        prefs.savePresets(presets, devicePresets)
    }

    private fun renderMap() {
        mapContainer.removeAllViews()
        val preset = currentPreset ?: return

        Action.values().forEachIndexed { idx, action ->
            if (idx > 0) {
                val div = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                }
                mapContainer.addView(div)
            }
            mapContainer.addView(buildActionRow(action, preset))
        }
    }

    private fun buildActionRow(action: Action, preset: MappingPreset): View {
        val isWaitingThis = capturing?.first == action

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 18, 4, 18)
            isClickable = true
            isFocusable = true
            setOnClickListener { startCapture(action, preset) }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleTv = TextView(this).apply {
            text = action.displayName
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_main))
            textSize = 15f
            paint.isFakeBoldText = true
        }
        val descTv = TextView(this).apply {
            text = action.description
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_sub))
            textSize = 12f
        }
        textCol.addView(titleTv)
        textCol.addView(descTv)
        row.addView(textCol)

        val boundCode = preset.codeFor(action)
        val chipText = when {
            isWaitingThis -> "请按键…"
            boundCode == null -> getString(R.string.none)
            else -> friendlyCode(boundCode)
        }

        val chip = Button(this).apply {
            text = chipText
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.panel))
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (boundCode == null && !isWaitingThis) R.drawable.bg_chip else R.drawable.bg_chip_active
            )
            textSize = 12f
            minHeight = 40
            minWidth = 108
            maxWidth = (resources.displayMetrics.density * 164).toInt()
            isSingleLine = false
            maxLines = 2
            setHorizontallyScrolling(false)
            setPadding(10, 4, 10, 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { startCapture(action, preset) }
        }
        row.addView(chip)

        if (boundCode != null) {
            val clear = Button(this).apply {
                text = "×"
                isAllCaps = false
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warn))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_input)
                textSize = 14f
                minWidth = 38
                minHeight = 36
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8 }
                setOnClickListener {
                    preset.clearBinding(action)
                    savePresets()
                    renderMap()
                    appendLog("已解除「${action.displayName}」的按键绑定")
                }
            }
            row.addView(clear)
        }

        return row
    }

    private fun startCapture(action: Action, preset: MappingPreset) {
        capturing = action to preset
        updateCaptureBanner()
        renderMap()
        appendLog("等待输入：请按下游戏手柄上的按键以绑定「${action.displayName}」")
        Toast.makeText(this, "请按下游戏手柄上的按键", Toast.LENGTH_SHORT).show()
    }

    private fun confirmResetDefault() {
        val preset = currentPreset ?: return
        AlertDialog.Builder(this)
            .setTitle("恢复默认映射")
            .setMessage("确定将当前预设恢复为默认按键映射吗？（十字键左右翻页、上下调节亮度、手柄按钮 1/2 确认/返回）")
            .setPositiveButton(R.string.ok) { _, _ ->
                capturing = null
                updateCaptureBanner()
                prefs.resetPresetToDefault(preset)
                reloadPresets()
                appendLog("已恢复默认按键映射")
                Toast.makeText(this, "已恢复默认映射", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun friendlyCode(code: String): String = GamepadEngine.formatCodeForDisplay(code)

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
                prefs.applyDefaultBindings(p)
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
        val preset = currentPreset ?: return
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
        if (presets.size <= 1) {
            Toast.makeText(this, "至少保留一个预设", Toast.LENGTH_SHORT).show()
            return
        }
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
            Toast.makeText(this, "未检测到已连接的手柄", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = currentPad ?: pads.first()
        val names = presets.values.map { it.name }.toTypedArray()
        if (names.isEmpty()) return
        val checked = presets.values.indexOfFirst { it.id == devicePresets[pad.persistKey] }
        AlertDialog.Builder(this)
            .setTitle("为「${pad.shortModel}」分配预设")
            .setSingleChoiceItems(names, checked) { dialog, which ->
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

    private fun testConnection() {
        val ip = inputIp.text.toString().trim()
        prefs.kindleIp = ip
        txtIpStatus.text = "正在连接 Kindle ($ip:8080)…"
        KoreaderClient.sendAsync(ip, Action.FULL_REFRESH.eventName) { ok, msg ->
            runOnUiThread {
                txtIpStatus.text = if (ok) "Kindle 已连通 · $msg" else "连接失败 · $msg"
                txtIpStatus.setTextColor(
                    ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn)
                )
                appendLog("连接测试 $ip -> $msg")
            }
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    private fun openPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            appendLog("无法打开无障碍设置：${e.message}")
        }
    }

    private fun updatePermStatus() {
        val a11y = GamepadAccessibilityService.isEnabled
        val battery = isIgnoringBatteryOptimizations()
        txtPermStatus.text = buildString {
            append("无障碍服务：")
            append(if (a11y) "已开启（支持后台与锁屏收键）" else "未开启（仅应用在前台时收键）")
            append("\n电池优化白名单：")
            append(if (battery) "已忽略电池优化" else "未设置（建议允许后台运行）")
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
        logListener = { text -> runOnUiThread { appendLog(text) } }
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
        while (statusLog.childCount > 30) {
            statusLog.removeViewAt(0)
        }
    }
}
