package com.lenix.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory source of truth for the VM manager.
 *
 * This is intentionally a simple reactive store for Phase 1. A Room-backed
 * [VmInstanceRepository] will replace the map when persistence is added; the
 * [StateFlow] contract stays the same so the UI does not have to change.
 */
class VmManager(
    private val stateMachine: VmStateMachine = VmStateMachine(),
) {

    private val mutableInstances = MutableStateFlow<Map<String, VmInstance>>(
        mapOf(VmInstance.DEFAULT.id to VmInstance.DEFAULT),
    )

    val instances: StateFlow<Map<String, VmInstance>> = mutableInstances.asStateFlow()

    private val mutableRunningProcesses = MutableStateFlow<Map<String, VmProcess>>(emptyMap())

    /** Handles for processes that are currently running. */
    val runningProcesses: StateFlow<Map<String, VmProcess>> =
        mutableRunningProcesses.asStateFlow()

    private val activeProcesses = ConcurrentHashMap<String, VmProcess>()

    fun getInstance(id: String): VmInstance? = mutableInstances.value[id]

    fun selectedInstance(): VmInstance = instances.value
        .values
        .minByOrNull { it.updatedAt }
        ?: VmInstance.DEFAULT

    fun setState(id: String, to: VmState, error: VmError? = null): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = when (to) {
            VmState.ERROR -> current.copy(
                state = VmState.ERROR,
                lastError = error ?: current.lastError ?: VmError.UNKNOWN,
                updatedAt = System.currentTimeMillis(),
            )
            else -> stateMachine.apply(current, to, error)
        }
        mutableInstances.update { it + (id to updated) }
        return updated
    }

    fun beginInstall(id: String) = setState(id, VmState.DOWNLOADING)

    fun markVerifying(id: String) = setState(id, VmState.VERIFYING)

    fun markExtracting(id: String) = setState(id, VmState.EXTRACTING)

    fun markInstalling(id: String) = setState(id, VmState.INSTALLING)

    fun markReady(id: String, storagePath: String? = null): VmInstance? {
        val updated = setState(id, VmState.READY)?.copy(storagePath = storagePath) ?: return null
        val final = updated.copy(updatedAt = System.currentTimeMillis())
        mutableInstances.update { it + (id to final) }
        return final
    }

    fun markError(id: String, error: VmError) = setState(id, VmState.ERROR, error)

    fun start(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = stateMachine.apply(current, VmState.STARTING)
        mutableInstances.update { it + (id to updated) }
        return updated
    }

    fun markRunning(id: String): VmInstance? {
        val updated = setState(id, VmState.RUNNING) ?: return null
        val process = VmProcess(instanceId = id)
        activeProcesses[id] = process
        mutableRunningProcesses.update { it + (id to process) }
        return updated
    }

    fun stop(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = when (current.state) {
            VmState.RUNNING, VmState.STARTING -> stateMachine.apply(current, VmState.STOPPING)
            else -> current
        }
        mutableInstances.update { it + (id to updated) }
        return updated
    }

    fun markStopped(id: String): VmInstance? {
        val updated = setState(id, VmState.READY) ?: return null
        activeProcesses.remove(id)
        mutableRunningProcesses.update { it - id }
        return updated
    }

    fun reset(id: String): VmInstance? {
        val current = getInstance(id) ?: return null
        val updated = stateMachine.apply(current, VmState.NOT_INSTALLED)
        mutableInstances.update { it + (id to updated) }
        return updated
    }

    fun processFor(id: String): VmProcess? = activeProcesses[id]
}
