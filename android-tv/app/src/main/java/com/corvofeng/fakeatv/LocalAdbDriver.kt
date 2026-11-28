package com.corvofeng.fakeatv

import android.content.Context
import android.os.Environment
import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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

    // mitv4a v1.5.3: cap the blocking ADB handshake. dadb's openShell() reads
    // AUTH responses with no socket timeout; when the local adbd never accepts
    // our key the read blocks forever. A foreground/JNI-callback thread calling
    // into us would then stall input dispatching and trigger an ANR. All connect
    // attempts now run on a background thread with a hard timeout.
    private const val CONNECT_TIMEOUT_MS = 2500L

    // mitv4a v1.5.4: directories that may hold a PC-authorized adb keypair.
    // Priority matters:
    //   1) /data/local/tmp/fakeatv-adb — adb push lands here as 0644 shell:shell,
    //      and the path chain is traversable by the app UID, so this is readable
    //      without any storage permission. Files under /sdcard/ pushed via adb
    //      are forced to 0660 root:sdcard_rw by the FUSE layer and chmod is a
    //      no-op there, which is why the Download candidates always EACCES.
    //   2) /sdcard/Download{,/FakeAtv} — legacy layout from the handover doc.
    //   3) app-private external dir (/sdcard/Android/data/<pkg>/files/adb).
    // Falls back to the legacy internal self-generated files/adb key.
    private fun publicKeyDirs(context: Context?): List<File> {
        val dirs = mutableListOf<File>()
        dirs.add(File("/data/local/tmp/fakeatv-adb"))
        try {
            val external = Environment.getExternalStorageDirectory()
            dirs.add(File(external, "Download"))
            dirs.add(File(external, "Download/FakeAtv"))
        } catch (_: Throwable) {}
        if (context != null) {
            context.getExternalFilesDir(null)?.let { dirs.add(File(it, "adb")) }
        }
        return dirs
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    @Volatile private var dadbInstance: Dadb? = null
    @Volatile private var shellStream: AdbShellStream? = null
    // Incremented whenever a connect attempt is abandoned, so a worker that
    // finishes late (after its socket was force-closed) cannot mark us connected.
    @Volatile private var connectEpoch = 0

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
            // v1.5.3: never let the caller's thread block on the ADB handshake.
            initAsync()
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

    /**
     * v1.5.4: resolve an ADB keypair, preferring a PC-authorized keypair that
     * has already been accepted by the TV's adbd. This avoids the AUTH dialog
     * entirely. Priority:
     *   1) /data/local/tmp/fakeatv-adb/adbkey{,.pub} (readable, adb-pushed)
     *   2) /sdcard/Download/adbkey{,.pub} (pushed from the paired PC)
     *   3) /sdcard/Download/FakeAtv/adbkey{,.pub}
     *   4) /sdcard/Android/data/<pkg>/files/adb/adbkey{,.pub}
     *   5) internal files/adb/ (legacy self-generated key)
     */
    private fun resolveKeyPair(context: Context?): AdbKeyPair? {
        if (context != null) {
            for (dir in publicKeyDirs(context)) {
                val priv = File(dir, "adbkey")
                val pub = File(dir, "adbkey.pub")
                if (priv.exists() && pub.exists()) {
                    try {
                        val keyPair = AdbKeyPair.read(priv, pub)
                        Log.i(TAG, "Using public-directory ADB keypair from ${dir.absolutePath}")
                        return keyPair
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to read public ADB keypair at ${dir.absolutePath}: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }
            val keyDir = File(context.filesDir, "adb")
            keyDir.mkdirs()
            val privFile = File(keyDir, "adbkey")
            val pubFile = File(keyDir, "adbkey.pub")
            if (!privFile.exists() || !pubFile.exists()) {
                Log.i(TAG, "Generating internal ADB keypair at ${keyDir.absolutePath}...")
                AdbKeyPair.generate(privFile, pubFile)
            }
            return AdbKeyPair.read(privFile, pubFile)
        }
        return null
    }

    /**
     * Performs the raw connect (may block inside dadb's AUTH readMessage).
     * Must only be called from the background executor thread.
     */
    private fun connectOnce(epoch: Int): Boolean {
        Log.i(TAG, "Attempting to connect to local ADB at $ADB_HOST:$ADB_PORT...")
        val keyPair = resolveKeyPair(appContext)
        val d = if (keyPair != null) {
            Dadb.create(ADB_HOST, ADB_PORT, keyPair)
        } else {
            Dadb.create(ADB_HOST, ADB_PORT)
        }
        // Publish the instance before the blocking openShell() so a timeout in
        // connectInternal() can close the socket and unblock this worker.
        dadbInstance = d
        val stream = d.openShell()
        if (epoch != connectEpoch) {
            // This attempt was already abandoned/timed out; do not publish.
            try { stream.close() } catch (_: Exception) {}
            return false
        }
        shellStream = stream
        isConnected.set(true)
        Log.i(TAG, "Successfully connected to local ADB daemon!")
        return true
    }

    @Synchronized
    fun connectInternal(): Boolean {
        if (isConnected.get() && shellStream != null) {
            return true
        }
        closeInternal()
        val myEpoch = connectEpoch

        // v1.5.3: bound the blocking handshake on a dedicated worker so a stalled
        // adbd can never wedge the shared executor or (worse) a JNI callback.
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit { connectOnce(myEpoch) }
            val ok: Boolean = try {
                future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
            } catch (e: TimeoutException) {
                Log.w(TAG, "ADB connect timed out after ${CONNECT_TIMEOUT_MS}ms (AUTH handshake stalled)")
                connectEpoch++ // invalidate any late completion from this worker
                future.cancel(true)
                false
            }
            if (!ok) closeInternal()
            return ok
        } catch (e: Throwable) {
            // catch Throwable: dadb key generation can throw NoClassDefFoundError /
            // UnsatisfiedLinkError on API 23; an escaping Error would kill the app.
            Log.w(TAG, "Failed to connect to local ADB ($ADB_HOST:$ADB_PORT): ${e.javaClass.simpleName}: ${e.message}")
            closeInternal()
            return false
        } finally {
            worker.shutdownNow()
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

    /**
     * Execute a raw shell command on the device.
     * v1.5.3: never block the calling thread on the ADB handshake. If the shell
     * is not ready we kick off an async (timeout-bounded) connect and return
     * false immediately. This keeps JNI input callbacks responsive and prevents
     * the "connected then instantly drops / ANR" symptom on MiTV4A.
     */
    private fun execShell(cmd: String): Boolean {
        if (!isConnected.get()) {
            initAsync()
            return false
        }
        return synchronized(this) {
            val stream = shellStream
            when {
                stream == null -> false
                else -> try {
                    stream.write("$cmd\n")
                    Log.d(TAG, "ADB shell: $cmd")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "ADB shell error: ${e.message}, scheduling reconnect")
                    closeInternal()
                    initAsync()
                    false
                }
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
        "channel_up" to "KEYCODE_CHANNEL_UP",
        "channel_down" to "KEYCODE_CHANNEL_DOWN",
        "siri" to "KEYCODE_SEARCH",
        "guide" to "KEYCODE_GUIDE",
        "power" to "KEYCODE_POWER"
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
