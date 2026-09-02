package com.lenix.vm.launch

import java.io.File

/**
 * What a guest needs before a desktop session can start, and how to say so.
 *
 * The bundled Debian bookworm RootFS is a **base** image: it ships a shell and apt and
 * nothing else — no X server, no VNC server, no window manager. Starting the desktop on
 * such a guest used to produce `Xvnc: not found`, a dead PRoot process and a Desktop
 * screen that could only say "Guest session died". Everything here exists so that case
 * is detected up front and reported as an instruction the user can follow.
 */
object DesktopPackages {

    /** VNC/X server binaries, in the order the guest script prefers them. */
    val VNC_SERVERS: List<String> = listOf("Xtigervnc", "Xvnc", "Xtightvnc", "Xvnc4")

    /** Directories inside the RootFS that a `PATH` lookup would cover. */
    val SEARCH_DIRS: List<String> = listOf(
        "usr/local/sbin",
        "usr/local/bin",
        "usr/sbin",
        "usr/bin",
        "sbin",
        "bin",
        "usr/bin/X11",
    )

    /** Window manager / session binaries for a desktop flavour, most complete first. */
    fun windowManagers(desktop: String): List<String> = when (desktop.lowercase()) {
        "lxqt" -> listOf("lxqt-session", "startlxqt")
        "xfce", "xfce4" -> listOf("xfce4-session", "startxfce4")
        else -> listOf("openbox-session", "openbox")
    }

    /** Debian packages that provide [windowManagers] plus a VNC server. */
    fun packages(desktop: String): List<String> {
        val session = when (desktop.lowercase()) {
            "lxqt" -> listOf("lxqt-core")
            "xfce", "xfce4" -> listOf("xfce4")
            else -> listOf("openbox", "xterm")
        }
        return listOf("tigervnc-standalone-server", "dbus-x11") + session
    }

    /** The exact command the user can paste into the Terminal window. */
    fun installCommand(desktop: String): String =
        "apt-get update && apt-get install -y " + packages(desktop).joinToString(" ")

    /**
     * The first VNC server binary present in [rootfs], or null when the guest has none.
     *
     * Host-side check on the extracted tree: cheap, and it lets START fail with a real
     * instruction instead of spawning a guest that dies half a second later.
     */
    fun findVncServer(rootfs: File): String? = find(rootfs, VNC_SERVERS)

    /** The first window manager binary for [desktop] present in [rootfs], or null. */
    fun findWindowManager(rootfs: File, desktop: String): String? =
        find(rootfs, windowManagers(desktop))

    /** True when [rootfs] can run a desktop session without installing anything. */
    fun isDesktopInstalled(rootfs: File, desktop: String): Boolean =
        findVncServer(rootfs) != null && findWindowManager(rootfs, desktop) != null

    /** Human-readable reason + fix for a guest that cannot start a desktop. */
    fun missingMessage(rootfs: File, desktop: String): String {
        val missing = buildList {
            if (findVncServer(rootfs) == null) add("a VNC server (${VNC_SERVERS.first()})")
            if (findWindowManager(rootfs, desktop) == null) {
                add("the ${desktop.lowercase()} session (${windowManagers(desktop).first()})")
            }
        }
        val what = when (missing.size) {
            0 -> "the desktop packages"
            1 -> missing.first()
            else -> missing.joinToString(" and ")
        }
        return "This RootFS is a base Debian image: it has no $what, so the desktop " +
            "cannot start. Open the Terminal (START without Auto-start desktop) and run:\n" +
            "  " + installCommand(desktop) + "\n" +
            "then start the instance again with Auto-start desktop enabled."
    }

    private fun find(rootfs: File, names: List<String>): String? {
        for (name in names) {
            for (dir in SEARCH_DIRS) {
                val candidate = File(File(rootfs, dir), name)
                val exists = candidate.isFile ||
                    runCatching { java.nio.file.Files.isSymbolicLink(candidate.toPath()) }
                        .getOrDefault(false)
                if (exists) return name
            }
        }
        return null
    }
}
