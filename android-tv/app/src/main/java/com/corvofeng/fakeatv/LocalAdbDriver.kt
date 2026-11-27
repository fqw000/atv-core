package com.corvofeng.fakeatv

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local ADB driver (mitv4a port).
 *
 * Connects to the TV's own adbd over loopback 127.0.0.1:5555 and executes
 * `input` shell commands. On API 23 GestureDescription (dispatchGesture) is
 * unavailable, so mouse-cursor tap/swipe are implemented here via
 * `input tap` / `input swipe` instead.
 *
 * NOTE: key-button injection now goes through HttpKeyDriver (TVRemoteIME) so
 * the remote works without manually re-enabling ADB after every reboot.
 * This driver is retained specifically for cursor gestures.
 */
object LocalAdbDriver {
    private const val TAG = "LocalAdbDriver"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555

    private val executor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var dadbInstance: Dadb? = null
    private var shellStream: AdbShellStream? = null

    val isConnected = AtomicBoolean(false)

    // Throttled reconnect probe: ADB may be turned on after boot, so detect it
    // within a few seconds without attempting a socket connect on every key press.
    private var lastConnectAttemptMs = 0L
    private const val RETRY_INTERVAL_MS = 5000L

    fun isConnectedOrRetry(): Boolean {
        if (isConnected.get()) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastConnectAttemptMs >= RETRY_INTERVAL_MS) {
            lastConnectAttemptMs = now
            return connectInternal()
        }
        return false
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        initAsync()
    }

    fun initAsync(callback: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val ok = connectInternal()
            callback?.invoke(ok)
        }
    }

    @Synchronized
    fun connectInternal(): Boolean {
        if (isConnected.get() && shellStream != null) {
            return true
        }
        closeInternal()
        try {
            Log.i(TAG, "Attempting to connect to local ADB at $ADB_HOST:$ADB_PORT...")
            val context = appContext
            val d = if (context != null) {
                val keyDir = File(context.filesDir, "adb")
                keyDir.mkdirs()
                val privFile = File(keyDir, "adbkey")
                val pubFile = File(keyDir, "adbkey.pub")
                if (!privFile.exists() || !pubFile.exists()) {
                    Log.i(TAG, "Generating internal ADB keypair at $keyDir...")
                    AdbKeyPair.generate(privFile, pubFile)
                }
                val keyPair = AdbKeyPair.read(privFile, pubFile)
                Dadb.create(ADB_HOST, ADB_PORT, keyPair)
            } else {
                Dadb.create(ADB_HOST, ADB_PORT)
            }

            val stream = d.openShell()
            dadbInstance = d
            shellStream = stream
            isConnected.set(true)
            Log.i(TAG, "Successfully connected to local ADB daemon!")
            return true
        } catch (e: Throwable) {
            // mitv4a: catch Throwable (not just Exception) because dadb's key
            // generation can throw NoClassDefFoundError / UnsatisfiedLinkError
            // on API 23 if a Java 8+ class or native symbol is unavailable.
            // An Error escaping this executor thread would kill the whole app.
            Log.w(TAG, "Failed to connect to local ADB ($ADB_HOST:$ADB_PORT): ${e.javaClass.simpleName}: ${e.message}")
            closeInternal()
            return false
        }
    }

    @Synchronized
    fun closeInternal() {
        try { shellStream?.close() } catch (_: Exception) {}
        try { dadbInstance?.close() } catch (_: Exception) {}
        shellStream = null
        dadbInstance = null
        isConnected.set(false)
    }

    /** Execute a raw shell command on the device, reconnecting once on failure. */
    private fun execShell(cmd: String): Boolean {
        if (!isConnected.get()) {
            if (!connectInternal()) return false
        }
        return synchronized(this) {
            try {
                val stream = shellStream ?: return false
                stream.write("$cmd\n")
                Log.d(TAG, "ADB shell: $cmd")
                true
            } catch (e: Exception) {
                Log.w(TAG, "ADB shell error: ${e.message}, retrying with reconnect")
                closeInternal()
                if (connectInternal()) {
                    try {
                        shellStream?.write("$cmd\n")
                        return true
                    } catch (_: Exception) {}
                }
                false
            }
        }
    }

    /** Mouse-cursor single tap. Replaces GestureDescription (API 24+) on API 23. */
    fun tap(x: Float, y: Float): Boolean {
        return execShell("input tap ${x.toInt()} ${y.toInt()}")
    }

    /** Mouse-cursor swipe/drag. Replaces GestureDescription (API 24+) on API 23. */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        return execShell("input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $durationMs")
    }

    private val KEY_MAP = mapOf(
        "up" to "KEYCODE_DPAD_UP",
        "down" to "KEYCODE_DPAD_DOWN",
        "left" to "KEYCODE_DPAD_LEFT",
        "right" to "KEYCODE_DPAD_RIGHT",
        "select" to "KEYCODE_DPAD_CENTER",
        "menu" to "KEYCODE_BACK",
        "home" to "KEYCODE_HOME",
        "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE",
        "volume_up" to "KEYCODE_VOLUME_UP",
        "volume_down" to "KEYCODE_VOLUME_DOWN",
        "mute" to "KEYCODE_VOLUME_MUTE",
        "power" to "KEYCODE_POWER",
        "siri" to "KEYCODE_SEARCH"
    )

    /** Kept for compatibility / optional use; primary button path is HttpKeyDriver. */
    fun sendKey(buttonName: String, isMappedToMenu: Boolean = false): Boolean {
        val keycode = if (isMappedToMenu) {
            "KEYCODE_MENU"
        } else {
            KEY_MAP[buttonName.lowercase()] ?: "KEYCODE_${buttonName.uppercase()}"
        }
        return execShell("input keyevent $keycode")
    }
}
