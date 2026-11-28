package com.corvofeng.fakeatv

import android.content.Context

/**
 * mitv4a v1.6.0 — button catalogue and user-customisable mapping.
 *
 * The iPhone Apple-TV remote emits a fixed set of button names (see
 * `hid_command_name` in atv-core). Historically only a handful were wired to
 * Android keys, so the top row (mute / menu / power) and the bottom-right
 * channel arrows appeared "dead". This object:
 *
 *  1. Lists every button the phone can send, with a human label and a default
 *     Android key name.
 *  2. Maps each button to the *string* key name that TVRemoteIME's `/key`
 *     endpoint expects (`KeyEvent.keyCodeFromString`), e.g. "KEYCODE_MUTE".
 *     Sending bare numbers (code=19) silently解析成 KEYCODE_UNKNOWN, which is
 *     why some keys looked dead even though HTTP returned 200.
 *  3. Resolves per-button, per-gesture (click/long/double) user overrides.
 */
object ButtonMapping {

    // Special action tokens — declared first so ACTIONS can reference them
    // during property initialisation (object properties init in declaration order).
    const val ACTION_NONE = "__NONE__"
    const val ACTION_CLEAR_APPS = "ACTION_CLEAR_APPS"

    /** A button the iPhone remote can emit. */
    data class Button(
        val name: String,          // native name, e.g. "channel_up"
        val label: String,         // Chinese label for the config UI
        val defaultKey: String,    // default Android key name (TVRemoteIME string form)
    )

    /**
     * Gesture identifiers coming from native `onButtonGesture`.
     */
    const val GESTURE_CLICK = "click"
    const val GESTURE_LONG = "long"
    const val GESTURE_DOUBLE = "double"

    val GESTURES = arrayOf(GESTURE_CLICK, GESTURE_LONG, GESTURE_DOUBLE)

    fun gestureLabel(g: String): String = when (g) {
        GESTURE_CLICK -> "单击"
        GESTURE_LONG -> "长按 (~0.5s)"
        GESTURE_DOUBLE -> "双击"
        else -> g
    }

    /**
     * All buttons the phone can send. `defaultKey` uses Android KeyEvent KEYCODE
     * names (the exact strings TVRemoteIME's keyCodeFromString understands).
     * Buttons that have no natural system action are given a sensible default:
     *  - guide        -> KEYCODE_GUIDE
     *  - sleep/wake   -> KEYCODE_SLEEP / KEYCODE_WAKEUP
     *  - screensaver  -> KEYCODE_DREAM (may be unsupported on API 23 -> harmless)
     *  - siri         -> KEYCODE_SEARCH
     */
    val BUTTONS = listOf(
        Button("up", "方向键 上", "KEYCODE_DPAD_UP"),
        Button("down", "方向键 下", "KEYCODE_DPAD_DOWN"),
        Button("left", "方向键 左", "KEYCODE_DPAD_LEFT"),
        Button("right", "方向键 右", "KEYCODE_DPAD_RIGHT"),
        Button("select", "确认 (中间键)", "KEYCODE_DPAD_CENTER"),
        Button("menu", "返回 / 菜单", "KEYCODE_BACK"),
        Button("home", "主页 (电视图标)", "KEYCODE_HOME"),
        Button("play_pause", "播放 / 暂停", "KEYCODE_MEDIA_PLAY_PAUSE"),
        Button("volume_up", "音量 +", "KEYCODE_VOLUME_UP"),
        Button("volume_down", "音量 -", "KEYCODE_VOLUME_DOWN"),
        Button("mute", "静音", "KEYCODE_VOLUME_MUTE"),
        Button("power", "开关机", "KEYCODE_POWER"),
        Button("channel_up", "频道 + (ch▲)", "KEYCODE_CHANNEL_UP"),
        Button("channel_down", "频道 - (ch▼)", "KEYCODE_CHANNEL_DOWN"),
        Button("siri", "Siri / 语音", "KEYCODE_SEARCH"),
        Button("guide", "节目指南", "KEYCODE_GUIDE"),
        Button("sleep", "睡眠", "KEYCODE_SLEEP"),
        Button("wake", "唤醒", "KEYCODE_WAKEUP"),
        Button("screensaver", "屏保", "KEYCODE_DREAM"),
    )

    /** Selectable actions in the config dialog (Android key names + specials). */
    val ACTIONS: List<Pair<String, String>> = listOf(
        "KEYCODE_DPAD_UP" to "方向键 上",
        "KEYCODE_DPAD_DOWN" to "方向键 下",
        "KEYCODE_DPAD_LEFT" to "方向键 左",
        "KEYCODE_DPAD_RIGHT" to "方向键 右",
        "KEYCODE_DPAD_CENTER" to "确认 / OK",
        "KEYCODE_BACK" to "返回",
        "KEYCODE_HOME" to "主页",
        "KEYCODE_MENU" to "系统菜单",
        "KEYCODE_MEDIA_PLAY_PAUSE" to "播放 / 暂停",
        "KEYCODE_MEDIA_NEXT" to "下一曲/下一集",
        "KEYCODE_MEDIA_PREVIOUS" to "上一曲/上一集",
        "KEYCODE_VOLUME_UP" to "音量 +",
        "KEYCODE_VOLUME_DOWN" to "音量 -",
        "KEYCODE_VOLUME_MUTE" to "静音",
        "KEYCODE_POWER" to "电源",
        "KEYCODE_CHANNEL_UP" to "频道 +",
        "KEYCODE_CHANNEL_DOWN" to "频道 -",
        "KEYCODE_SEARCH" to "搜索 / 语音",
        "KEYCODE_GUIDE" to "节目指南",
        "KEYCODE_SLEEP" to "睡眠",
        "KEYCODE_WAKEUP" to "唤醒",
        "KEYCODE_DREAM" to "屏保",
        "KEYCODE_APP_SWITCH" to "最近任务 / 多任务",
        ACTION_CLEAR_APPS to "清除后台 (最近任务→清全部)",
        ACTION_NONE to "无操作 (屏蔽此键)",
    )

    fun findButton(name: String): Button? =
        BUTTONS.find { it.name.equals(name, ignoreCase = true) }

    fun actionLabel(action: String): String =
        ACTIONS.find { it.first == action }?.second ?: action

    /**
     * Resolve the effective Android key/action for a button+gesture.
     * Order of precedence:
     *   1. user mapping for (button, gesture)
     *   2. for long/double gestures with no explicit mapping: fall back to the
     *      user's click mapping if present (so re-binding a key applies to all
     *      gestures unless overridden), else the button's default key.
     * Returns null when the action is ACTION_NONE.
     */
    fun resolve(context: Context, buttonName: String, gesture: String): String? {
        val btn = findButton(buttonName) ?: return fallbackByRawName(buttonName)
        // exact gesture override
        DeviceInfo.getButtonMapping(context, btn.name, gesture)?.let { return normalize(it) }
        // long/double fall back to click mapping, then default
        if (gesture != GESTURE_CLICK) {
            DeviceInfo.getButtonMapping(context, btn.name, GESTURE_CLICK)?.let { return normalize(it) }
        }
        return btn.defaultKey
    }

    /** Unknown button: still allow a raw KEYCODE_ mapping if the user set one. */
    private fun fallbackByRawName(buttonName: String): String? =
        if (buttonName.startsWith("KEYCODE_")) buttonName else null

    private fun normalize(action: String): String? =
        if (action.equals(ACTION_NONE, ignoreCase = true)) null else action
}
