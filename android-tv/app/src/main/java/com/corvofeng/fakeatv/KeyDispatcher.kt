package com.corvofeng.fakeatv

import android.content.Context
import android.util.Log

enum class InjectionMode(val key: String, val displayName: String) {
    AUTO("auto", "自动自适应 (硬件 -> HTTP -> 无障碍)"),
    HTTP("http", "仅 HTTP (TVRemoteIME 127.0.0.1:9978)"),
    ADB("adb", "仅 ADB (本机 127.0.0.1:5555)"),
    EVENT("event", "仅硬件事件 (/dev/input)"),
    A11Y("a11y", "仅无障碍服务 (Accessibility)");

    companion object {
        fun fromKey(key: String): InjectionMode {
            return values().find { it.key.equals(key, ignoreCase = true) } ?: AUTO
        }
    }
}

object KeyDispatcher {
    private const val TAG = "KeyDispatcher"

    @Volatile
    var currentMode: InjectionMode = InjectionMode.AUTO

    @Volatile
    var menuBindingButton: String = "none"

    @Volatile
    var lastUsedEngine: String = "未执行"
        private set

    fun init(context: Context) {
        val savedModeKey = DeviceInfo.getInjectionMode(context)
        currentMode = InjectionMode.fromKey(savedModeKey)
        menuBindingButton = DeviceInfo.getMenuButtonBinding(context)
        HttpKeyDriver.init(context)
        // mitv4a: ADB is initialized for mouse-cursor gestures (tap/swipe),
        // which cannot use GestureDescription on API 23.
        LocalAdbDriver.init(context)
        Log.i(TAG, "KeyDispatcher initialized: mode=$currentMode, menuBinding=$menuBindingButton")
    }

    fun setMode(context: Context, mode: InjectionMode) {
        currentMode = mode
        DeviceInfo.setInjectionMode(context, mode.key)
        Log.i(TAG, "InjectionMode updated to: $mode")
    }

    fun dispatchButton(name: String): String {
        val lowerName = name.lowercase().trim()
        val isMappedToMenu = menuBindingButton.isNotBlank() &&
                menuBindingButton != "none" &&
                menuBindingButton.equals(lowerName, ignoreCase = true)

        var executedEngine = "none"

        when (currentMode) {
            InjectionMode.AUTO -> {
                // Tier 1: Hardware Event (/dev/input) - only works on rooted boxes
                if (RemoteController.isHardwareAvailable && RemoteController.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "硬件事件驱动 (/dev/input)"
                }
                // Tier 2: HTTP /key to TVRemoteIME (mitv4a primary channel, no ADB needed)
                else if (HttpKeyDriver.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "HTTP /key (TVRemoteIME)"
                }
                // Tier 3: Accessibility Service fallback
                else if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true) {
                    executedEngine = "无障碍服务 (Accessibility)"
                } else {
                    executedEngine = "分发失败 (无可用通道)"
                }
            }

            InjectionMode.HTTP -> {
                if (HttpKeyDriver.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "HTTP /key (TVRemoteIME)"
                } else {
                    executedEngine = "HTTP 失败 (TVRemoteIME 未运行?)"
                }
            }

            InjectionMode.ADB -> {
                if (LocalAdbDriver.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "ADB 驱动 (127.0.0.1:5555)"
                } else {
                    executedEngine = "ADB 失败 (需手动开启网络ADB)"
                }
            }

            InjectionMode.EVENT -> {
                if (RemoteController.sendKey(lowerName, isMappedToMenu)) {
                    executedEngine = "硬件事件驱动"
                } else {
                    executedEngine = "硬件驱动失败 (miTV4A 未 root，此通道不可用)"
                }
            }

            InjectionMode.A11Y -> {
                if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true) {
                    executedEngine = "无障碍服务"
                } else {
                    executedEngine = "无障碍服务失败 (服务未开启)"
                }
            }
        }

        lastUsedEngine = executedEngine
        val display = if (isMappedToMenu) "$name -> MENU" else name
        Log.i(TAG, "dispatchButton: $display via [$executedEngine]")
        return executedEngine
    }
}
