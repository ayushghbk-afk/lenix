package com.lenix.vm.launch

import com.lenix.nativebridge.NativeSetup
import java.io.File

/** Builds the PRoot argv for a guest (ARCHITECTURE.md §7.2, ADR-001 / ADR-006).

  * GPL `proot` is executed as a separate process, never linked. `-0` only fakes
  * uid 0 inside the emulated view. Bind mounts are the documented host paths —
  * never `/sdcard`.
  */
object ProotCommandBuilder {

    const val DEFAULT_GEOMETRY = "1280x720"
    const val DEFAULT_DESKTOP = "openbox"
    const val DEFAULT_VNC_DISPLAY = 1
    const val DISPLAY_BASE_PORT = 5900

    /**
     * Out-of-band markers the guest scripts print so the host can tell "started" from
     * "died in the first half second" without parsing distro-specific error text.
     *
     * They are stripped from the terminal transcript ([com.lenix.vm.pty.MarkerFilter]);
     * every one of them starts with [MARKER_PREFIX] so that filter stays a one-liner.
     */
    const val MARKER_PREFIX = "__LENIX_"

    /** The guest shell exec'd successfully. */
    const val MARKER_READY = "__LENIX_READY__"

    /** Xvnc is listening and the window manager is about to start. */
    const val MARKER_DESKTOP_READY = "__LENIX_DESKTOP_READY__"

    /** The VNC server was found but exited immediately; its log follows. */
    const val MARKER_XVNC_FAILED = "__LENIX_XVNC_FAILED__"

    /** The guest has no VNC server / window manager installed; the fix follows. */
    const val MARKER_DESKTOP_MISSING = "__LENIX_DESKTOP_MISSING__"

    fun shell(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        workDir: String = "/root",
    ): List<String> = base(proot, rootfs, home, resolv, shared, workDir) + listOf(
        "/bin/sh", "-c",
        // Signal the host that the shell started successfully, then exec bash.
        // The host waits for __LENIX_READY__ before marking the instance RUNNING
        // (see GuestRuntime.awaitStartup).
        "echo $MARKER_READY >&2; exec /bin/bash -l 2>&1",
    )

    /**
     * The desktop launcher.
     *
     * It resolves the VNC server and the window manager with `command -v` instead of
     * assuming `Xvnc` and `openbox-session` exist: the bundled Debian RootFS is a base
     * image, and tigervnc installs its server as `Xtigervnc` with `Xvnc` as a symlink
     * only on some releases. When nothing is installed the script prints
     * [MARKER_DESKTOP_MISSING] plus the apt line that fixes it and exits, so the host can
     * turn a dead session into an instruction (see `GuestRuntime.start`).
     */
    fun desktop(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        vncPort: Int,
        geometry: String = DEFAULT_GEOMETRY,
        desktop: String = DEFAULT_DESKTOP,
        tini: File? = null,
    ): List<String> {
        val display = displayFor(vncPort)
        val servers = DesktopPackages.VNC_SERVERS.joinToString(" ")
        val managers = DesktopPackages.windowManagers(desktop).joinToString(" ")
        val installHint = DesktopPackages.installCommand(desktop)
        val inner = listOf(
            "/bin/sh", "-c",
            buildString {
                append("export DISPLAY=:$display; ")
                append("export HOME=/root; ")
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; ")
                append("mkdir -p /tmp /run 2>/dev/null; ")
                // Resolve what is actually installed instead of assuming Xvnc/openbox.
                append("VNCBIN=; ")
                append("for candidate in $servers; do ")
                append("  if command -v \"\$candidate\" >/dev/null 2>&1; then VNCBIN=\$candidate; break; fi; ")
                append("done; ")
                append("WMBIN=; ")
                append("for candidate in $managers; do ")
                append("  if command -v \"\$candidate\" >/dev/null 2>&1; then WMBIN=\$candidate; break; fi; ")
                append("done; ")
                append("if [ -z \"\$VNCBIN\" ] || [ -z \"\$WMBIN\" ]; then ")
                append("  echo $MARKER_DESKTOP_MISSING >&2; ")
                append("  [ -z \"\$VNCBIN\" ] && echo \"missing: VNC server ($servers)\" >&2; ")
                append("  [ -z \"\$WMBIN\" ] && echo \"missing: window manager ($managers)\" >&2; ")
                append("  echo \"install it with: $installHint\" >&2; ")
                append("  exit 1; ")
                append("fi; ")
                append("echo \"starting \$VNCBIN on :$display\" >&2; ")
                append("\"\$VNCBIN\" :$display -localhost -geometry $geometry -depth 24 ")
                append("-rfbport $vncPort -SecurityTypes None >/tmp/xvnc.log 2>&1 & ")
                append("XVNCPID=\$!; ")
                append("echo \$XVNCPID > /tmp/xvnc.pid; ")
                append("sleep 1; ")
                append("if ! kill -0 \$XVNCPID 2>/dev/null; then ")
                append("  echo $MARKER_XVNC_FAILED >&2; ")
                append("  cat /tmp/xvnc.log >&2; ")
                append("  exit 1; ")
                append("fi; ")
                // Portable port check - try bash /dev/tcp first, fall back to nc and python3
                append("for i in 1 2 3 4 5 6 7 8 9 10; do ")
                append("  if (echo >/dev/tcp/127.0.0.1/$vncPort) 2>/dev/null; then break; fi; ")
                append("  if nc -z 127.0.0.1 $vncPort 2>/dev/null; then break; fi; ")
                append("  if python3 -c \"import socket; s=socket.socket(); s.settimeout(1); s.connect(('127.0.0.1',$vncPort)); s.close()\" 2>/dev/null; then break; fi; ")
                append("  sleep 0.4; ")
                append("done; ")
                append("echo $MARKER_DESKTOP_READY >&2; ")
                append("\"\$WMBIN\" >/tmp/session.log 2>&1 & ")
                append("touch /run/pvm-ready 2>/dev/null || true; ")
                append("wait")
            },
        )
        val afterTini = if (tini != null && tini.isFile) {
            listOf(tini.absolutePath, "-s", "--") + inner
        } else {
            inner
        }
        return base(proot, rootfs, home, resolv, shared, "/root") + afterTini
    }

    fun displayFor(vncPort: Int): Int {
        val display = vncPort - DISPLAY_BASE_PORT
        return if (display in 1..99) display else DEFAULT_VNC_DISPLAY
    }

    /** The preferred session binary for a desktop flavour (the guest falls back). */
    fun desktopSession(desktop: String): String =
        DesktopPackages.windowManagers(desktop).first()

    /** Environment for the PRoot host process (ADR-021).

      * - `PROOT_LOADER`: pin PRoot's static loader to the APK payload so it never extracts
      *   + execs one from app temp (denied by SELinux on Android 10+).
      * - `PROOT_TMP_DIR`/`TMPDIR`: PRoot's scratch space (never exec'd — safe in filesDir).
      * - `LD_LIBRARY_PATH`: PRoot is a bionic binary; its `.so` deps (libtalloc, etc.)
      *   ship beside the engine binary in the payload dir.
      */
    fun applyEngineEnvironment(
        environment: MutableMap<String, String>,
        loader: File?,
        tmpDir: File?,
        libDir: File?,
    ) {
        if (loader != null && loader.isFile) {
            environment[NativeSetup.ENV_PROOT_LOADER] = loader.absolutePath
        }
        if (tmpDir != null) {
            environment[NativeSetup.ENV_PROOT_TMP_DIR] = tmpDir.absolutePath
            environment[NativeSetup.ENV_TMPDIR] = tmpDir.absolutePath
        }
        if (libDir != null) {
            val existing = environment[NativeSetup.ENV_LD_LIBRARY_PATH]
            environment[NativeSetup.ENV_LD_LIBRARY_PATH] =
                if (existing.isNullOrBlank()) libDir.absolutePath
                else "${libDir.absolutePath}:$existing"
        }
    }

    private fun base(
        proot: File,
        rootfs: File,
        home: File,
        resolv: File,
        shared: File,
        workDir: String,
    ): List<String> {
        val argv = mutableListOf(
            proot.absolutePath,
            "-r", rootfs.absolutePath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${home.absolutePath}:/root",
            "-w", workDir,
        )
        if (resolv.isFile) {
            argv += listOf("-b", "${resolv.absolutePath}:/etc/resolv.conf")
        }
        if (shared.exists() || shared.mkdirs()) {
            argv += listOf("-b", "${shared.absolutePath}:/shared")
        }
        return argv
    }
}
