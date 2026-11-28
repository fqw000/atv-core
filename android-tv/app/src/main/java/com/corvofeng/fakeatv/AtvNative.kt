package com.corvofeng.fakeatv

object AtvNative {
    /** mitv4a: whether libatv_android.so was loaded successfully. */
    @Volatile
    var isLibraryLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String = ""
        private set

    init {
        try {
            System.loadLibrary("atv_android")
            isLibraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // On API 23 the library can fail to load if a dependency symbol
            // (e.g. getifaddrs) is missing. Keep the flag false so callers can
            // skip native calls gracefully instead of crashing the process.
            loadError = e.message ?: e.javaClass.simpleName
            e.printStackTrace()
        }
    }

    interface Callback {
        fun onButton(name: String)
        fun onTouch(dx: Double, dy: Double, phase: Int)
        fun onAudio(volume: Double, muted: Boolean)
        fun onEvent(kind: String, detail: String)

        /** mitv4a v1.6.0: gesture-aware button (click/long/double). */
        fun onButtonGesture(name: String, gesture: String) {
            // Default: treat as a normal click so older callbacks keep working.
            onButton(name)
        }
    }

    external fun nativeStartServer(
        name: String,
        pin: Int,
        deviceId: String,
        serverId: String,
        localIp: String,
        callback: Callback
    ): Boolean

    external fun nativeStopServer()
    external fun nativeIsRunning(): Boolean
}
