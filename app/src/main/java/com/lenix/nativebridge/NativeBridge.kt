package com.lenix.nativebridge

/**
 * Thin JNI surface for `libpvmnative.so` (H4 in docs/NATIVE_BINARIES.md).
 *
 * The library is optional in v0.1: PRoot is launched as a separate process, the
 * terminal falls back to pipe-backed stdio when [openPtyMaster] is unavailable,
 * and zstd extraction stays refused until this `.so` actually loads. Callers must
 * check [available] rather than assuming JNI is present.
 *
 * Native methods are invoked reflectively so this class still compiles when the
 * NDK / `.so` is not part of the APK (no `external` declarations).
 */
object NativeBridge {

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var loadError: String? = "libpvmnative.so is not bundled in this build"
        private set

    fun tryLoad(loader: () -> Unit = { System.loadLibrary("pvmnative") }): Boolean {
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
        return invokeNative("nativeOpenPty") as? Int ?: -1
    }

    fun killProcessGroup(pid: Long, signal: Int): Boolean {
        if (!available) return false
        return invokeNative("nativeKillpg", pid, signal) as? Boolean ?: false
    }

    private fun invokeNative(name: String, vararg args: Any): Any? = try {
        val types = args.map { arg ->
            when (arg) {
                is Int -> Int::class.javaPrimitiveType
                is Long -> Long::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> arg.javaClass
            }
        }.toTypedArray()
        val method = javaClass.getDeclaredMethod(name, *types)
        method.isAccessible = true
        method.invoke(this, *args)
    } catch (_: Throwable) {
        null
    }
}
