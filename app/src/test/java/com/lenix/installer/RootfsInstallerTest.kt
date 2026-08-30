package com.lenix.installer

import com.lenix.data.JsonInstallStateStore
import com.lenix.data.download.LayerCache
import com.lenix.data.download.ResumableDownloader
import com.lenix.vm.VmError
import com.lenix.vm.VmException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Phase 3 pipeline tests: the installer runs its whole flow (download → verify →
 * stage → commit) against a local HTTP server, and — the point of the phase — an
 * interrupted install resumes instead of restarting.
 */
class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val files = ConcurrentHashMap<String, ByteArray>()

    private val base = ByteArray(60_000) { (it % 251).toByte() }
    private val desktop = ByteArray(25_000) { (it % 239).toByte() }

    private val instanceId = "debian-ab12cd"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = RangeFileServer(files)
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installer(filesDir: File, maxAttempts: Int = 3): RootfsInstaller =
        RootfsInstaller(
            filesDir = filesDir,
            downloader = ResumableDownloader(
                cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
                client = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                maxAttempts = maxAttempts,
                backoffMillis = { 0 },
            ),
        )

    private fun manifestJson(arch: String = "aarch64"): String {
        fun layer(id: String, bytes: ByteArray) = """
            {
              "id": "$id",
              "url": "${server.url("/$id.layer")}",
              "sizeBytes": ${bytes.size},
              "uncompressedBytes": ${bytes.size * 4},
              "sha256": "${sha256(bytes)}",
              "compression": "xz"
            }
        """.trimIndent()
        return """
        {
          "schemaVersion": 1,
          "id": "debian-bookworm-aarch64",
          "distro": "debian",
          "codename": "bookworm",
          "arch": "$arch",
          "version": "0.1.0",
          "channel": "stable",
          "releasedAt": "2026-08-30T00:00:00Z",
          "layers": [
            ${layer("base", base)},
            ${layer("desktop-openbox", desktop)}
          ],
          "install": {
            "estimatedFreeGb": 0.5,
            "bootCommand": "/bin/bash"
          },
          "signature": "unsigned:phase-4"
        }
        """.trimIndent()
    }

    @Test
    fun `installs all layers end to end and commits the instance`() {
        files["/base.layer"] = base
        files["/desktop-openbox.layer"] = desktop
        val filesDir = tmp.newFolder("files")

        val progress = runBlocking { installer(filesDir).install(instanceId, manifestJson()).toList() }

        // Full pipeline reached READY with every phase reported.
        assertEquals(RootfsInstaller.Progress.Ready, progress.last())
        assertTrue(progress.first() is RootfsInstaller.Progress.Download)
        assertTrue(progress.any { it is RootfsInstaller.Progress.Verifying })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Extracting })
        assertTrue(progress.any { it is RootfsInstaller.Progress.Committing })

        // Aggregate download progress ends at the manifest total.
        val lastDownload = progress.filterIsInstance<RootfsInstaller.Progress.Download>().last()
        assertEquals(lastDownload.bytesTotal, lastDownload.bytesDone)
        assertEquals((base.size + desktop.size).toLong(), lastDownload.bytesDone)

        // The instance is committed on disk and the checkpoint is cleared.
        val instanceDir = File(File(filesDir, "instances"), instanceId)
        assertTrue(File(instanceDir, "rootfs").isDirectory)
        assertArrayEquals(base, File(File(instanceDir, "rootfs"), "base.layer").readBytes())
        assertTrue(File(instanceDir, "rootfs.json").isFile)
        assertFalse(File(instanceDir, "state.json").exists())
        assertFalse(File(instanceDir, ".tmp").exists())

        // Both layers live in the shared, content-addressed cache.
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        assertTrue(cache.cachedFile(sha256(base)).isFile)
        assertTrue(cache.cachedFile(sha256(desktop)).isFile)
    }

    @Test
    fun `a retry reuses cached layers and resumes the interrupted one in place`() {
        files["/base.layer"] = base
        files["/desktop-openbox.layer"] = desktop
        val filesDir = tmp.newFolder("files")

        // Simulate the aftermath of a killed install: base fully cached, the
        // desktop layer interrupted mid-download.
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        cache.cachedFile(sha256(base)).writeBytes(base)
        cache.partFile(sha256(desktop)).writeBytes(desktop.copyOfRange(0, 7_000))

        val progress = runBlocking {
            installer(filesDir).install(instanceId, manifestJson()).toList()
        }

        assertEquals(RootfsInstaller.Progress.Ready, progress.last())

        // Only the desktop layer hit the network, resuming at byte 7000.
        assertEquals(1, server.requestCount)
        assertEquals("bytes=7000-", server.takeRequest().getHeader("Range"))

        // The first progress event already reflects the cached base layer.
        val first = progress.filterIsInstance<RootfsInstaller.Progress.Download>().first()
        assertEquals(base.size.toLong(), first.bytesDone)
    }

    @Test
    fun `a network failure leaves a retryable checkpoint and a resumable partial`() {
        files["/base.layer"] = base
        val filesDir = tmp.newFolder("files")

        // The desktop layer never arrives: every attempt times out waiting for it.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                if (path == "/desktop-openbox.layer") {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(desktop))
                        // One byte every 500ms: the first byte lands immediately,
                        // the next never arrives within the client's 300ms read
                        // timeout — and the server wakes quickly enough after the
                        // client hangs up that tearDown's shutdown() stays clean.
                        .throttleBody(1, 500, TimeUnit.MILLISECONDS)
                }
                return RangeFileServer(files).dispatch(request)
            }
        }
        val impatientClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val failingInstaller = RootfsInstaller(
            filesDir = filesDir,
            downloader = ResumableDownloader(
                cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR)),
                client = impatientClient,
                maxAttempts = 1,
                backoffMillis = { 0 },
            ),
        )

        val exception = assertThrows(VmException::class.java) {
            runBlocking { failingInstaller.install(instanceId, manifestJson()).toList() }
        }
        assertEquals(VmError.NETWORK_ERROR, exception.error)

        // The checkpoint records exactly where the install died…
        val state = JsonInstallStateStore.forInstance(filesDir, instanceId).load()
        assertNotNull(state)
        assertEquals("DOWNLOADING", state?.phase)
        assertEquals(1, state?.layerIndex)
        assertEquals(2, state?.layerCount)

        // …and the base layer is fully cached, so the retry only needs layer two.
        assertTrue(LayerCache(File(filesDir, LayerCache.LAYER_DIR)).cachedFile(sha256(base)).isFile)

        // Retry against a healthy server: base is never downloaded again.
        files["/desktop-openbox.layer"] = desktop
        server.dispatcher = RangeFileServer(files)
        val progress = runBlocking {
            installer(filesDir).install(instanceId, manifestJson()).toList()
        }
        assertEquals(RootfsInstaller.Progress.Ready, progress.last())
        // base (failed run) + desktop (failed run) + desktop (retry): the cached
        // base layer is never requested a second time.
        assertEquals(3, server.requestCount)
        assertEquals("/base.layer", server.takeRequest().path)
        assertEquals("/desktop-openbox.layer", server.takeRequest().path)
        assertEquals("/desktop-openbox.layer", server.takeRequest().path)
    }

    @Test
    fun `an invalid manifest fails as DOWNLOAD_CORRUPTED without touching the network`() {
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir).install(instanceId, "{}").toList() }
        }

        assertEquals(VmError.DOWNLOAD_CORRUPTED, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unsupported architecture is rejected before any download`() {
        files["/base.layer"] = base
        files["/desktop-openbox.layer"] = desktop
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir).install(instanceId, manifestJson(arch = "x86_64")).toList() }
        }

        assertEquals(VmError.UNSUPPORTED_ARCHITECTURE, exception.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `tampered layer bytes fail the checksum gate`() {
        files["/base.layer"] = ByteArray(base.size) { (it % 199).toByte() } // wrong content
        files["/desktop-openbox.layer"] = desktop
        val filesDir = tmp.newFolder("files")

        val exception = assertThrows(VmException::class.java) {
            runBlocking { installer(filesDir, maxAttempts = 2).install(instanceId, manifestJson()).toList() }
        }

        assertEquals(VmError.CHECKSUM_FAILED, exception.error)
        // Nothing partial or completed was committed for the tampered layer.
        val cache = LayerCache(File(filesDir, LayerCache.LAYER_DIR))
        assertFalse(cache.cachedFile(sha256(base)).exists())
        assertFalse(cache.partFile(sha256(base)).exists())
        assertFalse(File(File(File(filesDir, "instances"), instanceId), "rootfs").exists())
    }

    /**
     * A static file server that honors `Range` and reports an `ETag`.
     */
    private class RangeFileServer(
        private val files: Map<String, ByteArray>,
        private val etag: String = "\"v1\"",
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?') ?: "/"
            val body = files[path] ?: return MockResponse().setResponseCode(404)
            val range = request.getHeader("Range")
            val from = range
                ?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?: 0L
            return when {
                range != null && from >= body.size -> MockResponse().setResponseCode(416)
                range == null -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(body))

                else -> MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", etag)
                    .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(from.toInt(), body.size)))
            }
        }

        companion object {
            private val RANGE = Regex("bytes=(\\d+)-")
        }
    }
}
