package com.lenix.pocketvm.data

/**
 * Represents the status of a VM instance
 */
enum class VmStatus {
    NOT_INSTALLED,
    INSTALLING,
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}
