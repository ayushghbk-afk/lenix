package com.lenix.vm.pty

import com.lenix.vm.launch.GuestSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * Line-oriented view of a guest shell: stdout is appended to [output], stdin is
 * [send]. Used by the Compose terminal. When a real PTY master exists it can be
 * swapped in without changing the UI (ADR-009).
 */
class PtySession(
    private val guest: GuestSession,
    private val scope: CoroutineScope,
    private val charset: Charset = Charsets.UTF_8,
    private val maxChars: Int = 64_000,
) {
    private val mutableOutput = MutableStateFlow("")
    val output: StateFlow<String> = mutableOutput.asStateFlow()

    private val reader: Job = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(4096)
        try {
            while (guest.isAlive()) {
                val n = guest.stdout.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val chunk = String(buffer, 0, n, charset)
                mutableOutput.value = trim((mutableOutput.value + chunk))
            }
        } catch (_: Exception) {
            // Session closed.
        }
    }

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        guest.stdin.write(text.toByteArray(charset))
        guest.stdin.flush()
    }

    fun close() {
        reader.cancel()
    }

    private fun trim(value: String): String =
        if (value.length <= maxChars) value else value.substring(value.length - maxChars)
}
