package com.lenix.vm.pty

import com.lenix.vm.launch.GuestSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What the terminal window renders: the transcript plus whether a guest shell is
 * attached to it. The screen is a pure function of this, so leaving the screen and
 * coming back cannot lose the transcript or double-attach a reader.
 */
data class TerminalSnapshot(
    val text: String = "",
    val revision: Long = 0L,
    val alive: Boolean = false,
    val pid: Long = 0L,
    /** One-line status under the transcript: why nothing is attached, or how it ended. */
    val notice: String? = null,
) {
    companion object {
        /** The window's state while no guest session exists. */
        fun disconnected(notice: String): TerminalSnapshot =
            TerminalSnapshot(text = "", revision = 0L, alive = false, pid = 0L, notice = notice)
    }
}

/**
 * Attaches the terminal window to a live [GuestSession].
 *
 * The guest's stdio is a pipe, not a PTY (`libpvmnative`'s `openpty` is optional and not
 * shipped — ADR-019), which has three consequences this class owns:
 *
 * 1. **The shell never echoes.** bash reads lines from a pipe without readline, so what
 *    the user types must be echoed locally ([TerminalBuffer.echoLine]) or the window
 *    shows output with no command line above it.
 * 2. **Exactly one reader may exist.** The session is owned by `GuestRuntime` for as
 *    long as the guest runs — not by the Compose screen — so navigating away neither
 *    drops output nor leaves the 64 KiB pipe full, which would block the guest.
 * 3. **Control bytes are not signals.** There is no line discipline to turn `^C` into
 *    SIGINT; the only in-band end-of-input is closing stdin ([sendEof]).
 *
 * Output is decoded incrementally, so a multi-byte UTF-8 sequence split across two
 * reads renders correctly instead of as two replacement characters.
 *
 * @param echoInput set false once a real PTY exists and the shell echoes for us
 * @param prompt prefix for the local echo, so the transcript reads like a terminal
 *   (`$ ls` above its output) instead of bare output with no command line
 * @param threadFactory injectable for tests; the reader is a daemon thread that owns
 *   the blocking `read()` and no coroutine scope is required
 */
class PtySession(
    private val guest: GuestSession,
    private val buffer: TerminalBuffer = TerminalBuffer(),
    private val charset: Charset = Charsets.UTF_8,
    private val echoInput: Boolean = true,
    private val prompt: String = DEFAULT_PROMPT,
    private val threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, READER_THREAD).apply { isDaemon = true }
    },
) {
    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Trailing bytes of an incomplete multi-byte sequence, carried to the next read. */
    private val pendingBytes = ByteArray(4)
    private var pendingCount = 0

    @Volatile
    private var closed = false

    @Volatile
    private var exited = !guest.isAlive()

    @Volatile
    private var stdinClosed = false

    @Volatile
    private var notice: String? = if (guest.isAlive()) null else EXITED_NOTICE

    private val mutableSnapshot = MutableStateFlow(
        TerminalSnapshot(
            text = buffer.text(),
            revision = buffer.revision,
            alive = !exited,
            pid = guest.pid,
            notice = notice,
        ),
    )
    val snapshot: StateFlow<TerminalSnapshot> = mutableSnapshot.asStateFlow()

    private var reader: Thread? = null

    /** Starts the reader thread. Idempotent. */
    fun start(): PtySession {
        if (reader != null) return this
        reader = threadFactory { pump() }.also(Thread::start)
        return this
    }

    /**
     * Types [line] into the guest shell; a newline is appended. Returns false when there
     * is nothing to write to (never throws — a dead shell is a status line, not a crash).
     */
    fun send(line: String): Boolean =
        write((line + "\n").toByteArray(charset)) { buffer.echoLine(prompt + line) }

    /**
     * Closes the guest's stdin, the only end-of-input a pipe-backed shell understands:
     * bash sees EOF and exits. Returns false when stdin was already closed.
     */
    fun sendEof(): Boolean {
        if (closed || exited || stdinClosed) return false
        return try {
            guest.stdin.flush()
            guest.stdin.close()
            stdinClosed = true
            true
        } catch (e: IOException) {
            markExited(e.message?.let { "Shell closed: $it" } ?: EXITED_NOTICE)
            false
        }
    }

    /** Clears the scrollback; the guest is untouched. */
    fun clear() {
        buffer.clear()
        publish()
    }

    /** Detaches the window. The guest keeps running; [GuestRuntime] owns its lifetime. */
    fun close() {
        closed = true
        reader?.interrupt()
        reader = null
    }

    private fun write(bytes: ByteArray, echo: () -> Unit): Boolean {
        if (closed) return false
        if (exited || !guest.isAlive()) {
            markExited(EXITED_NOTICE)
            return false
        }
        return try {
            guest.stdin.write(bytes)
            guest.stdin.flush()
            if (echoInput) echo()
            publish()
            true
        } catch (e: IOException) {
            markExited(e.message?.let { "Shell closed: $it" } ?: EXITED_NOTICE)
            false
        }
    }

    private fun pump() {
        val bytes = ByteArray(READ_CHUNK)
        val chars = CharBuffer.allocate(READ_CHUNK * 2)
        try {
            val stdout = guest.stdout
            while (!closed) {
                val read = stdout.read(bytes)
                if (read < 0) break
                if (read == 0) continue
                val text = decode(bytes, read, chars, endOfInput = false)
                if (text.isNotEmpty()) {
                    buffer.write(text)
                    publish()
                }
            }
            val tail = decode(bytes, 0, chars, endOfInput = true)
            if (tail.isNotEmpty()) buffer.write(tail)
        } catch (_: IOException) {
            // The guest died or its stdout was closed under us; report it below.
        } catch (_: InterruptedException) {
            // close() interrupted us on purpose; put the flag back and stop quietly.
            Thread.currentThread().interrupt()
        } finally {
            if (!closed) markExited(EXITED_NOTICE)
        }
    }

    private fun decode(bytes: ByteArray, length: Int, chars: CharBuffer, endOfInput: Boolean): String {
        val input = if (pendingCount == 0) {
            ByteBuffer.wrap(bytes, 0, length)
        } else {
            val combined = ByteArray(pendingCount + length)
            System.arraycopy(pendingBytes, 0, combined, 0, pendingCount)
            System.arraycopy(bytes, 0, combined, pendingCount, length)
            pendingCount = 0
            ByteBuffer.wrap(combined)
        }
        chars.clear()
        decoder.decode(input, chars, endOfInput)
        if (endOfInput) decoder.flush(chars)
        // Whatever the decoder left unconsumed is a partial sequence: hold it back.
        val left = input.remaining()
        if (left in 1..pendingBytes.size) {
            input.get(pendingBytes, 0, left)
            pendingCount = left
        }
        chars.flip()
        return chars.toString()
    }

    private fun markExited(message: String) {
        if (exited && notice == message) return
        exited = true
        notice = message
        publish()
    }

    private fun publish() {
        mutableSnapshot.value = TerminalSnapshot(
            text = buffer.text(),
            revision = buffer.revision,
            alive = !exited && !closed,
            pid = guest.pid,
            notice = notice,
        )
    }

    companion object {
        const val READER_THREAD = "lenix-terminal"
        const val EXITED_NOTICE = "Guest shell exited. Press START on Home to launch it again."
        const val DEFAULT_PROMPT = "$ "

        private const val READ_CHUNK = 4_096
    }
}
