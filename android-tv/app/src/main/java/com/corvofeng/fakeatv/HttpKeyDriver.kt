package com.corvofeng.fakeatv

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP key-injection driver (mitv4a port).
 *
 * Replaces the local-ADB channel. The TV runs TVRemoteIME (or any compatible
 * HTTP remote service) exposing POST /key with an Android keycode in `code`.
 * Since the receiver App lives on the TV itself, the default target is the
 * loopback address 127.0.0.1:9978 - no network ADB, no reboot re-enable.
 *
 * Only the buttons the native iOS Apple TV Remote actually emits are mapped.
 */
object HttpKeyDriver {
    private const val TAG = "HttpKeyDriver"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 9978
    private const val PATH_KEY = "/key"

    /**
     * Legacy fallback map: iPhone button name -> Android KEYCODE *name* string.
     * TVRemoteIME resolves via KeyEvent.keyCodeFromString, so string names are
     * required; bare numbers parse to KEYCODE_UNKNOWN and silently do nothing.
     * The full/custom mapping now lives in ButtonMapping.
     */
    private val KEY_MAP = mapOf(
        "up" to "KEYCODE_DPAD_UP",
        "down" to "KEYCODE_DPAD_DOWN",
        "left" to "KEYCODE_DPAD_LEFT",
        "right" to "KEYCODE_DPAD_RIGHT",
        "select" to "KEYCODE_DPAD_CENTER",
        "menu" to "KEYCODE_BACK",
        "home" to "KEYCODE_HOME",
        "volume_up" to "KEYCODE_VOLUME_UP",
        "volume_down" to "KEYCODE_VOLUME_DOWN",
        "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE",
        "power" to "KEYCODE_POWER"
    )

    @Volatile
    var host: String = DEFAULT_HOST

    @Volatile
    var port: Int = DEFAULT_PORT

    val lastOk = AtomicBoolean(false)

    @Volatile
    var lastError: String = ""
        private set

    fun init(context: Context) {
        host = DeviceInfo.getHttpHost(context)
        port = DeviceInfo.getHttpPort(context)
        Log.i(TAG, "HttpKeyDriver initialized -> http://$host:$port$PATH_KEY")
    }

    fun reconfigure(context: Context, newHost: String, newPort: Int) {
        host = newHost.ifBlank { DEFAULT_HOST }
        port = if (newPort in 1..65535) newPort else DEFAULT_PORT
        DeviceInfo.setHttpTarget(context, host, port)
        Log.i(TAG, "HttpKeyDriver reconfigured -> http://$host:$port$PATH_KEY")
    }

    /**
     * Send one key press using the resolved Android key NAME (string form that
     * TVRemoteIME's keyCodeFromString understands, e.g. "KEYCODE_VOLUME_MUTE").
     * [action] may also be a special token like ACTION_CLEAR_APPS.
     */
    fun sendAction(action: String): Boolean {
        if (action == ButtonMapping.ACTION_NONE) return false
        if (action == ButtonMapping.ACTION_CLEAR_APPS) return clearApps()
        return post("key", "code=$action")
    }

    /** Compatibility wrapper used by legacy callers (menu-binding path). */
    fun sendKey(name: String, isMappedToMenu: Boolean = false): Boolean {
        val lower = name.lowercase().trim()
        val action = if (isMappedToMenu) "KEYCODE_MENU" else (KEY_MAP[lower] ?: return false)
        return post("key", "code=$action")
    }

    /**
     * Long press: send /keydown, wait, then /keyup. TVRemoteIME exposes both
     * endpoints (InputRequestProcesser.KEY_ACTION_DOWN/UP).
     */
    fun sendLong(action: String, holdMs: Long = 600): Boolean {
        if (action == ButtonMapping.ACTION_NONE) return false
        if (action == ButtonMapping.ACTION_CLEAR_APPS) return clearApps()
        val down = post("keydown", "code=$action")
        if (!down) return false
        try { Thread.sleep(holdMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return post("keyup", "code=$action")
    }

    /** Double tap: two /key presses with a short gap. */
    fun sendDouble(action: String, gapMs: Long = 130): Boolean {
        if (action == ButtonMapping.ACTION_NONE) return false
        if (action == ButtonMapping.ACTION_CLEAR_APPS) return clearApps()
        val first = post("key", "code=$action")
        if (!first) return false
        try { Thread.sleep(gapMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return post("key", "code=$action")
    }

    /**
     * "Clear background": open the recents/app-switch panel. A full
     * "clear all" gesture is firmware-specific and cannot be performed via a
     * keycode alone; KEYCODE_APP_SWITCH is the reliable, standard action and
     * the user can swipe-clear from there. Logged clearly so behaviour is
     * transparent rather than silently doing nothing.
     */
    private fun clearApps(): Boolean {
        Log.i(TAG, "ACTION_CLEAR_APPS -> KEYCODE_APP_SWITCH (swipe to clear all in recents)")
        return post("key", "code=KEYCODE_APP_SWITCH")
    }

    private fun post(path: String, payloadStr: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$host:$port/$path")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 700
            conn.readTimeout = 700
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val payload = payloadStr.toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val rc = conn.responseCode
            val ok = rc in 200..299
            lastOk.set(ok)
            lastError = if (ok) "" else "HTTP $rc"
            if (!ok) Log.w(TAG, "POST /$path $payloadStr -> HTTP $rc")
            ok
        } catch (e: Exception) {
            lastOk.set(false)
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "POST /$path $payloadStr failed: $lastError")
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun describe(): String =
        if (lastOk.get()) "HTTP[●已连]" else "HTTP[○失败:${lastError.ifBlank { "未测试" }}]"
}
