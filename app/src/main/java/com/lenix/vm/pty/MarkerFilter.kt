package com.lenix.vm.pty

/**
 * Removes the host↔guest handshake markers from what the terminal window shows.
 *
 * The guest scripts print tokens like `__LENIX_READY__` and `__LENIX_DESKTOP_MISSING__`
 * on stderr so the host can tell a started session from one that died (see
 * `ProotCommandBuilder`). stderr is merged into stdout, so without this filter those
 * tokens land in the user's transcript as noise — exactly the `_XVNEC_FAILED` /
 * `XVNC STARTING` lines a session failure used to spray across the window.
 *
 * Stateful on purpose: a marker can be split across two `read()` chunks, so a trailing
 * partial match is held back until the next chunk completes (or contradicts) it. Only
 * the token itself is dropped — any explanatory text the guest printed around it is
 * kept, because that is the part the user needs.
 */
class MarkerFilter(private val prefix: String = DEFAULT_PREFIX) {

    /** A whole marker plus the blank tail of its own line, which was never content. */
    private val marker = Regex(Regex.escape(prefix) + "[A-Z0-9_]*?__[^\\S\\n]*\\n?")
    private val held = StringBuilder()

    /** Filters one decoded chunk, holding back an incomplete trailing marker. */
    fun filter(chunk: String): String {
        if (chunk.isEmpty()) return ""
        held.append(chunk)
        val text = held.toString()
        held.setLength(0)

        val out = StringBuilder()
        var consumed = 0
        for (match in marker.findAll(text)) {
            out.append(text, consumed, match.range.first)
            consumed = match.range.last + 1
            // A marker that ends exactly at the end of the chunk may still grow — the
            // newline that terminates its line can arrive with the next read.
            if (consumed == text.length) {
                held.append(match.value)
                return out.toString()
            }
        }
        return out.toString() + holdTail(text.substring(consumed))
    }

    /** Flushes whatever was held back; call when the guest's stdout reaches EOF. */
    fun flush(): String {
        val rest = held.toString()
        held.setLength(0)
        return marker.replace(rest, "")
    }

    /**
     * Returns [tail] minus anything that could still turn into a marker: an opening
     * prefix with no terminator yet, or a partial prefix (`__LEN`) at the very end.
     */
    private fun holdTail(tail: String): String {
        val open = tail.lastIndexOf(prefix)
        if (open >= 0 && tail.length - open <= MAX_MARKER_LENGTH) {
            held.append(tail, open, tail.length)
            return tail.substring(0, open)
        }
        for (length in minOf(prefix.length - 1, tail.length) downTo 1) {
            if (tail.regionMatches(tail.length - length, prefix, 0, length)) {
                held.append(tail, tail.length - length, tail.length)
                return tail.substring(0, tail.length - length)
            }
        }
        return tail
    }

    companion object {
        const val DEFAULT_PREFIX = "__LENIX_"

        /** Longest token we will wait for before deciding it was ordinary text. */
        private const val MAX_MARKER_LENGTH = 48
    }
}
