package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns live guest sessions for the manager: start (PRoot + optional desktop),
 * stop with SIGTERM→SIGKILL, and the PTY/pipe handle the terminal reads.
 *
 * @param nativeLibDir signed APK native payload dir (`ApplicationInfo.nativeLibraryDir`)
 *   — the only place Android 10+ allows `execve` of the engine (ADR-021).
 */
class GuestRuntime(
    private val filesDir: File,
    private val manager: VmManager,
    private val engine: GuestEngine = ProotGuestEngine(),
    private val nativeLibDir: File? = null,
    private val abi: String = NativeSetup.DEFAULT_ABI,
    private val portAllocator: () -> Int = { VncPortAllocator.allocate() },
    private val passwordFactory: () -> String = { VncPassword.generate() },
) {
    private val sessions = ConcurrentHashMap<String, GuestSession>()

    fun session(id: String): GuestSession? = sessions[id]

    fun isEngineAvailable(): Boolean = engine.isAvailable(filesDir, abi, nativeLibDir)

    fun start(id: String, desktop: Boolean, geometry: String = ProotCommandBuilder.DEFAULT_GEOMETRY): GuestSession {
        sessions[id]?.stop(1_000)
        sessions.remove(id)
        val instance = manager.getInstance(id)
            ?: throw VmException(VmError.UNKNOWN, "Unknown instance '$id'.")
        val root = instanceRoot(id)
        val rootfs = File(root, "rootfs")
        if (!engine.isAvailable(filesDir, abi, nativeLibDir)) {
            manager.markError(id, VmError.NATIVE_ENGINE_FAILED)
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "The PRoot engine for $abi is not on this device. Ship the engine payload " +
                    "(proot + loader) under app/src/main/resources/lib/$abi/ and rebuild.",
            )
        }
        val vncPort = if (desktop) portAllocator() else null
        val password = if (desktop) passwordFactory() else null
        if (password != null) {
            VncPassword.write(File(File(root, "vnc"), "password"), password)
            File(File(root, "vnc"), "port").writeText(vncPort.toString())
        }
        ensureResolv(File(File(root, "etc"), "resolv.conf"))
        manager.start(id)
        val session = try {
            engine.launch(
                LaunchRequest(
                    instanceId = id,
                    filesDir = filesDir,
                    rootfs = rootfs,
                    home = File(root, "home"),
                    shared = File(filesDir, "shared"),
                    resolv = File(File(root, "etc"), "resolv.conf"),
                    mode = if (desktop) GuestMode.DESKTOP else GuestMode.SHELL,
                    vncPort = vncPort,
                    vncPassword = password,
                    geometry = geometry,
                    desktop = ProotCommandBuilder.DEFAULT_DESKTOP,
                    abi = abi,
                    nativeLibDir = nativeLibDir,
                ),
            )
        } catch (e: VmException) {
            manager.markError(id, e.error)
            throw e
        } catch (e: Exception) {
            // E.g. ProcessBuilder.start() throwing "error=13, Permission denied": the
            // engine was validated but the kernel refused. Never surface a raw
            // IOException — tell the user where the engine must live.
            manager.markError(id, VmError.NATIVE_ENGINE_FAILED)
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "Could not start the PRoot engine (${e.message ?: e.javaClass.simpleName}). " +
                    "The $abi engine payload must sit in the signed APK's native library " +
                    "directory (app/src/main/resources/lib/$abi/) — Android 10+ refuses to exec " +
                    "engines from filesDir (docs/DECISIONS.md ADR-021).",
                e,
            )
        }
        sessions[id] = session
        manager.markRunning(id, session.toVmProcess(id))
        return session
    }

    fun stop(id: String) {
        manager.stop(id)
        sessions.remove(id)?.stop()
        manager.markStopped(id)
    }

    fun instanceRoot(id: String): File = File(filesDir, "instances/$id")

    private fun ensureResolv(file: File) {
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }
}
