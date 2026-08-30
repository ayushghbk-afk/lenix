package com.lenix.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class NativeSetupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `install copies missing assets and skips identical files`() {
        val files = tmp.newFolder("files")
        val dest = NativeSetup.nativeDir(files)
        val payload = "proot-bytes".toByteArray()
        val first = NativeSetup.installFromAssets(
            destDir = dest,
            abi = "arm64-v8a",
            openAsset = { path ->
                if (path.endsWith("/proot")) ByteArrayInputStream(payload) else null
            },
            names = listOf("proot", "tini"),
        )
        assertEquals(listOf("proot"), first.copied)
        assertEquals(listOf("tini"), first.missing)
        assertTrue(NativeSetup.hasProot(files))

        val second = NativeSetup.installFromAssets(
            destDir = dest,
            abi = "arm64-v8a",
            openAsset = { path ->
                if (path.endsWith("/proot")) ByteArrayInputStream(payload) else null
            },
            names = listOf("proot"),
        )
        assertEquals(listOf("proot"), second.skipped)
    }
}
