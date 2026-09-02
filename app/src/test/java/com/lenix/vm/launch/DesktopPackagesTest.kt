package com.lenix.vm.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPackagesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a base rootfs has no desktop`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("")

        assertNull(DesktopPackages.findVncServer(rootfs))
        assertNull(DesktopPackages.findWindowManager(rootfs, "openbox"))
        assertFalse(DesktopPackages.isDesktopInstalled(rootfs, "openbox"))
    }

    @Test
    fun `tigervnc under usr bin counts, whatever the binary is called`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/Xtigervnc").writeText("")
        File(rootfs, "usr/bin/openbox-session").writeText("")

        assertEquals("Xtigervnc", DesktopPackages.findVncServer(rootfs))
        assertEquals("openbox-session", DesktopPackages.findWindowManager(rootfs, "openbox"))
        assertTrue(DesktopPackages.isDesktopInstalled(rootfs, "openbox"))
    }

    @Test
    fun `the missing message names what is missing and how to install it`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/Xvnc").writeText("")

        val message = DesktopPackages.missingMessage(rootfs, "openbox")

        assertTrue(message.contains("openbox-session"))
        assertFalse("the VNC server is installed", message.contains("Xtigervnc)"))
        assertTrue(message.contains("apt-get update && apt-get install -y"))
        assertTrue(message.contains("tigervnc-standalone-server"))
    }

    @Test
    fun `install command follows the desktop flavour`() {
        assertTrue(DesktopPackages.installCommand("xfce").contains("xfce4"))
        assertTrue(DesktopPackages.installCommand("lxqt").contains("lxqt-core"))
        assertTrue(DesktopPackages.installCommand("openbox").contains("openbox"))
    }
}
