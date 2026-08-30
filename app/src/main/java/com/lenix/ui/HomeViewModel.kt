package com.lenix.ui

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lenix.data.JsonInstanceStore
import com.lenix.data.JsonInstallStateStore
import com.lenix.data.JsonSettingsStore
import com.lenix.data.LenixSettings
import com.lenix.data.SelectionStore
import com.lenix.installer.RootfsCatalog
import com.lenix.installer.RootfsInstaller
import com.lenix.installer.RootfsManifest
import com.lenix.installer.RootfsManifestParser
import com.lenix.vm.DistroSpec
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmInstance
import com.lenix.vm.VmManager
import com.lenix.vm.VmState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class HomeUiState(
    val instances: Map<String, VmInstance> = emptyMap(),
    val selectedInstance: VmInstance = VmInstance.DEFAULT,
    val installProgress: InstallProgress = InstallProgress.Inactive,
    val settings: LenixSettings = LenixSettings(),
    val message: String? = null,
) {
    data class InstallProgress(
        val state: VmState? = null,
        val fraction: Float = 0f,
        val message: String = "",
    ) {
        companion object {
            val Inactive = InstallProgress()
        }
    }
}

/**
 * Keeps the monitor UI reactive without turning [MainActivity] into a giant monster.
 *
 * Owns the persisted instance manager (Phase 2) and, since Phase 3, drives the
 * real RootFS install pipeline: the bundled manifest is downloaded layer-by-layer
 * through the resumable downloader, every state transition is persisted, and an
 * interrupted install resumes at the byte it stopped at. App settings are loaded
 * from / persisted to `filesDir/settings.json` here too — one owner, one file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    application: Application,
    val vmManager: VmManager = VmManager(
        store = JsonInstanceStore(File(application.filesDir, INSTANCE_DIR)),
    ),
    private val selectionStore: SelectionStore = SelectionStore(
        File(application.filesDir, SELECTION_FILE),
    ),
    private val settingsStore: JsonSettingsStore = JsonSettingsStore(
        File(application.filesDir, SETTINGS_FILE),
    ),
    private val installer: RootfsInstaller = RootfsInstaller(application.filesDir),
    private val manifestParser: RootfsManifestParser = RootfsManifestParser(),
) : AndroidViewModel(application) {

    private val vmDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var selectedId: String =
        selectionStore.load()?.let { saved -> vmManager.getInstance(saved)?.id }
            ?: vmManager.selectedInstance().id

    private val mutableHomeState = MutableStateFlow(
        HomeUiState(
            instances = vmManager.instances.value,
            selectedInstance = vmManager.getInstance(selectedId) ?: vmManager.selectedInstance(),
            settings = settingsStore.load(),
        ),
    )

    val uiState: StateFlow<HomeUiState> = mutableHomeState.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            vmManager.instances.collect { instances ->
                mutableHomeState.update { current ->
                    val selected = instances[selectedId]
                        ?: instances.values.minByOrNull { it.updatedAt }?.also { fallback ->
                            selectedId = fallback.id
                        }
                        ?: VmInstance.DEFAULT
                    current.copy(
                        instances = instances,
                        selectedInstance = selected,
                    )
                }
            }
        }
        seedInterruptedInstall()
    }

    /**
     * Installs (or resumes installing) the RootFS for the selected instance.
     *
     * Runs the real pipeline from Phase 3 on: manifest → resumable download →
     * verify → stage → commit. A retry after an interruption resumes from the
     * layer cache and any `.part` file instead of restarting from zero.
     */
    fun install() {
        val id = mutableHomeState.value.selectedInstance.id
        val instance = vmManager.getInstance(id)
        if (instance == null) {
            message("Create an instance first.")
            return
        }
        if (instance.state.isBusy) return
        if (instance.state == VmState.READY) {
            message("Already installed — reset the instance to reinstall.")
            return
        }

        val option = RootfsCatalog.options.firstOrNull {
            it.enabled && it.manifestAsset != null &&
                it.displayName.equals(instance.distro, ignoreCase = true)
        }
        val manifestJson = option?.manifestAsset?.let(::readAsset)
        if (manifestJson == null) {
            message("No RootFS is bundled for ${instance.distro} in this build.")
            return
        }

        val manifest = try {
            manifestParser.parse(manifestJson)
        } catch (e: Exception) {
            message("The bundled RootFS manifest is invalid: ${e.message}")
            return
        }

        if (mutableHomeState.value.settings.smartStorage) {
            val required = requiredBytesFor(manifest)
            val available = availableBytes()
            if (available in 0 until required) {
                message(
                    "Not enough free storage: need about ${formatBytes(required)}, " +
                        "only ${formatBytes(available)} available.",
                )
                return
            }
        }

        installJob?.cancel()
        installJob = viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.beginInstall(id)
                installer.install(id, manifestJson).collect { progress ->
                    onInstallProgress(id, progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VmException) {
                vmManager.markError(id, e.error)
                message(e.message ?: "Install failed.")
            } catch (e: Exception) {
                vmManager.markError(id, VmError.UNKNOWN)
                message(e.message ?: "Install failed.")
            }
        }
    }

    /** Stops an in-flight install; verified layers stay cached for the next attempt. */
    fun cancelInstall() {
        val job = installJob ?: return
        installJob = null
        viewModelScope.launch(vmDispatcher) {
            job.cancelAndJoin()
            val id = mutableHomeState.value.selectedInstance.id
            installer.discardPartials()
            JsonInstallStateStore.forInstance(getApplication<Application>().filesDir, id).clear()
            val instance = vmManager.getInstance(id)
            if (instance != null && instance.state.isBusy) {
                vmManager.markError(id, VmError.INSTALL_INTERRUPTED)
            }
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = HomeUiState.InstallProgress.Inactive,
                    message = "Install cancelled. Completed layers stay cached for the next attempt.",
                )
            }
        }
    }

    fun start() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            vmManager.start(id)
            delay(1200)
            vmManager.markRunning(id)
            message("Linux environment is running (demo; the PRoot engine lands in Phase 6).")
        }
    }

    fun stop() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            vmManager.stop(id)
            delay(600)
            vmManager.markStopped(id)
            mutableHomeState.update { state -> state.copy(message = null) }
        }
    }

    fun reset() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            vmManager.reset(id)
            JsonInstallStateStore.forInstance(getApplication<Application>().filesDir, id).clear()
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = HomeUiState.InstallProgress.Inactive,
                    message = null,
                )
            }
        }
    }

    /** Makes [id] the instance shown and controlled on the Home screen. */
    fun selectInstance(id: String) {
        val instance = vmManager.getInstance(id) ?: return
        selectedId = id
        viewModelScope.launch(vmDispatcher) { selectionStore.save(id) }
        mutableHomeState.update { state -> state.copy(selectedInstance = instance) }
    }

    /** Creates a new instance from a catalog entry and selects it. */
    fun createInstance(name: String, distroId: String) {
        viewModelScope.launch(vmDispatcher) {
            val option = RootfsCatalog.options.firstOrNull { it.id == distroId }
            if (option == null || !option.enabled) {
                message("That distro is not available in v0.1.")
                return@launch
            }
            try {
                val created = vmManager.createInstance(
                    name = name,
                    distro = DistroSpec(
                        distro = option.displayName,
                        codename = option.codename,
                        version = option.version,
                        architecture = option.architecture,
                    ),
                )
                selectedId = created.id
                selectionStore.save(created.id)
                mutableHomeState.update { state ->
                    state.copy(selectedInstance = created, message = "Instance '${created.name}' created.")
                }
            } catch (e: IllegalArgumentException) {
                message(e.message ?: "Invalid instance.")
            } catch (e: IllegalStateException) {
                message(e.message ?: "Instance limit reached.")
            }
        }
    }

    fun renameInstance(id: String, newName: String) {
        viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.renameInstance(id, newName)
                message("Instance renamed.")
            } catch (e: IllegalArgumentException) {
                message(e.message ?: "Invalid name.")
            }
        }
    }

    fun deleteInstance(id: String) {
        viewModelScope.launch(vmDispatcher) {
            try {
                vmManager.deleteInstance(id)
                if (selectedId == id) {
                    val next = vmManager.instances.value.values.minByOrNull { it.updatedAt }
                    if (next != null) {
                        selectedId = next.id
                        selectionStore.save(next.id)
                    } else {
                        selectionStore.clear()
                    }
                }
                message("Instance deleted.")
            } catch (e: IllegalStateException) {
                message(e.message ?: "Instance is busy.")
            }
        }
    }

    /**
     * Updates one setting and persists the whole set to `filesDir/settings.json`.
     * The UI state updates optimistically; the disk write is serialized on the
     * manager dispatcher.
     */
    fun updateSettings(transform: (LenixSettings) -> LenixSettings) {
        val updated = transform(mutableHomeState.value.settings)
        mutableHomeState.update { state -> state.copy(settings = updated) }
        viewModelScope.launch(vmDispatcher) { settingsStore.save(updated) }
    }

    /** Disk usage for one instance, for the instance manager list. */
    suspend fun diskUsageBytes(id: String): Long = withContext(vmDispatcher) {
        vmManager.diskUsageBytes(id)
    }

    fun dismissMessage() {
        mutableHomeState.update { state -> state.copy(message = null) }
    }

    /**
     * After a process death mid-install, the instance recovers as
     * ERROR/INSTALL_INTERRUPTED with a persisted checkpoint; surface that progress
     * so the Home screen can offer RESUME at the right percentage.
     */
    private fun seedInterruptedInstall() {
        val instance = vmManager.getInstance(selectedId) ?: return
        if (instance.state != VmState.ERROR || instance.lastError != VmError.INSTALL_INTERRUPTED) {
            return
        }
        val state = JsonInstallStateStore
            .forInstance(getApplication<Application>().filesDir, selectedId)
            .load() ?: return
        if (state.bytesTotal <= 0L) return
        val fraction = (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f)
        mutableHomeState.update { current ->
            current.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = VmState.DOWNLOADING,
                    fraction = fraction,
                    message = "Interrupted at ${(fraction * 100).toInt()}% — " +
                        "tap RESUME INSTALL to continue where it stopped.",
                ),
            )
        }
    }

    private fun onInstallProgress(id: String, progress: RootfsInstaller.Progress) {
        when (progress) {
            is RootfsInstaller.Progress.Download -> updateProgress(
                VmState.DOWNLOADING,
                fractionOf(progress.bytesDone, progress.bytesTotal) * DOWNLOAD_SHARE,
                "Downloading ${progress.layerId} — ${formatBytes(progress.bytesDone)} / " +
                    "${formatBytes(progress.bytesTotal)}",
            )

            is RootfsInstaller.Progress.Verifying -> {
                vmManager.markVerifying(id)
                updateProgress(VmState.VERIFYING, VERIFYING_FRACTION, "Verifying ${progress.layerId} …")
            }

            is RootfsInstaller.Progress.Extracting -> {
                vmManager.markExtracting(id)
                updateProgress(
                    VmState.EXTRACTING,
                    EXTRACTING_FRACTION,
                    "Staging RootFS (${progress.currentFile}) …",
                )
            }

            is RootfsInstaller.Progress.Committing -> {
                vmManager.markInstalling(id)
                updateProgress(VmState.INSTALLING, COMMITTING_FRACTION, progress.detail)
            }

            RootfsInstaller.Progress.Ready -> {
                vmManager.markReady(id)
                mutableHomeState.update { state ->
                    state.copy(
                        installProgress = HomeUiState.InstallProgress(
                            state = VmState.READY,
                            fraction = 1f,
                            message = "Ready",
                        ),
                        message = null,
                    )
                }
            }
        }
    }

    private fun fractionOf(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /**
     * Free space an install is expected to need: the compressed layers in the
     * shared cache plus extraction headroom (docs/ROOTFS_SYSTEM.md §4).
     */
    private fun requiredBytesFor(manifest: RootfsManifest): Long {
        val compressed = manifest.layers.sumOf { it.sizeBytes }
        val uncompressed = manifest.layers.sumOf { it.uncompressedBytes }
        return compressed + uncompressed * EXTRACTION_HEADROOM_NUMERATOR /
            EXTRACTION_HEADROOM_DENOMINATOR
    }

    /** Available bytes on the app's internal storage, or -1 when unknowable. */
    private fun availableBytes(): Long = try {
        StatFs(getApplication<Application>().filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        -1L
    }

    private fun readAsset(path: String): String? = try {
        getApplication<Application>().assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }

    private fun updateProgress(state: VmState, fraction: Float, text: String) {
        mutableHomeState.update { current ->
            current.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = state,
                    fraction = fraction.coerceIn(0f, 1f),
                    message = text,
                ),
                message = null,
            )
        }
    }

    private fun message(text: String) {
        mutableHomeState.update { state -> state.copy(message = text) }
    }

    companion object {
        const val INSTANCE_DIR = "instances"
        const val SELECTION_FILE = "selected_instance"
        const val SETTINGS_FILE = "settings.json"

        /** Downloading owns the first 85% of the progress bar; the rest is local work. */
        const val DOWNLOAD_SHARE = 0.85f
        const val VERIFYING_FRACTION = 0.9f
        const val EXTRACTING_FRACTION = 0.93f
        const val COMMITTING_FRACTION = 0.97f

        const val EXTRACTION_HEADROOM_NUMERATOR = 6L
        const val EXTRACTION_HEADROOM_DENOMINATOR = 5L

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
            bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
            else -> "$bytes B"
        }
    }
}
