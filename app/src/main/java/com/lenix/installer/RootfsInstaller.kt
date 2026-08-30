package com.lenix.installer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lenix.data.InstallState
import com.lenix.data.JsonInstallStateStore
import com.lenix.data.download.LayerCache
import com.lenix.data.download.LayerSpec
import com.lenix.data.download.ResumableDownloader
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Provenance snapshot of the RootFS an instance was installed from
 * (`instances/<id>/rootfs.json`). This is *what came from the network*; the
 * instance record itself is owned by `data.JsonInstanceStore` as `config.json`.
 */
data class RootfsProvenance(
    val rootfsId: String,
    val distro: String,
    val codename: String,
    val arch: String,
    val version: String,
    val bootCommand: String,
)

/**
 * The RootFS install pipeline (docs/ROOTFS_SYSTEM.md §2), Phase 3 edition:
 *
 *  1. parse + validate the manifest, reject unsupported architectures;
 *  2. DOWNLOADING — fetch every layer through the resumable, content-addressed
 *     [LayerCache] (an interrupted install resumes at the exact byte it stopped);
 *  3. VERIFYING — re-hash every cached layer against the manifest;
 *  4. EXTRACTING — *staging stub*: layers are copied into `instances/<id>/.tmp/rootfs/`
 *     until real streaming extraction lands in Phase 5;
 *  5. COMMITTING — atomically rename the staged directory to `rootfs/` and write
 *     the `rootfs.json` provenance record.
 *
 * Progress checkpoints are persisted to `instances/<id>/state.json` at every phase
 * change and at least every [STATE_SAVE_INTERVAL_MS] during downloads, and the
 * checkpoint is cleared when the install commits.
 *
 * Takes a plain [filesDir] (not a `Context`) so the whole pipeline is JVM unit
 * testable; see ADR-014/015.
 */
class RootfsInstaller(
    private val filesDir: File,
    private val parser: RootfsManifestParser = RootfsManifestParser(),
    private val verifier: RootfsVerifier = RootfsVerifier(),
    private val downloader: ResumableDownloader = ResumableDownloader(
        cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
    ),
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed class Progress {
        /** Aggregate download progress across all layers of the manifest. */
        data class Download(val layerId: String, val bytesDone: Long, val bytesTotal: Long) :
            Progress()

        /** A cached layer is being re-hashed against the manifest. */
        data class Verifying(val layerId: String) : Progress()

        /** Layers are being staged into the instance (real extraction: Phase 5). */
        data class Extracting(val currentFile: String, val done: Float) : Progress()

        /** The staged rootfs is being committed as the instance's `rootfs/`. */
        data class Committing(val detail: String) : Progress()

        data object Ready : Progress()
    }

    fun install(id: String, manifestJson: String): Flow<Progress> = flow {
        val manifest = try {
            parser.parse(manifestJson)
        } catch (e: VmException) {
            throw e
        } catch (e: Exception) {
            throw VmException(VmError.DOWNLOAD_CORRUPTED, e.message, e)
        }

        RootfsCatalog.requireSupportedArchitecture(manifest.arch)

        val instanceDir = File(File(filesDir, JsonInstallStateStore.INSTANCE_DIR), id)
        if (!instanceDir.exists()) instanceDir.mkdirs()
        val stateStore = JsonInstallStateStore(File(instanceDir, JsonInstallStateStore.STATE_FILE))
        val stagingDir = File(instanceDir, STAGING_DIR)
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw VmException(VmError.ROOTFS_EXTRACTION_FAILED, "Could not create $stagingDir.")
        }

        val layers = manifest.layers
        val bytesTotal = layers.sumOf { it.sizeBytes }
        var bytesDone = 0L

        // [2] DOWNLOADING — resumable, content-addressed.
        layers.forEachIndexed { index, layer ->
            var lastStateSave = clock()
            stateStore.save(
                InstallState(
                    instanceId = id,
                    phase = PHASE_DOWNLOADING,
                    layerIndex = index,
                    layerCount = layers.size,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    updatedAt = lastStateSave,
                ),
            )
            val file = downloader.download(
                LayerSpec(url = layer.url, sizeBytes = layer.sizeBytes, sha256 = layer.sha256),
            ) { layerDone, _ ->
                emit(Progress.Download(layer.id, bytesDone + layerDone, bytesTotal))
                val now = clock()
                if (now - lastStateSave >= STATE_SAVE_INTERVAL_MS) {
                    lastStateSave = now
                    stateStore.save(
                        InstallState(
                            instanceId = id,
                            phase = PHASE_DOWNLOADING,
                            layerIndex = index,
                            layerCount = layers.size,
                            bytesDone = bytesDone + layerDone,
                            bytesTotal = bytesTotal,
                            updatedAt = now,
                        ),
                    )
                }
            }
            bytesDone += file.length()
        }

        // [3] VERIFYING — re-hash every cached layer against the signed manifest.
        stateStore.save(
            InstallState(
                instanceId = id,
                phase = PHASE_VERIFYING,
                layerCount = layers.size,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                updatedAt = clock(),
            ),
        )
        layers.forEach { layer ->
            emit(Progress.Verifying(layer.id))
            verifier.verifyFile(downloader.cache.cachedFile(layer.sha256), layer.sha256)
        }

        // [4] EXTRACTING — Phase 3 staging stub; Phase 5 replaces this with
        // streaming libarchive extraction into the guest filesystem.
        stateStore.save(
            InstallState(
                instanceId = id,
                phase = PHASE_EXTRACTING,
                layerCount = layers.size,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                updatedAt = clock(),
            ),
        )
        val stagedRootfs = File(stagingDir, ROOTFS_DIR)
        if (!stagedRootfs.mkdirs()) {
            throw VmException(VmError.ROOTFS_EXTRACTION_FAILED, "Could not create $stagedRootfs.")
        }
        layers.forEachIndexed { index, layer ->
            val staged = File(stagedRootfs, "${layer.id}.layer")
            emit(Progress.Extracting(staged.name, (index + 1f) / layers.size))
            try {
                downloader.cache.cachedFile(layer.sha256).copyTo(staged, overwrite = true)
            } catch (e: Exception) {
                throw VmException(VmError.ROOTFS_EXTRACTION_FAILED, e.message, e)
            }
        }

        // [5] COMMITTING — atomic rename; either a complete rootfs or nothing.
        stateStore.save(
            InstallState(
                instanceId = id,
                phase = PHASE_COMMITTING,
                layerCount = layers.size,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                updatedAt = clock(),
            ),
        )
        emit(Progress.Committing("Committing RootFS"))
        val rootfsDir = File(instanceDir, ROOTFS_DIR)
        rootfsDir.deleteRecursively()
        if (!stagedRootfs.renameTo(rootfsDir)) {
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "Could not commit the staged RootFS to $rootfsDir.",
            )
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
            File(instanceDir, PROVENANCE_FILE),
            RootfsProvenance(
                rootfsId = manifest.id,
                distro = manifest.distro,
                codename = manifest.codename,
                arch = manifest.arch,
                version = manifest.version,
                bootCommand = manifest.install.bootCommand,
            ),
        )
        stagingDir.deleteRecursively()
        stateStore.clear()

        emit(Progress.Ready)
    }.flowOn(Dispatchers.IO)

    /** Drops in-flight partial downloads (explicit user cancel). */
    fun discardPartials() = downloader.discardPartials()

    companion object {
        const val STAGING_DIR = ".tmp"
        const val ROOTFS_DIR = "rootfs"
        const val PROVENANCE_FILE = "rootfs.json"
        const val STATE_SAVE_INTERVAL_MS = 5_000L

        const val PHASE_DOWNLOADING = "DOWNLOADING"
        const val PHASE_VERIFYING = "VERIFYING"
        const val PHASE_EXTRACTING = "EXTRACTING"
        const val PHASE_COMMITTING = "COMMITTING"
    }
}
