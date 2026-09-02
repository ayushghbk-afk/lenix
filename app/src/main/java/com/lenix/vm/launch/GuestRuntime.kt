package com.lenix.vm.launch

import android.util.Log
import com.lenix.nativebridge.NativeSetup
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmManager
import com.lenix.vm.pty.PtySession
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Owns live guest sessions for the manager: start (PRoot + optional desktop),

  * stop with SIGTERM→SIGKILL, and the PTY/pipe handle the terminal reads.
  *
  * Each running session also owns exactly one [PtySession] reader for as long as the
  * guest lives — not just while the terminal screen is composed. Two reasons: the
  * guest's stdout is a pipe, so an unread 64 KiB buffer blocks the shell, and the
  * transcript must survive navigating away from the terminal window and back.
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
    /** How long START waits for the guest shell to say it exec'd. */
    private val shellReadyTimeoutMs: Long = DEFAULT_SHELL_READY_TIMEOUT_MS,
    /** How long START waits for Xvnc to come up before handing the viewer the port. */
    private val desktopReadyTimeoutMs: Long = DEFAULT_DESKTOP_READY_TIMEOUT_MS,
) {
    private val sessions = ConcurrentHashMap<String, GuestSession>()

    /** One terminal reader per running guest session, closed when the guest stops. */
    private val terminals = ConcurrentHashMap<String, PtySession>()

    fun session(id: String): GuestSession? = sessions[id]

    /** The terminal attached to [id]'s guest session, or null when it is not running. */
    fun terminal(id: String): PtySession? = terminals[id]

    fun isEngineAvailable(): Boolean = engine.isAvailable(filesDir, abi, nativeLibDir)

    fun start(id: String, desktop: Boolean, geometry: String = ProotCommandBuilder.DEFAULT_GEOMETRY): GuestSession {
        // A restart has to go through the real stop path: killing the old process behind
        // the manager's back leaves the instance RUNNING, and RUNNING -> STARTING is an
        // illegal transition (VmStateMachine), so the restart would throw. It also has to
        // detach the old session's terminal.
        if (sessions.containsKey(id)) stop(id)
        val instance = manager.getInstance(id)
            ?: throw VmException(VmError.UNKNOWN, "Unknown instance '$id'.")
        val root = instanceRoot(id)
        val rootfs = File(root, "rootfs")

        if (!engine.isAvailable(filesDir, abi, nativeLibDir)) {
            manager.markError(id, VmError.NATIVE_ENGINE_FAILED)
            throw VmException(
                VmError.NATIVE_ENGINE_FAILED,
                "The PRoot engine for $abi is not on this device. Ship the engine payload " +
                    "(${NativeSetup.PROOT} + ${NativeSetup.PROOT_LOADER}) under " +
                    "app/src/main/jniLibs/$abi/ and rebuild.",
            )
        }

        // Diagnostic: log rootfs contents for debugging shell startup issues
        Log.d("Lenix", "Starting guest $id: rootfs=${rootfs.absolutePath}, desktop=$desktop")
        Log.d("Lenix", "Rootfs exists: ${rootfs.isDirectory}")
        val shellCandidates = listOf("bin/sh", "bin/bash", "usr/bin/sh", "usr/bin/bash")
        for (candidate in shellCandidates) {
            val f = File(rootfs, candidate)
            val exists = f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())
            Log.d("Lenix", "  $candidate: ${if (exists) "EXISTS" else "MISSING"}")
        }

        // A desktop needs more than a RootFS: the bundled Debian image is a base system
        // with no X server, no VNC server and no window manager. Check for them here,
        // while we can still explain the fix, instead of spawning a guest that dies with
        // "Xvnc: not found" and leaving the Desktop screen guessing.
        if (desktop && !DesktopPackages.isDesktopInstalled(rootfs, ProotCommandBuilder.DEFAULT_DESKTOP)) {
            val reason = DesktopPackages.missingMessage(rootfs, ProotCommandBuilder.DEFAULT_DESKTOP)
            Log.w("Lenix", "Desktop start refused for $id: $reason")
            throw VmException(VmError.DESKTOP_NOT_INSTALLED, reason)
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
                    "directory (app/src/main/jniLibs/$abi/) — Android 10+ refuses to exec " +
                    "engines from filesDir (docs/DECISIONS.md ADR-021).",
                e,
            )
        }
        sessions[id] = session

        // Both modes wait for the guest to say it got somewhere before the instance is
        // called RUNNING: a shell that could not exec bash, or a desktop whose VNC server
        // is missing, must fail as an instruction — not as a RUNNING instance whose
        // process is already gone.
        val startup = if (desktop) {
            awaitStartup(
                session = session,
                ready = ProotCommandBuilder.MARKER_DESKTOP_READY,
                failures = listOf(
                    ProotCommandBuilder.MARKER_DESKTOP_MISSING,
                    ProotCommandBuilder.MARKER_XVNC_FAILED,
                ),
                timeoutMs = desktopReadyTimeoutMs,
            )
        } else {
            awaitStartup(
                session = session,
                ready = ProotCommandBuilder.MARKER_READY,
                failures = emptyList(),
                timeoutMs = shellReadyTimeoutMs,
            )
        }

        if (desktop) {
            val failure = startup.failure
            if (failure == ProotCommandBuilder.MARKER_DESKTOP_MISSING) {
                sessions.remove(id)
                session.stop()
                manager.markError(id, VmError.DESKTOP_NOT_INSTALLED)
                throw VmException(
                    VmError.DESKTOP_NOT_INSTALLED,
                    DesktopPackages.missingMessage(rootfs, ProotCommandBuilder.DEFAULT_DESKTOP) +
                        startup.detail(),
                )
            }
            if (failure == ProotCommandBuilder.MARKER_XVNC_FAILED || !session.isAlive()) {
                sessions.remove(id)
                session.stop()
                manager.markError(id, VmError.VNC_CONNECTION_FAILED)
                throw VmException(
                    VmError.VNC_CONNECTION_FAILED,
                    "The VNC server exited while starting the desktop." + startup.detail(),
                )
            }
        } else if (!startup.ready) {
            Log.e("Lenix", "Shell did not start properly for instance $id")
            sessions.remove(id)
            session.stop()
            manager.markError(id, VmError.ROOTFS_EXTRACTION_FAILED)
            throw VmException(
                VmError.ROOTFS_EXTRACTION_FAILED,
                "The Linux shell did not start. Check that the RootFS is complete " +
                    "and contains /bin/sh or /bin/bash. Try reinstalling the RootFS." +
                    startup.detail(),
            )
        }

        // Attach the reader before handing the session out: from here on the guest's
        // stdout always has a consumer, in shell mode and in desktop mode alike. The
        // bytes the startup wait already consumed are seeded into the transcript so the
        // window is not missing the guest's first words.
        terminals[id] = PtySession(session).prime(startup.output).start()
        manager.markRunning(id, session.toVmProcess(id))
        return session
    }

    /** What [awaitStartup] saw: the marker (if any) and everything it read on the way. */
    private data class Startup(
        val ready: Boolean,
        val failure: String?,
        val output: String,
    ) {
        /** The guest's own words, appended to a host message when it printed any. */
        fun detail(): String {
            val text = output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(ProotCommandBuilder.MARKER_PREFIX) }
                .joinToString("\n")
            return if (text.isBlank()) "" else "\n\nGuest said:\n$text"
        }
    }

    /**
     * Reads the guest's first output until [ready] or one of [failures] shows up.
     *
     * Returns early when the guest dies, and gives a failing guest a short grace period
     * so the log it prints after the marker (e.g. the Xvnc error) is part of the message
     * the user sees. Every byte read here is returned in [Startup.output] and handed to
     * the terminal, so nothing the guest said is lost.
     */
    private fun awaitStartup(
        session: GuestSession,
        ready: String,
        failures: List<String>,
        timeoutMs: Long,
    ): Startup {
        val deadline = System.currentTimeMillis() + timeoutMs
        val readBuf = ByteArray(4096)
        val seen = StringBuilder()
        var failure: String? = null
        var graceUntil = Long.MAX_VALUE

        while (System.currentTimeMillis() < minOf(deadline, graceUntil)) {
            val alive = session.isAlive()
            try {
                val available = session.stdout.available()
                if (available > 0) {
                    val read = session.stdout.read(readBuf, 0, Math.min(available, readBuf.size))
                    if (read > 0) {
                        seen.append(String(readBuf, 0, read))
                        val text = seen.toString()
                        if (failure == null) {
                            failure = failures.firstOrNull { text.contains(it) }
                            // Collect the log lines the guest prints after the marker.
                            if (failure != null) graceUntil = System.currentTimeMillis() + GRACE_MS
                        }
                        if (failure == null && text.contains(ready)) {
                            return Startup(ready = true, failure = null, output = text)
                        }
                    }
                    continue
                }
            } catch (e: IOException) {
                Log.w("Lenix", "Error reading guest stdout: ${e.message}")
                break
            } catch (e: Exception) {
                Log.w("Lenix", "Error waiting for guest startup: ${e.message}")
                break
            }
            if (!alive) {
                Log.w("Lenix", "Guest process died while waiting for startup")
                break
            }
            Thread.sleep(POLL_MS)
        }
        return Startup(ready = false, failure = failure, output = seen.toString())
    }

    fun stop(id: String) {
        manager.stop(id)
        val session = sessions.remove(id)
        // Detach the window first so the shutdown output cannot race a "shell exited"
        // notice into a session the user stopped on purpose.
        closeTerminal(id)
        session?.stop()
        manager.markStopped(id)
    }

    private fun closeTerminal(id: String) {
        terminals.remove(id)?.close()
    }

    fun instanceRoot(id: String): File = File(filesDir, "instances/$id")

    private fun ensureResolv(file: File) {
        if (file.isFile) return
        file.parentFile?.mkdirs()
        file.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    companion object {
        const val DEFAULT_SHELL_READY_TIMEOUT_MS = 5_000L

        /** Xvnc needs a second or two on a phone; the script prints its marker earlier. */
        const val DEFAULT_DESKTOP_READY_TIMEOUT_MS = 12_000L

        private const val POLL_MS = 50L
        private const val GRACE_MS = 400L
    }
}
