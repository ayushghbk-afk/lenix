package com.lenix.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lenix.vm.VmInstance
import com.lenix.vm.VmManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val instances: Map<String, VmInstance> = emptyMap(),
    val selectedInstance: VmInstance = VmInstance.DEFAULT,
    val installProgress: InstallProgress = InstallProgress.Inactive,
    val message: String? = null,
) {
    data class InstallProgress(
        val state: com.lenix.vm.VmState? = null,
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
 */
class HomeViewModel(
    val vmManager: VmManager = VmManager(),
) : ViewModel() {

    private val mutableHomeState = MutableStateFlow(
        HomeUiState(
            instances = vmManager.instances.value,
            selectedInstance = vmManager.selectedInstance(),
        ),
    )

    val uiState: StateFlow<HomeUiState> = mutableHomeState.asStateFlow()

    init {
        viewModelScope.launch {
            vmManager.instances.collect { instances ->
                val selected = instances.values.minByOrNull { it.updatedAt } ?: VmInstance.DEFAULT
                mutableHomeState.update { current ->
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
        vmManager.beginInstall(id)
        mutableHomeState.update { state ->
            state.copy(
                installProgress = HomeUiState.InstallProgress(
                    state = com.lenix.vm.VmState.DOWNLOADING,
                    fraction = 0.2f,
                    message = "Preparing Debian arm64 RootFS …",
                ),
                message = null,
            )
        }
        viewModelScope.launch {
            delay(400)
            vmManager.markVerifying(id)
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = state.installProgress.copy(
                        state = com.lenix.vm.VmState.VERIFYING,
                        fraction = 0.4f,
                        message = "Verifying RootFS checksum (demo) …",
                    ),
                )
            }

            delay(400)
            vmManager.markExtracting(id)
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = state.installProgress.copy(
                        state = com.lenix.vm.VmState.EXTRACTING,
                        fraction = 0.8f,
                        message = "Extracting RootFS (demo) …",
                    ),
                )
            }

            delay(400)
            vmManager.markInstalling(id)
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = state.installProgress.copy(
                        state = com.lenix.vm.VmState.INSTALLING,
                        fraction = 0.95f,
                        message = "Writing instance config (demo) …",
                    ),
                )
            }

            delay(400)
            vmManager.markReady(id)
            mutableHomeState.update { state ->
                state.copy(
                    installProgress = HomeUiState.InstallProgress(
                        state = com.lenix.vm.VmState.READY,
                        fraction = 1f,
                        message = "Ready",
                    ),
                    selectedInstance = vmManager.getInstance(id) ?: VmInstance.DEFAULT,
                    message = null,
                )
            }
        }
    }

    fun start() {
        val id = mutableHomeState.value.selectedInstance.id
        vmManager.start(id)
        viewModelScope.launch {
            delay(1200)
            vmManager.markRunning(id)
            mutableHomeState.update { state ->
                state.copy(message = "Linux environment is running (demo).")
            }
        }
    }

    fun stop() {
        val id = mutableHomeState.value.selectedInstance.id
        vmManager.stop(id)
        viewModelScope.launch {
            delay(600)
            vmManager.markStopped(id)
            mutableHomeState.update { state -> state.copy(message = null) }
        }
    }

    fun reset() {
        val id = mutableHomeState.value.selectedInstance.id
        vmManager.reset(id)
        mutableHomeState.update { state ->
            state.copy(
                selectedInstance = VmInstance.DEFAULT,
                installProgress = HomeUiState.InstallProgress.Inactive,
                message = null,
            )
        }
    }
}
