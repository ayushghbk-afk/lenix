package com.lenix.nativebridge

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * First-run copy of host-side engine binaries out of the APK.
 *
 * Android 10+ W^X forbids executing assets in place (ARCHITECTURE.md §5.3): they are
 * copied to `filesDir/native/<abi>/` and chmod 0700. GPL components stay as separate
 * files executed via [ProcessBuilder], never `dlopen`'d (ADR-001).
 *
 * Missing assets are not an error at copy time — [GuestEngine] fails later as
 * [com.lenix.vm.VmError.NATIVE_ENGINE_FAILED] when `proot` is actually needed.
 */
object NativeSetup {

    const val NATIVE_DIR = "native"
    const val PROOT = "proot"
    const val TINI = "tini"
    const val BUSYBOX = "busybox"

    val REQUIRED_FOR_GUEST = listOf(PROOT)

    fun nativeDir(filesDir: File, abi: String = DEFAULT_ABI): File =
        File(filesDir, "$NATIVE_DIR/$abi")

    /**
     * Copies each named asset `native/<abi>/<name>` into [destDir] when the asset exists.
     * Existing files with a matching sha256 are left alone.
     */
    fun installFromAssets(
        destDir: File,
        abi: String,
        openAsset: (String) -> InputStream?,
        names: List<String> = listOf(PROOT, TINI, BUSYBOX),
    ): InstallReport {
        destDir.mkdirs()
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (name in names) {
            val assetPath = "$NATIVE_DIR/$abi/$name"
            val dest = File(destDir, name)
            val stream = openAsset(assetPath)
            if (stream == null) {
                missing += name
                continue
            }
            stream.use { input ->
                val bytes = input.readBytes()
                if (dest.isFile && dest.length() == bytes.size.toLong() && sha256(dest) == sha256(bytes)) {
                    skipped += name
                } else {
                    dest.writeBytes(bytes)
                    dest.setExecutable(true, true)
                    dest.setReadable(true, true)
                    dest.setWritable(true, true)
                    copied += name
                }
            }
        }
        return InstallReport(copied = copied, skipped = skipped, missing = missing)
    }

    fun hasProot(filesDir: File, abi: String = DEFAULT_ABI): Boolean =
        File(nativeDir(filesDir, abi), PROOT).isFile

    data class InstallReport(
        val copied: List<String>,
        val skipped: List<String>,
        val missing: List<String>,
    )

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    const val DEFAULT_ABI = "arm64-v8a"
}
