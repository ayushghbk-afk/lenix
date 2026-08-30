package com.lenix.nativebridge

/**
 * Thin JNI surface for `libpvmnative.so` (H4 in docs/NATIVE_BINARIES.md).
 *
 * The library is optional in v0.1: PRoot is launched as a separate process, the
 * terminal falls back to pipe-backed stdio when [openPtyMaster] is unavailable,
 * and zstd extraction stays refused until this `.so` actually loads. Callers must
 * check [available] rather than assuming JNI is present.
 */
object NativeBridge {

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = "libpvmnative.so is not bundled in this build"
        private set

    fun tryLoad(loader: () -> Unit = DEFAULT_LOADER): Boolean {
        if (available) return true
        return try {
            loader()
            available = true
            loadError = null
            true
        } catch (e: Throwable) {
            available = false
            loadError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Opens a PTY master. Returns a native fd, or -1 when the library is missing
     * or `/dev/ptmx` is blocked (ARCHITECTURE.md §15).
     */
    fun openPtyMaster(): Int {
        if (!available) return -1
        return nativeOpenPty()
    }

    fun killProcessGroup(pid: Long, signal: Int): Boolean {
        if (!available) return false
        return nativeKillpg(pid, signal)
    }

    private external fun nativeOpenPty(): Int

    private external fun nativeKillpg(pid: Long, signal: Int): Boolean

    private val DEFAULT_LOADER: () -> Unit = {
        System.loadLibrary("pvmnative")
    }
}
