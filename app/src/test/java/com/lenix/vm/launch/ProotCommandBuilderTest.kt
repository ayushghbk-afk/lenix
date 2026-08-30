package com.lenix.vm.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandBuilderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `shell argv fakes root and binds the documented host paths`() {
        val proot = tmp.newFile("proot")
        val rootfs = tmp.newFolder("rootfs")
        val home = tmp.newFolder("home")
        val shared = tmp.newFolder("shared")
        val resolv = File(tmp.newFolder("etc"), "resolv.conf").also { it.writeText("nameserver 1.1.1.1\n") }

        val argv = ProotCommandBuilder.shell(proot, rootfs, home, resolv, shared)

        assertEquals(proot.absolutePath, argv.first())
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("-r"))
        assertTrue(argv.contains(rootfs.absolutePath))
        assertTrue(argv.any { it.startsWith(home.absolutePath) && it.endsWith(":/root") })
        assertTrue(argv.any { it.contains("resolv.conf") })
        assertEquals("/bin/bash", argv[argv.lastIndex - 1])
        assertEquals("-l", argv.last())
    }

    @Test
    fun `desktop argv launches Xvnc on loopback and openbox`() {
        val proot = tmp.newFile("proot")
        val rootfs = tmp.newFolder("rootfs")
        val home = tmp.newFolder("home")
        val shared = tmp.newFolder("shared")
        val resolv = File(tmp.newFolder("etc"), "resolv.conf").also { it.writeText("nameserver 8.8.8.8\n") }

        val argv = ProotCommandBuilder.desktop(
            proot, rootfs, home, resolv, shared,
            vncPort = 5901,
            geometry = "1280x720",
            desktop = "openbox",
        )
        val script = argv.last()
        assertTrue(script.contains("Xvnc :1"))
        assertTrue(script.contains("-localhost"))
        assertTrue(script.contains("-rfbport 5901"))
        assertTrue(script.contains("openbox-session"))
        assertTrue(script.contains("-geometry 1280x720"))
    }

    @Test
    fun `displayFor maps RFB ports onto X displays`() {
        assertEquals(1, ProotCommandBuilder.displayFor(5901))
        assertEquals(7, ProotCommandBuilder.displayFor(5907))
        assertEquals(ProotCommandBuilder.DEFAULT_VNC_DISPLAY, ProotCommandBuilder.displayFor(80))
    }

    @Test
    fun `desktopSession maps flavors`() {
        assertEquals("openbox-session", ProotCommandBuilder.desktopSession("openbox"))
        assertEquals("lxqt-session", ProotCommandBuilder.desktopSession("lxqt"))
        assertEquals("xfce4-session", ProotCommandBuilder.desktopSession("xfce"))
    }
}
