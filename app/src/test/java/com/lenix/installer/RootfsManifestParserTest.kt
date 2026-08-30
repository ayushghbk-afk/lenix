package com.lenix.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootfsManifestParserTest {

    private val parser = RootfsManifestParser()

    private val validManifest = """
        {
          "schemaVersion": 1,
          "id": "debian-bookworm-aarch64",
          "distro": "debian",
          "codename": "bookworm",
          "arch": "aarch64",
          "version": "1.0.0",
          "channel": "stable",
          "releasedAt": "2026-08-29T00:00:00Z",
          "compatibility": {
            "minAndroidSdk": 29,
            "minRamMb": 2048,
            "recommendedRamMb": 4096
          },
          "desktop": {
            "default": "openbox",
            "flavors": ["openbox"]
          },
          "layers": [
            {
              "id": "base",
              "url": "https://example.invalid/base.tar.zst",
              "sizeBytes": 100,
              "uncompressedBytes": 1000,
              "sha256": "abc123",
              "compression": "zstd"
            }
          ],
          "install": {
            "estimatedFreeGb": 3.0,
            "bootCommand": "/usr/bin/tini -s -- /usr/local/bin/pvm-entry"
          },
          "signature": "ed25519:base64value"
        }
    """.trimIndent()

    @Test
    fun `parses valid manifest`() {
        val manifest = parser.parse(validManifest)
        assertEquals("debian", manifest.distro)
        assertEquals("aarch64", manifest.arch)
        assertEquals(1, manifest.layers.size)
        assertEquals("base", manifest.layers.first().id)
    }

    @Test
    fun `rejects missing signature`() {
        val missing = validManifest.replace("\"signature\": \"ed25519:base64value\"", "\"signature\": \"\"")
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(missing)
        }
    }

    @Test
    fun `rejects a layer without a checksum`() {
        val missingHash = validManifest.replace("\"sha256\": \"abc123\"", "\"sha256\": \"\"")
        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(missingHash)
        }
    }
}
