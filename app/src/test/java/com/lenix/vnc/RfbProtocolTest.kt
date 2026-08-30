package com.lenix.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RfbProtocolTest {

    @Test
    fun `version round trip`() {
        val out = ByteArrayOutputStream()
        RfbProtocol.writeVersion(out)
        assertEquals(RfbProtocol.VERSION, String(out.toByteArray()))
        assertEquals(
            RfbProtocol.VERSION,
            RfbProtocol.readVersion(ByteArrayInputStream(out.toByteArray())),
        )
    }

    @Test
    fun `chooseSecurity prefers none`() {
        assertEquals(
            RfbProtocol.SECURITY_NONE,
            RfbProtocol.chooseSecurity(listOf(RfbProtocol.SECURITY_VNC, RfbProtocol.SECURITY_NONE)),
        )
    }

    @Test
    fun `readServerInit parses pixel format and name`() {
        val buf = ByteBuffer.allocate(24 + 4 + 5).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1280)
        buf.putShort(720)
        buf.put(32)
        buf.put(24)
        buf.put(0)
        buf.put(1)
        buf.putShort(255)
        buf.putShort(255)
        buf.putShort(255)
        buf.put(16)
        buf.put(8)
        buf.put(0)
        buf.put(ByteArray(3))
        buf.putInt(5)
        buf.put("Lenix".toByteArray())
        val init = RfbProtocol.readServerInit(ByteArrayInputStream(buf.array()))
        assertEquals(1280, init.width)
        assertEquals(720, init.height)
        assertEquals("Lenix", init.name)
        assertEquals(32, init.bitsPerPixel)
        assertTrue(init.trueColour)
    }

    @Test
    fun `decodeRawBgra writes ARGB`() {
        val pixels = IntArray(4)
        val bytes = byteArrayOf(
            0x11, 0x22, 0x33, 0xFF.toByte(),
            0x00, 0x00, 0x00, 0x80.toByte(),
            0x01, 0x02, 0x03, 0x04,
            0x0A, 0x0B, 0x0C, 0x0D,
        )
        val rect = RfbProtocol.Rect(0, 0, 2, 2, RfbProtocol.ENCODING_RAW)
        RfbProtocol.decodeRawBgra(pixels, 2, rect, bytes)
        assertEquals(0xFF332211.toInt(), pixels[0])
        assertEquals(0x80000000.toInt(), pixels[1])
    }

    @Test
    fun `pointer event is six bytes`() {
        val out = ByteArrayOutputStream()
        RfbProtocol.writePointerEvent(out, 1, 10, 20)
        val bytes = out.toByteArray()
        assertEquals(6, bytes.size)
        assertEquals(RfbProtocol.CLIENT_POINTER_EVENT.toByte(), bytes[0])
        assertEquals(1.toByte(), bytes[1])
    }

    @Test
    fun `handshake against a scripted server`() {
        val serverOut = ByteArrayOutputStream()
        // What the client will read: version, 1 security type (None), result 0, server-init
        serverOut.write(RfbProtocol.VERSION.toByteArray())
        serverOut.write(byteArrayOf(1, RfbProtocol.SECURITY_NONE.toByte()))
        serverOut.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array())
        val init = ByteBuffer.allocate(24 + 4 + 1).order(ByteOrder.BIG_ENDIAN)
        init.putShort(2).putShort(2)
        init.put(32).put(24).put(0).put(1)
        init.putShort(255).putShort(255).putShort(255)
        init.put(16).put(8).put(0)
        init.put(ByteArray(3))
        init.putInt(1).put('X'.code.toByte())
        serverOut.write(init.array())

        val clientWrites = ByteArrayOutputStream()
        val client = RfbClient(port = 5901, connect = {
            ByteArrayInputStream(serverOut.toByteArray()) to clientWrites
        })
        client.handshake()
        assertEquals(2, client.server.width)
        assertEquals("X", client.server.name)
        assertTrue(String(clientWrites.toByteArray()).startsWith("RFB "))
    }
}
