package com.lenix.vm

/**
 * A single Linux environment managed by Lenix.
 *
 * v0.1 deliberately supports ARM64 Debian only. Other distros / ABIs are parsed by
 * the catalog and displayed as "coming soon" rather than being enabled.
 */
data class VmInstance(
    val id: String,
    val name: String,
    val distro: String,
    val codename: String,
    val version: String,
    val architecture: String = SUPPORTED_ABI,
    val state: VmState = VmState.NOT_INSTALLED,
    val lastError: VmError? = null,
    val storagePath: String? = null,
    val memoryMB: Int = DEFAULT_MEMORY_MB,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SUPPORTED_ABI = "arm64-v8a"
        const val DEFAULT_MEMORY_MB = 512

        val DEFAULT = VmInstance(
            id = "default",
            name = "Lenix Linux",
            distro = "Debian",
            codename = "bookworm",
            version = "12",
        )
    }
}
