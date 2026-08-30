package com.lenix.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lenix.data.JsonInstanceStore
import com.lenix.data.SelectionStore
import com.lenix.installer.RootfsCatalog
import com.lenix.vm.DistroSpec
import com.lenix.vm.VmInstance
import com.lenix.vm.VmManager
import com.lenix.vm.VmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeUiState(
    val instances: Map<String, VmInstance> = emptyMap(),
    val selectedInstance: VmInstance = VmInstance.DEFAULT,
    val installProgress: InstallProgress = InstallProgress.Inactive,
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
 * Owns the persisted instance manager (Phase 2): instances survive app restarts, the
 * selected instance is remembered, and all manager disk work runs on a single-threaded
 * IO dispatcher so mutations are serialized and never touch the main thread.
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
) : AndroidViewModel(application) {

    private val vmDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var selectedId: String =
        selectionStore.load()?.let { saved -> vmManager.getInstance(saved)?.id }
            ?: vmManager.selectedInstance().id

    private val mutableHomeState = MutableStateFlow(
        HomeUiState(
            instances = vmManager.instances.value,
            selectedInstance = vmManager.getInstance(selectedId) ?: vmManager.selectedInstance(),
        ),
    )

    val uiState: StateFlow<HomeUiState> = mutableHomeState.asStateFlow()

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
    }

    /**
     * Phase 1 demo action. Replace with real downloader / verifier / extractor work
     * once the UI is building from a clean pipeline.
     */
    fun install() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) {
            message("Create an instance first.")
            return
        }
        viewModelScope.launch(vmDispatcher) {
            vmManager.beginInstall(id)
            updateProgress(VmState.DOWNLOADING, 0.2f, "Preparing Debian arm64 RootFS …")

            delay(400)
            vmManager.markVerifying(id)
            updateProgress(VmState.VERIFYING, 0.4f, "Verifying RootFS checksum (demo) …")

            delay(400)
            vmManager.markExtracting(id)
            updateProgress(VmState.EXTRACTING, 0.8f, "Extracting RootFS (demo) …")

            delay(400)
            vmManager.markInstalling(id)
            updateProgress(VmState.INSTALLING, 0.95f, "Writing instance config (demo) …")

            delay(400)
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

    fun start() {
        val id = mutableHomeState.value.selectedInstance.id
        if (vmManager.getInstance(id) == null) return
        viewModelScope.launch(vmDispatcher) {
            vmManager.start(id)
            delay(1200)
            vmManager.markRunning(id)
            message("Linux environment is running (demo).")
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

    /** Disk usage for one instance, for the instance manager list. */
    suspend fun diskUsageBytes(id: String): Long = withContext(vmDispatcher) {
        vmManager.diskUsageBytes(id)
    }

    fun dismissMessage() {
        mutableHomeState.update { state -> state.copy(message = null) }
    }

    private fun updateProgress(state: VmState, fraction: Float, text: String) {
        mutableHomeState.update { current ->
            current.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = state,
                    fraction = fraction,
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
    }
}
