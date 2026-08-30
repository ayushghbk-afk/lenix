package com.lenix.installer

import android.content.Context
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Fake, local-only installer for Phase 1.
 *
 * It validates the selected ABI, reserves an instance directory, runs the manifest
 * through the real [RootfsManifestParser], and reports the state the production
 * downloader will use. It does not download a RootFS yet.
 */
class RootfsInstaller(
    private val context: Context,
    private val parser: RootfsManifestParser = RootfsManifestParser(),
) {

    sealed class Progress {
        data class Download(val bytesDone: Long, val bytesTotal: Long) : Progress()
        data class Verifying(val bytesDone: Long, val bytesTotal: Long) : Progress()
        data class Extracting(val currentFile: String, val done: Float) : Progress()
        data object Ready : Progress()
    }

    /**
     * Stages a local fake archive into [instanceRoot] and reaches READY.
     */
    fun install(
        id: String,
        manifestJson: String,
        fakeArchive: File,
    ): Flow<Progress> = flow {
        val manifest = try {
            parseManifest(manifestJson)
        } catch (e: VmException) {
            throw e
        } catch (e: Exception) {
            throw VmException(VmError.DOWNLOAD_CORRUPTED, e.message, e)
        }

        RootfsCatalog.requireSupportedArchitecture(manifest.arch)

        val instanceDir = instanceDir(id)
        if (instanceDir.exists()) {
            instanceDir.deleteRecursively()
        }
        instanceDir.mkdirs()

        val stagingDir = File(instanceDir, ".tmp").apply { mkdirs() }

        emit(Progress.Download(0, fakeArchive.length()))
        copyFile(fakeArchive, File(stagingDir, "rootfs.tar.zst"))
        emit(Progress.Download(fakeArchive.length(), fakeArchive.length()))
        emit(Progress.Verifying(fakeArchive.length(), fakeArchive.length()))

        // In production this runs a streaming lazy tar extraction.
        File(stagingDir, "rootfs.tar.zst").copyTo(File(stagingDir, "rootfs"), overwrite = true)
        emit(Progress.Extracting("rootfs.tar.zst", 1f))

        commit(instanceDir, stagingDir, manifest)
        emit(Progress.Ready)
    }.flowOn(Dispatchers.IO)

    private fun parseManifest(json: String): RootfsManifest = parser.parse(json)

    private fun copyFile(source: File, target: File) {
        source.copyTo(target, overwrite = true)
    }

    private fun commit(instanceDir: File, stagingDir: File, manifest: RootfsManifest) {
        val commitDir = File(instanceDir, "rootfs")
        if (commitDir.exists()) {
            commitDir.deleteRecursively()
        }
        stagingDir.renameTo(commitDir)
        File(instanceDir, "config.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"id\": \"${manifest.id}\",")
                appendLine("  \"distro\": \"${manifest.distro}\",")
                appendLine("  \"codename\": \"${manifest.codename}\",")
                appendLine("  \"arch\": \"${manifest.arch}\",")
                appendLine("  \"version\": \"${manifest.version}\",")
                appendLine("  \"bootCommand\": \"${manifest.install.bootCommand}\"")
                appendLine("}")
            },
        )
    }

    private fun instanceDir(id: String): File {
        val base = File(context.filesDir, "instances")
        if (!base.exists()) base.mkdirs()
        val dir = File(base, id)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
