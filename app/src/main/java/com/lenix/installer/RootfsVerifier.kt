package com.lenix.installer

import com.lenix.vm.VmError
import com.lenix.vm.VmException
import java.io.File
import java.security.MessageDigest

/**
 * Verifies downloaded RootFS bytes before extraction.
 *
 * Phase 1 implements SHA-256 verification of each layer against the manifest. Layer
 * signature verification (Ed25519/minisign) will be layered on the same interface in
 * a later commit without changing callers.
 */
class RootfsVerifier {

    fun verifyFile(file: File, expectedSha256: String) {
        val actualHex = sha256(file)
        if (!actualHex.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw VmException(
                VmError.CHECKSUM_FAILED,
                "Expected $expectedSha256 but computed $actualHex for ${file.name}.",
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
