package com.corvofeng.fakeatv

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvMenuBinding: TextView
    private lateinit var tvInjectionMode: TextView
    private lateinit var tvDriverStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnMenuBinding: Button
    private lateinit var btnInjectionMode: Button

    private val logEntries = mutableListOf<String>()

    // --- B fix: in-activity config sheet (no separate dialog window) -----------
    // Root cause: an AlertDialog creates a second window that steals focus on
    // MiTV4A; keys injected by TVRemoteIME (deviceId=-1) are then delivered to
    // the no-longer-focused main window and dropped by ViewRootImpl ("no window
    // focus"). Rendering the chooser as a view inside this Activity keeps a
    // single focused window, so both injected keys and the IR remote work.
    private var configSheet: FrameLayout? = null
    private var configRows = mutableListOf<TextView>()
    private var configItems: List<String> = emptyList()
    private var configSelected = 0
    private var configOnSelect: ((Int) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra(AtvService.EXTRA_EVENT_TYPE) ?: return
            val detail = intent.getStringExtra(AtvService.EXTRA_EVENT_DETAIL) ?: ""
            appendLog("[$type] $detail")
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        KeyDispatcher.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvIp = findViewById(R.id.tvIp)
        tvPin = findViewById(R.id.tvPin)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvMenuBinding = findViewById(R.id.tvMenuBinding)
        tvInjectionMode = findViewById(R.id.tvInjectionMode)
        tvDriverStatus = findViewById(R.id.tvDriverStatus)
        tvLog = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnMenuBinding = findViewById(R.id.btnMenuBinding)
        btnInjectionMode = findViewById(R.id.btnInjectionMode)

        btnMenuBinding.setOnClickListener {
            showMenuBindingSheet()
        }

        btnInjectionMode.setOnClickListener {
            showInjectionModeSheet()
        }

        btnToggle.setOnClickListener {
            if (AtvService.isRunningInApp(this)) {
                val intent = Intent(this, AtvService::class.java).apply {
                    action = AtvService.ACTION_STOP
                }
                startService(intent)
            } else {
                startAtvService()
            }
            btnToggle.postDelayed({ updateUi() }, 500)
        }

        btnAccessibility.setOnClickListener {
            if (RemoteController.isHardwareAvailable) {
                Toast.makeText(this, "Hardware remote driver (/dev/input/event0) is active!\nNo accessibility service needed.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AtvAccessibilityService.isServiceRunning) {
                Toast.makeText(this, "Accessibility Service is already enabled", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                AlertDialog.Builder(this)
                    .setTitle("Accessibility Settings")
                    .setMessage(
                        "This device does not have a system Accessibility Settings page.\n\n" +
                        "Enable via ADB:\n\n" +
                        "adb shell settings put secure enabled_accessibility_services com.corvofeng.fakeatv/.AtvAccessibilityService\n" +
                        "adb shell settings put secure accessibility_enabled 1"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        // Auto-start service on app launch (cross-process query after :atv split)
        if (!AtvService.isRunningInApp(this)) {
            startAtvService()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AtvService.BROADCAST_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        dismissConfigSheet()
        super.onDestroy()
    }

    /**
     * Single-window key handling for the in-activity config chooser.
     * Consumes navigation keys while the sheet is open so they never leak to
     * the underlying buttons; both injected keys and the IR remote arrive here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (configSheet == null) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveConfigSelection(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveConfigSelection(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down) commitConfigSelection()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                if (down) dismissConfigSheet()
                return true
            }
            else -> return true // swallow while the chooser is open
        }
    }

    private fun moveConfigSelection(delta: Int) {
        if (configItems.isEmpty()) return
        configSelected = (configSelected + delta).coerceIn(0, configItems.size - 1)
        refreshConfigHighlight()
    }

    private fun refreshConfigHighlight() {
        configRows.forEachIndexed { i, tv ->
            val selected = i == configSelected
            tv.text = (if (selected) "▶ " else "    ") + configItems[i]
            tv.setTextColor(if (selected) 0xFF81D4FA.toInt() else 0xFFCFD8DC.toInt())
            tv.setBackgroundColor(if (selected) 0xFF37474F.toInt() else Color.TRANSPARENT)
        }
    }

    private fun commitConfigSelection() {
        val cb = configOnSelect ?: return
        val idx = configSelected
        dismissConfigSheet()
        cb(idx)
    }

    private fun dismissConfigSheet() {
        configSheet?.let { (it.parent as? ViewGroup)?.removeView(it) }
        configSheet = null
        configRows.clear()
        configOnSelect = null
    }

    private fun showConfigSheet(
        title: String,
        items: Array<String>,
        currentIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        dismissConfigSheet()
        configItems = items.toList()
        configSelected = currentIndex.coerceIn(0, items.size - 1)
        configOnSelect = onSelect

        val ctx = this
        val sheet = FrameLayout(ctx).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dismissConfigSheet() }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF212121.toInt())
            setPadding(48, 36, 48, 36)
            minimumWidth = 620
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(80, 80, 80, 80) }
            setOnClickListener { } // consume, do not dismiss when tapping the card
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 24)
        }
        card.addView(titleView)

        configRows.clear()
        items.forEachIndexed { i, label ->
            val row = TextView(ctx).apply {
                textSize = 16f
                setPadding(24, 14, 24, 14)
                setOnClickListener { configSelected = i; commitConfigSelection() }
            }
            configRows.add(row)
            card.addView(row)
        }

        val hint = TextView(ctx).apply {
            text = "方向键选择  •  确认键应用  •  返回键取消"
            textSize = 13f
            setTextColor(0xFF90A4AE.toInt())
            setPadding(0, 24, 0, 0)
        }
        card.addView(hint)

        sheet.addView(card)
        addContentView(sheet, sheet.layoutParams)
        configSheet = sheet
        refreshConfigHighlight()
    }

    private fun startAtvService() {
        val deviceName = DeviceInfo.getDeviceName(this)
        val intent = Intent(this, AtvService::class.java).apply {
            action = AtvService.ACTION_START
            putExtra(AtvService.EXTRA_NAME, deviceName)
            putExtra(AtvService.EXTRA_PIN, 1111)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUi() {
        val running = AtvService.isRunningInApp(this)
        val deviceName = DeviceInfo.getDeviceName(this)
        val deviceId = DeviceInfo.getDeviceId(this)
        tvStatus.text = if (running) "$deviceName: Running" else "$deviceName: Stopped"
        tvStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        btnToggle.text = if (running) "Stop Server" else "Start Server"

        tvIp.text = "IP: ${getWifiIpAddress()}   ID: $deviceId"
        tvPin.text = "Pairing PIN: 1111"

        val currentBinding = DeviceInfo.getMenuButtonBinding(this)
        tvMenuBinding.text = "Menu 键映射: ${getMenuBindingDisplayName(currentBinding)}"

        // Injection mode display
        val mode = KeyDispatcher.currentMode
        tvInjectionMode.text = "按键注入模式: ${mode.displayName}"

        val hwOk = RemoteController.isHardwareAvailable
        val httpStr = HttpKeyDriver.describe()
        val accessOk = AtvAccessibilityService.isServiceRunning
        val adbOk = LocalAdbDriver.isConnected.get()

        val hwStr = if (hwOk) "硬件[●可用]" else "硬件[○无]"
        val adbStr = if (adbOk) "ADB[●已连]" else "ADB[○断开]"
        val a11yStr = if (accessOk) "无障碍[●就绪]" else "无障碍[○关闭]"
        val lastUsed = KeyDispatcher.lastUsedEngine

        tvDriverStatus.text = "按键: $hwStr | $httpStr | $a11yStr\n光标: $adbStr\n当前生效: $lastUsed"
        tvAccessibility.text = "无障碍状态: " + if (accessOk) "已开启 (可兜底)" else "未开启 (普通电视推荐开启)"
        tvAccessibility.setTextColor(if (accessOk) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun showInjectionModeSheet() {
        val modes = InjectionMode.values()
        val options = modes.map { it.displayName }.toTypedArray()
        val current = KeyDispatcher.currentMode
        val currentIndex = modes.indexOf(current).let { if (it >= 0) it else 0 }

        showConfigSheet("选择按键注入模式 (优先策略)", options, currentIndex) { which ->
            val selected = modes[which]
            KeyDispatcher.setMode(this, selected)
            // Sync the mode into the :atv service process (A2: separate process).
            val intent = Intent(this, AtvService::class.java).apply {
                action = AtvService.ACTION_SET_INJECTION_MODE
                putExtra(AtvService.EXTRA_MODE, selected.key)
            }
            startService(intent)
            appendLog("[Config] 按键注入模式: ${selected.displayName}")
            updateUi()
            Toast.makeText(this, "已切换: ${selected.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenuBindingSheet() {
        val options = arrayOf(
            "无 (默认: 保持原始按键功能)",
            "播放/暂停 键 (play_pause)",
            "电视/Home 图标 键 (home)",
            "返回 键 (menu)",
            "静音 键 (mute, 左上)",
            "电源 键 (power, 右上)",
            "CH 频道+ 键 (channel_up, 右下)",
            "CH 频道- 键 (channel_down, 右下)"
        )
        val values = arrayOf(
            "none", "play_pause", "home", "menu",
            "mute", "power", "channel_up", "channel_down"
        )
        val current = DeviceInfo.getMenuButtonBinding(this)
        val currentIndex = values.indexOf(current).let { if (it >= 0) it else 0 }

        showConfigSheet("绑定遥控器按键为 Android Menu 键", options, currentIndex) { which ->
            val selected = values[which]
            DeviceInfo.setMenuButtonBinding(this, selected)
            RemoteController.menuBindingButton = selected
            AtvAccessibilityService.instance?.menuBindingButton = selected

            // Notify running AtvService (cross-process via startService)
            val intent = Intent(this, AtvService::class.java).apply {
                action = AtvService.ACTION_SET_MENU_BINDING
                putExtra(AtvService.EXTRA_BUTTON, selected)
            }
            startService(intent)

            appendLog("[Config] Menu 键绑定: ${options[which]}")
            updateUi()
            Toast.makeText(this, "已设置: ${options[which]}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMenuBindingDisplayName(binding: String): String {
        return when (binding.lowercase()) {
            "play_pause" -> "播放/暂停 键 (play_pause)"
            "home" -> "电视/Home 图标 键 (home)"
            "menu" -> "返回 键 (menu)"
            "mute" -> "静音 键 (mute)"
            "power" -> "电源 键 (power)"
            "channel_up" -> "CH 频道+ 键 (channel_up)"
            "channel_down" -> "CH 频道- 键 (channel_down)"
            "none", "" -> "无 (默认)"
            else -> binding
        }
    }

    private fun appendLog(line: String) {
        logEntries.add(0, line)
        if (logEntries.size > 15) {
            logEntries.removeLast()
        }
        tvLog.text = logEntries.joinToString("\n")
    }

    private fun getWifiIpAddress(): String {
        return DeviceInfo.getLocalIpAddress(this) ?: "Unknown"
    }
}
