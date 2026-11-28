package com.corvofeng.fakeatv

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
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
    private lateinit var tvButtonMapping: TextView
    private lateinit var tvInjectionMode: TextView
    private lateinit var tvDriverStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnMenuBinding: Button
    private lateinit var btnButtonMapping: Button
    private lateinit var btnResetMapping: Button
    private lateinit var btnInjectionMode: Button

    private val logEntries = mutableListOf<String>()

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
        tvButtonMapping = findViewById(R.id.tvButtonMapping)
        tvInjectionMode = findViewById(R.id.tvInjectionMode)
        tvDriverStatus = findViewById(R.id.tvDriverStatus)
        tvLog = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnMenuBinding = findViewById(R.id.btnMenuBinding)
        btnButtonMapping = findViewById(R.id.btnButtonMapping)
        btnResetMapping = findViewById(R.id.btnResetMapping)
        btnInjectionMode = findViewById(R.id.btnInjectionMode)

        btnMenuBinding.setOnClickListener {
            showMenuBindingDialog()
        }

        btnButtonMapping.setOnClickListener {
            showButtonPickerDialog()
        }

        btnResetMapping.setOnClickListener {
            DeviceInfo.clearAllButtonMappings(this)
            appendLog("[Config] 按钮自定义已重置为默认")
            updateUi()
            Toast.makeText(this, "已重置为默认映射", Toast.LENGTH_SHORT).show()
        }

        btnInjectionMode.setOnClickListener {
            showInjectionModeDialog()
        }

        btnToggle.setOnClickListener {
            if (AtvService.isRunning) {
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

        // Auto-start service on app launch
        if (!AtvService.isRunning) {
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
        val running = AtvService.isRunning
        val deviceName = DeviceInfo.getDeviceName(this)
        val deviceId = DeviceInfo.getDeviceId(this)
        tvStatus.text = if (running) "$deviceName: Running" else "$deviceName: Stopped"
        tvStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        btnToggle.text = if (running) "Stop Server" else "Start Server"

        tvIp.text = "IP: ${getWifiIpAddress()}   ID: $deviceId"
        tvPin.text = "Pairing PIN: 1111"

        val currentBinding = DeviceInfo.getMenuButtonBinding(this)
        tvMenuBinding.text = "Menu 键映射: ${getMenuBindingDisplayName(currentBinding)}"

        val customCount = DeviceInfo.getPrefs(this).all.keys.count { it.startsWith("btnmap_") }
        tvButtonMapping.text = if (customCount == 0)
            "按钮自定义: 默认映射 (点此配置单击/长按/双击)"
        else
            "按钮自定义: $customCount 项已改 (点此继续配置)"

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

    private fun showInjectionModeDialog() {
        val modes = InjectionMode.values()
        val options = modes.map { it.displayName }.toTypedArray()
        val current = KeyDispatcher.currentMode
        val currentIndex = modes.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("选择按键注入模式 (优先策略)")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = modes[which]
                KeyDispatcher.setMode(this, selected)
                appendLog("[Config] 按键注入模式: ${selected.displayName}")
                updateUi()
                Toast.makeText(this, "已切换: ${selected.displayName}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMenuBindingDialog() {
        val options = arrayOf(
            "无 (默认: 保持原始按键功能)",
            "播放/暂停 键 (play_pause, 推荐，位于左下角)",
            "电视/Home 图标 键 (home, 位于右下角)",
            "静音 键 (mute, 位于左上角)",
            "返回 键 (menu, 位于底部中间)"
        )
        val values = arrayOf("none", "play_pause", "home", "mute", "menu")
        val current = DeviceInfo.getMenuButtonBinding(this)
        val currentIndex = values.indexOf(current).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("绑定遥控器按键为 Android Menu 键")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = values[which]
                DeviceInfo.setMenuButtonBinding(this, selected)
                RemoteController.menuBindingButton = selected
                AtvAccessibilityService.instance?.menuBindingButton = selected

                // Notify running AtvService
                val intent = Intent(this, AtvService::class.java).apply {
                    action = AtvService.ACTION_SET_MENU_BINDING
                    putExtra(AtvService.EXTRA_BUTTON, selected)
                }
                startService(intent)

                appendLog("[Config] Menu 键绑定: ${options[which]}")
                updateUi()
                Toast.makeText(this, "已设置: ${options[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- mitv4a v1.6.0: button customisation (button -> gesture -> action) ----

    private fun showButtonPickerDialog() {
        val buttons = ButtonMapping.BUTTONS
        val labels = buttons.map { "${it.label}  (${it.name})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("第 1 步：选择按钮")
            .setItems(labels) { _, which ->
                showGesturePickerDialog(buttons[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGesturePickerDialog(button: ButtonMapping.Button) {
        val gLabels = ButtonMapping.GESTURES.map { ButtonMapping.gestureLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("第 2 步：${button.label} — 选择手势")
            .setItems(gLabels) { _, gWhich ->
                val gesture = ButtonMapping.GESTURES[gWhich]
                showActionPickerDialog(button, gesture)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showActionPickerDialog(button: ButtonMapping.Button, gesture: String) {
        val actionLabels = ButtonMapping.ACTIONS.map { it.second }.toTypedArray()
        val actionValues = ButtonMapping.ACTIONS.map { it.first }.toTypedArray()

        // Current effective value (explicit mapping, else click mapping, else default).
        var currentAction = DeviceInfo.getButtonMapping(this, button.name, gesture)
        if (currentAction == null && gesture != ButtonMapping.GESTURE_CLICK) {
            currentAction = DeviceInfo.getButtonMapping(this, button.name, ButtonMapping.GESTURE_CLICK)
        }
        if (currentAction == null) currentAction = button.defaultKey
        val currentIndex = actionValues.indexOf(currentAction).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("第 3 步：${button.label} / ${ButtonMapping.gestureLabel(gesture)} → 动作")
            .setMessage("TVRemoteIME 按键用字符串键名注入；长按=按下→延时→抬起，双击=两次按键。")
            .setSingleChoiceItems(actionLabels, currentIndex) { dialog, which ->
                val action = actionValues[which]
                DeviceInfo.setButtonMapping(this, button.name, gesture, action)
                appendLog("[Config] ${button.name}/${gesture} -> ${ButtonMapping.actionLabel(action)}")
                dialog.dismiss()
                updateUi()
                Toast.makeText(this, "已设置: ${button.label} / ${ButtonMapping.gestureLabel(gesture)} → ${ButtonMapping.actionLabel(action)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                DeviceInfo.setButtonMapping(this, button.name, gesture, null)
                appendLog("[Config] ${button.name}/${gesture} 恢复默认")
                updateUi()
            }
            .show()
    }

    private fun getMenuBindingDisplayName(binding: String): String {
        return when (binding.lowercase()) {
            "play_pause" -> "播放/暂停 键 (play_pause)"
            "home" -> "电视/Home 图标 键 (home)"
            "mute" -> "静音 键 (mute)"
            "menu" -> "返回 键 (menu)"
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
