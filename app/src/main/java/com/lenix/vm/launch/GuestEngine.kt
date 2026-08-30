package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmProcess
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

enum class GuestMode { SHELL, DESKTOP }

data class LaunchRequest(
    val instanceId: String,
    val filesDir: File,
    val rootfs: File,
    val home: File,
    val shared: File,
    val resolv: File,
    val mode: GuestMode,
    val vncPort: Int? = null,
    val vncPassword: String? = null,
    val geometry: String = ProotCommandBuilder.DEFAULT_GEOMETRY,
    val desktop: String = ProotCommandBuilder.DEFAULT_DESKTOP,
    val abi: String = NativeSetup.DEFAULT_ABI,
)

interface GuestSession {
    val pid: Long
    val stdin: OutputStream
    val stdout: InputStream
    val vncPort: Int?
    fun isAlive(): Boolean
    fun stop(graceMs: Long = 10_000)
}

interface GuestEngine {
    fun isAvailable(filesDir: File, abi: String = NativeSetup.DEFAULT_ABI): Boolean
    fun launch(request: LaunchRequest): GuestSession
}

/**
 * Real PRoot engine: copies/uses `filesDir/native/<abi>/proot` and execs it.
 */
class ProotGuestEngine : GuestEngine {

    override fun isAvailable(filesDir: File, abi: String): Boolean =
        NativeSetup.hasProot(filesDir, abi)

    override fun launch(request: LaunchRequest): GuestSession {
        val nativeDir = NativeSetup.nativeDir(request.filesDir, request.abi)
        val proot = File(nativeDir, NativeSetup.PROOT)
        if (!proot.isFile || !proot.canExecute()) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "PRoot is not installed at ${proot.absolutePath}. Unpack the native pack for ${request.abi}.",
            )
        }
        if (!request.rootfs.isDirectory) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "RootFS is missing at ${request.rootfs.absolutePath}.",
            )
        }
        request.home.mkdirs()
        request.shared.mkdirs()
        val tini = File(nativeDir, NativeSetup.TINI)
        val argv = when (request.mode) {
            GuestMode.SHELL -> ProotCommandBuilder.shell(
                proot = proot,
                rootfs = request.rootfs,
                home = request.home,
                resolv = request.resolv,
                shared = request.shared,
            )
            GuestMode.DESKTOP -> ProotCommandBuilder.desktop(
                proot = proot,
                rootfs = request.rootfs,
                home = request.home,
                resolv = request.resolv,
                shared = request.shared,
                vncPort = request.vncPort ?: error("desktop launch needs a VNC port"),
                geometry = request.geometry,
                desktop = request.desktop,
                tini = tini.takeIf { it.isFile },
            )
        }
        val process = ProcessBuilder(argv)
            .directory(request.rootfs)
            .redirectErrorStream(true)
            .start()
        return ProcessGuestSession(
            process = process,
            vncPort = request.vncPort,
        )
    }
}

class ProcessGuestSession(
    private val process: Process,
    override val vncPort: Int?,
) : GuestSession {
    override val pid: Long = pidOf(process)
    override val stdin: OutputStream = process.outputStream
    override val stdout: InputStream = process.inputStream

    override fun isAlive(): Boolean = process.isAlive

    override fun stop(graceMs: Long) {
        process.destroy()
        if (!process.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** [Process.pid] is API 31; v0.1 minSdk is 29, so call it only if present. */
        fun pidOf(process: Process): Long = try {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as? Long) ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }
}

fun GuestSession.toVmProcess(instanceId: String): VmProcess = VmProcess(
    instanceId = instanceId,
    pid = pid,
    vncPort = vncPort,
)
