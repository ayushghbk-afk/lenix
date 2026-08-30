package com.lenix.vm.launch

import com.lenix.nativebridge.EngineInstaller
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
    /**
     * `ApplicationInfo.nativeLibraryDir` — the signed APK payload directory that
     * Android 10+ actually allows `execve` from (ADR-021).
     */
    val nativeLibDir: File? = null,
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
    /**
     * @param nativeLibDir signed APK payload dir (already ABI-specific), or null.
     */
    fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?): Boolean
    fun launch(request: LaunchRequest): GuestSession
}

/**
 * Real PRoot engine.
 *
 * The engine is exec'd from the APK payload (`ApplicationInfo.nativeLibraryDir`):
 * Android 10+'s SELinux W^X policy denies direct exec from `filesDir`, and a legacy
 * `filesDir/native/<abi>/proot` install is rejected on-device (its static loader
 * cannot be relayed through `/system/bin/linker64`). `PROOT_LOADER` is pinned to the
 * payload's static loader so PRoot never extracts+execs one into app temp (also
 * denied). [AndroidExecBridge] remains as a safety net for bionic host helpers that
 * must run from app data.
 */
class ProotGuestEngine : GuestEngine {

    override fun isAvailable(filesDir: File, abi: String, nativeLibDir: File?): Boolean =
        EngineInstaller.ensureEngine(filesDir, abi, nativeLibDir).isReady

    override fun launch(request: LaunchRequest): GuestSession {
        val status = EngineInstaller.ensureEngine(
            filesDir = request.filesDir,
            abi = request.abi,
            nativeLibDir = request.nativeLibDir,
        )
        if (!status.isReady) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                status.reason
                    ?: "PRoot engine for ${request.abi} is not ready. Add the complete engine " +
                        "payload under app/src/main/jniLibs/${request.abi}/ and rebuild.",
            )
        }
        val proot = status.proot
        if (proot == null) {
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                status.reason
                    ?: "PRoot engine for ${request.abi} is not installed. Add the engine " +
                        "payload under app/src/main/jniLibs/${request.abi}/ and rebuild.",
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

        val payloadDir = request.nativeLibDir?.takeIf { it.isDirectory }
        val legacyDir = NativeSetup.nativeDir(request.filesDir, request.abi)
        // Dependencies (e.g. libtalloc) sit next to the engine binary.
        val libDir = (payloadDir ?: legacyDir).takeIf { it.isDirectory }
        // tini is optional; accept the canonical `libtini.so` and the historic `tini`
        // that debug payloads / legacy filesDir installs still use.
        val tini = payloadDir?.let { NativeSetup.findPayloadFile(it, NativeSetup.TINI_NAMES) }
            ?: NativeSetup.findPayloadFile(legacyDir, NativeSetup.TINI_NAMES)
            ?: File(legacyDir, NativeSetup.TINI)

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
        val command = AndroidExecBridge.resolve(argv, request.abi)
        val tmpDir = File(request.filesDir, "proot-tmp").apply { mkdirs() }

        val builder = ProcessBuilder(command)
            .directory(request.rootfs)
            .redirectErrorStream(true)
        ProotCommandBuilder.applyEngineEnvironment(
            environment = builder.environment(),
            loader = status.loader,
            tmpDir = tmpDir,
            libDir = libDir,
        )
        val process = builder.start()
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
