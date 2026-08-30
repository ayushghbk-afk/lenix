package com.lenix.nativebridge

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles automatic preinstallation and autofixing of native engine binaries (PRoot).
 */
object EngineInstaller {

    val DEFAULT_ENGINE_URLS = listOf(
        "https://raw.githubusercontent.com/termux/proot-distro/master/proot",
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot",
    )

    /**
     * Ensures that the PRoot engine is installed under `filesDir/native/<abi>/proot`.
     * If missing, unpacks from assets or downloads from [engineUrls].
     */
    fun ensureOrInstallEngine(
        filesDir: File,
        abi: String = NativeSetup.DEFAULT_ABI,
        openAsset: (String) -> InputStream?,
        engineUrls: List<String> = DEFAULT_ENGINE_URLS,
        downloader: ((String, File) -> Boolean)? = null,
    ): Boolean {
        val destDir = NativeSetup.nativeDir(filesDir, abi)
        destDir.mkdirs()

        // 1. Try unpacking preinstalled assets
        NativeSetup.installFromAssets(
            destDir = destDir,
            abi = abi,
            openAsset = openAsset,
        )

        if (NativeSetup.hasProot(filesDir, abi)) {
            val prootFile = File(destDir, NativeSetup.PROOT)
            prootFile.setExecutable(true, true)
            prootFile.setReadable(true, true)
            prootFile.setWritable(true, true)
            return true
        }

        // 2. Try downloading engine binary
        val prootFile = File(destDir, NativeSetup.PROOT)
        if (downloader != null) {
            for (url in engineUrls) {
                if (downloader(url, prootFile) && prootFile.isFile && prootFile.length() > 0) {
                    prootFile.setExecutable(true, true)
                    prootFile.setReadable(true, true)
                    prootFile.setWritable(true, true)
                    return true
                }
            }
        } else {
            for (urlStr in engineUrls) {
                if (downloadFile(urlStr, prootFile)) {
                    prootFile.setExecutable(true, true)
                    prootFile.setReadable(true, true)
                    prootFile.setWritable(true, true)
                    return true
                }
            }
        }

        return NativeSetup.hasProot(filesDir, abi)
    }

    private fun downloadFile(urlStr: String, destFile: File): Boolean {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            if (connection.responseCode in 200..299) {
                val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmp.isFile && tmp.length() > 0) {
                    if (destFile.exists()) destFile.delete()
                    val success = tmp.renameTo(destFile)
                    if (!success) {
                        tmp.copyTo(destFile, overwrite = true)
                        tmp.delete()
                    }
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
