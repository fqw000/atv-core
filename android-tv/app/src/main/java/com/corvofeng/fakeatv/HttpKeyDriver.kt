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

    /** iPhone Apple TV Remote button name -> Android keycode (TVRemoteIME /key). */
    private val KEY_MAP = mapOf(
        "up" to 19,             // DPAD_UP
        "down" to 20,           // DPAD_DOWN
        "left" to 21,           // DPAD_LEFT
        "right" to 22,          // DPAD_RIGHT
        "select" to 23,         // DPAD_CENTER
        "menu" to 4,            // BACK  (iOS remote's back button is named "menu" in atv-core)
        "home" to 3,            // HOME
        "volume_up" to 24,      // VOLUME_UP
        "volume_down" to 25,    // VOLUME_DOWN
        "mute" to 164,          // VOLUME_MUTE
        "play_pause" to 85,     // MEDIA_PLAY_PAUSE
        "channel_up" to 166,    // CHANNEL_UP   (iPhone remote CH▲)
        "channel_down" to 167,  // CHANNEL_DOWN (iPhone remote CH▼)
        "siri" to 84,           // SEARCH / VOICE_ASSIST
        "guide" to 172,         // GUIDE
        "power" to 26           // POWER
    )

    /** Keycode used when the user rebinds a button to Android MENU. */
    private const val CODE_MENU = 82

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
     * Send one key press. [isMappedToMenu] comes from KeyDispatcher's menu-binding rule.
     * Synchronous with a short timeout: loopback replies are typically <10 ms.
     */
    fun sendKey(name: String, isMappedToMenu: Boolean = false): Boolean {
        val lower = name.lowercase().trim()
        val code = if (isMappedToMenu) CODE_MENU else KEY_MAP[lower]
        if (code == null) {
            Log.d(TAG, "No HTTP mapping for '$lower', skip")
            return false
        }
        return postKey(code)
    }

    private fun postKey(code: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$host:$port$PATH_KEY")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 700
            conn.readTimeout = 700
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val payload = "code=$code".toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val rc = conn.responseCode
            val ok = rc in 200..299
            lastOk.set(ok)
            lastError = if (ok) "" else "HTTP $rc"
            if (!ok) Log.w(TAG, "POST /key code=$code -> HTTP $rc")
            ok
        } catch (e: Exception) {
            lastOk.set(false)
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "POST /key code=$code failed: $lastError")
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun describe(): String =
        if (lastOk.get()) "HTTP[●已连]" else "HTTP[○失败:${lastError.ifBlank { "未测试" }}]"
}
