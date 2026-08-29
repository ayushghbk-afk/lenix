package com.lenix.pocketvm.data

/**
 * Represents a Linux VM instance
 */
data class VmInstance(
    val id: String,
    val name: String,
    val distro: String,
    val version: String,
    val architecture: String = "arm64",
    val status: VmStatus = VmStatus.NOT_INSTALLED,
    val storagePath: String? = null,
    val memoryMB: Int = 512,
    val createdAt: Long = System.currentTimeMillis()
)
