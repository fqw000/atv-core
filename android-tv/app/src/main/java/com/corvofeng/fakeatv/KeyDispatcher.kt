package com.corvofeng.fakeatv

import android.content.Context
import android.util.Log

enum class InjectionMode(val key: String, val displayName: String) {
    AUTO("auto", "自动自适应 (ADB优先 -> HTTP -> 无障碍)"),
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

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
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

    /** Legacy single-click dispatch (kept for compatibility / touchpad buttons). */
    fun dispatchButton(name: String): String =
        dispatchResolved(name, ButtonMapping.GESTURE_CLICK, null)

    /**
     * mitv4a v1.6.0: gesture-aware dispatch. Resolves the user mapping for
     * (button, gesture) to an Android key/action, then executes it through the
     * active injection channel. [name] is the native button name.
     */
    fun dispatchButtonGesture(name: String, gesture: String): String {
        val ctx = appContext
        val action = if (ctx != null) ButtonMapping.resolve(ctx, name, gesture) else null
        return dispatchResolved(name, gesture, action)
    }

    /**
     * Execute [action] (or a legacy menu-binding derived action when null) via
     * the active channel, applying the requested gesture.
     * - HTTP:  click=/key, long=/keydown+wait+/keyup, double=two /key
     * - ADB:   click=keyevent, long=keyevent --longpress, double=two keyevent
     * The EVENT/A11Y channels only support single click (they have no gesture
     * primitive); long/double degrade to a single click there.
     */
    private fun dispatchResolved(name: String, gesture: String, resolvedAction: String?): String {
        val lowerName = name.lowercase().trim()

        // Legacy "bind button as MENU" rule (from the older UI) still applies
        // when there is no explicit per-button mapping.
        val isMappedToMenu = menuBindingButton.isNotBlank() &&
                menuBindingButton != "none" &&
                menuBindingButton.equals(lowerName, ignoreCase = true)

        // If the user explicitly mapped to ACTION_NONE, suppress entirely.
        if (resolvedAction == null && !isMappedToMenu &&
            appContext?.let { DeviceInfo.getButtonMapping(it, lowerName, gesture) } == ButtonMapping.ACTION_NONE
        ) {
            lastUsedEngine = "已屏蔽 (无操作)"
            Log.i(TAG, "dispatchButton: $name/$gesture suppressed (ACTION_NONE)")
            return lastUsedEngine
        }

        var executedEngine = "none"

        when (currentMode) {
            InjectionMode.AUTO -> {
                // Tier 1: ADB (if available).
                if (LocalAdbDriver.isConnectedOrRetry() &&
                    execAdb(lowerName, gesture, resolvedAction, isMappedToMenu)
                ) {
                    executedEngine = "ADB 驱动 (127.0.0.1:5555)"
                }
                // Tier 2: HTTP /key to TVRemoteIME (works without ADB after reboot).
                else if (execHttp(lowerName, gesture, resolvedAction, isMappedToMenu)) {
                    executedEngine = "HTTP /key (TVRemoteIME)"
                }
                // Tier 3: Accessibility Service fallback.
                else if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true) {
                    executedEngine = "无障碍服务 (Accessibility)"
                } else {
                    executedEngine = "分发失败 (无可用通道)"
                }
            }

            InjectionMode.HTTP -> {
                executedEngine = if (execHttp(lowerName, gesture, resolvedAction, isMappedToMenu))
                    "HTTP /key (TVRemoteIME)" else "HTTP 失败 (TVRemoteIME 未运行?)"
            }

            InjectionMode.ADB -> {
                executedEngine = if (execAdb(lowerName, gesture, resolvedAction, isMappedToMenu))
                    "ADB 驱动 (127.0.0.1:5555)" else "ADB 失败 (需手动开启网络ADB)"
            }

            InjectionMode.EVENT -> {
                // Hardware event path has no gesture primitive; single click only.
                executedEngine = if (RemoteController.sendKey(lowerName, isMappedToMenu))
                    "硬件事件驱动" else "硬件驱动失败 (miTV4A 未 root)"
            }

            InjectionMode.A11Y -> {
                executedEngine = if (AtvAccessibilityService.instance?.sendKey(lowerName, isMappedToMenu) == true)
                    "无障碍服务" else "无障碍服务失败 (服务未开启)"
            }
        }

        lastUsedEngine = executedEngine
        Log.i(TAG, "dispatchButton: $name/$gesture -> ${resolvedAction ?: lowerName} via [$executedEngine]")
        return executedEngine
    }

    private fun execHttp(name: String, gesture: String, action: String?, asMenu: Boolean): Boolean {
        val key = action ?: if (asMenu) "KEYCODE_MENU" else ButtonMapping.BUTTONS
            .find { it.name.equals(name, ignoreCase = true) }?.defaultKey
            ?: "KEYCODE_${name.uppercase()}"
        return when (gesture) {
            ButtonMapping.GESTURE_LONG -> HttpKeyDriver.sendLong(key)
            ButtonMapping.GESTURE_DOUBLE -> HttpKeyDriver.sendDouble(key)
            else -> HttpKeyDriver.sendAction(key)
        }
    }

    private fun execAdb(name: String, gesture: String, action: String?, asMenu: Boolean): Boolean {
        val key = action ?: if (asMenu) "KEYCODE_MENU" else
            LocalAdbDriver.keyNameFor(name) ?: "KEYCODE_${name.uppercase()}"
        return when (gesture) {
            ButtonMapping.GESTURE_LONG -> LocalAdbDriver.sendActionGesture(key, gesture)
            ButtonMapping.GESTURE_DOUBLE -> LocalAdbDriver.sendActionGesture(key, gesture)
            else -> LocalAdbDriver.sendActionGesture(key, ButtonMapping.GESTURE_CLICK)
        }
    }
}
