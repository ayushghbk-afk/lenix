package com.lenix.vm.launch

import com.lenix.data.JsonInstanceStore
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import com.lenix.vm.VmManager
import com.lenix.vm.VmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class GuestRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `start without proot is NATIVE_ENGINE_FAILED`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val runtime = GuestRuntime(filesDir = files, manager = manager, engine = MissingEngine())
        try {
            runtime.start(manager.selectedInstance().id, desktop = false)
            throw AssertionError("expected VmException")
        } catch (e: VmException) {
            assertEquals(VmError.NATIVE_ENGINE_FAILED, e.error)
        }
        assertEquals(VmState.ERROR, manager.selectedInstance().state)
    }

    @Test
    fun `start desktop allocates a port and records the password`() {
        val files = tmp.newFolder("files")
        val manager = VmManager(store = JsonInstanceStore(File(files, "instances")))
        installReady(manager)
        val id = manager.selectedInstance().id
        File(files, "instances/$id/rootfs").mkdirs()
        val engine = RecordingEngine()
        val runtime = GuestRuntime(
            filesDir = files,
            manager = manager,
            engine = engine,
            portAllocator = { 5907 },
            passwordFactory = { "aabbccddeeff" },
        )

        val session = runtime.start(id, desktop = true)

        assertEquals(5907, session.vncPort)
        assertEquals(VmState.RUNNING, manager.getInstance(id)?.state)
        assertEquals(5907, manager.processFor(id)?.vncPort)
        assertEquals("aabbccddeeff", File(files, "instances/$id/vnc/password").readText())
        assertEquals(GuestMode.DESKTOP, engine.last?.mode)
        assertEquals(5907, engine.last?.vncPort)

        runtime.stop(id)
        assertEquals(VmState.READY, manager.getInstance(id)?.state)
        assertFalse(session.isAlive())
    }

    @Test
    fun `vnc password is 12 hex chars`() {
        val pw = VncPassword.generate()
        assertEquals(12, pw.length)
        assertTrue(pw.matches(Regex("[0-9a-f]{12}")))
    }

    private fun installReady(manager: VmManager) {
        val id = manager.selectedInstance().id
        manager.beginInstall(id)
        manager.markVerifying(id)
        manager.markExtracting(id)
        manager.markInstalling(id)
        manager.markReady(id)
    }

    private class MissingEngine : GuestEngine {
        override fun isAvailable(filesDir: File, abi: String) = false
        override fun launch(request: LaunchRequest): GuestSession = error("unused")
    }

    private class RecordingEngine : GuestEngine {
        var last: LaunchRequest? = null
        override fun isAvailable(filesDir: File, abi: String) = true
        override fun launch(request: LaunchRequest): GuestSession {
            last = request
            return FakeSession(request.vncPort)
        }
    }

    private class FakeSession(override val vncPort: Int?) : GuestSession {
        private val alive = AtomicBoolean(true)
        override val pid: Long = 4242
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override fun isAlive(): Boolean = alive.get()
        override fun stop(graceMs: Long) { alive.set(false) }
    }
}
