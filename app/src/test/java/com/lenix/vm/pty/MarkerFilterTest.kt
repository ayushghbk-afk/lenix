package com.lenix.vm.pty

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerFilterTest {

    @Test
    fun `drops handshake markers and keeps the words around them`() {
        val filter = MarkerFilter()
        assertEquals(
            "missing: VNC server\n",
            filter.filter("__LENIX_DESKTOP_MISSING__missing: VNC server\n"),
        )
    }

    @Test
    fun `a marker split across two reads is still removed`() {
        val filter = MarkerFilter()
        val first = filter.filter("boot noise\n__LENIX_DESK")
        val second = filter.filter("TOP_READY__starting openbox\n")

        assertEquals("boot noise\n", first)
        assertEquals("starting openbox\n", second)
    }

    @Test
    fun `a partial prefix at the end is held back, not printed`() {
        val filter = MarkerFilter()
        assertEquals("ok\n", filter.filter("ok\n__LEN"))
        assertEquals("", filter.filter("IX_READY__"))
    }

    @Test
    fun `ordinary underscores are never eaten`() {
        val filter = MarkerFilter()
        val text = "total 4\ndrwx__ 2 root root\n"
        assertEquals(text, filter.filter(text))
    }

    @Test
    fun `flush releases text that only looked like a marker`() {
        val filter = MarkerFilter()
        assertEquals("", filter.filter("__LENIX_"))
        assertEquals("__LENIX_", filter.flush())
    }
}
