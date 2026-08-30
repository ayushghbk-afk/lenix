package com.lenix.vnc

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * RFB 3.8 wire helpers (ADR-003 / ADR-004). Handshake and Raw encoding are
 * implemented in pure Kotlin so they are JVM-testable without a live Xvnc.
 */
object RfbProtocol {
    const val VERSION = "RFB 003.008\n"
    const val SECURITY_INVALID = 0
    const val SECURITY_NONE = 1
    const val SECURITY_VNC = 2
    const val ENCODING_RAW = 0
    const val MSG_FRAMEBUFFER_UPDATE = 0
    const val CLIENT_SET_PIXEL_FORMAT = 0
    const val CLIENT_SET_ENCODINGS = 2
    const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
    const val CLIENT_KEY_EVENT = 4
    const val CLIENT_POINTER_EVENT = 5

    fun writeVersion(out: OutputStream) {
        out.write(VERSION.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    fun readVersion(input: InputStream): String {
        val buf = ByteArray(12)
        DataInputStream(input).readFully(buf)
        return String(buf, StandardCharsets.US_ASCII)
    }

    fun readSecurityTypes(input: InputStream): List<Int> {
        val data = DataInputStream(input)
        val count = data.readUnsignedByte()
        if (count == 0) {
            val reasonLen = data.readInt()
            val reason = ByteArray(reasonLen.coerceAtLeast(0))
            data.readFully(reason)
            error("RFB rejected the client: ${String(reason, StandardCharsets.UTF_8)}")
        }
        return List(count) { data.readUnsignedByte() }
    }

    fun chooseSecurity(types: List<Int>): Int {
        if (SECURITY_NONE in types) return SECURITY_NONE
        if (SECURITY_VNC in types) return SECURITY_VNC
        error("No supported RFB security type in $types")
    }

    fun writeSecurityType(out: OutputStream, type: Int) {
        out.write(type)
        out.flush()
    }

    fun readSecurityResult(input: InputStream): Boolean {
        val result = DataInputStream(input).readInt()
        return result == 0
    }

    fun writeClientInit(out: OutputStream, shared: Boolean = true) {
        out.write(if (shared) 1 else 0)
        out.flush()
    }

    data class ServerInit(
        val width: Int,
        val height: Int,
        val name: String,
        val bitsPerPixel: Int,
        val depth: Int,
        val bigEndian: Boolean,
        val trueColour: Boolean,
        val redMax: Int,
        val greenMax: Int,
        val blueMax: Int,
        val redShift: Int,
        val greenShift: Int,
        val blueShift: Int,
    )

    fun readServerInit(input: InputStream): ServerInit {
        val data = DataInputStream(input)
        val width = data.readUnsignedShort()
        val height = data.readUnsignedShort()
        val bitsPerPixel = data.readUnsignedByte()
        val depth = data.readUnsignedByte()
        val bigEndian = data.readUnsignedByte() != 0
        val trueColour = data.readUnsignedByte() != 0
        val redMax = data.readUnsignedShort()
        val greenMax = data.readUnsignedShort()
        val blueMax = data.readUnsignedShort()
        val redShift = data.readUnsignedByte()
        val greenShift = data.readUnsignedByte()
        val blueShift = data.readUnsignedByte()
        data.skipBytes(3)
        val nameLen = data.readInt()
        val nameBytes = ByteArray(nameLen.coerceAtLeast(0))
        data.readFully(nameBytes)
        return ServerInit(
            width = width,
            height = height,
            name = String(nameBytes, StandardCharsets.UTF_8),
            bitsPerPixel = bitsPerPixel,
            depth = depth,
            bigEndian = bigEndian,
            trueColour = trueColour,
            redMax = redMax,
            greenMax = greenMax,
            blueMax = blueMax,
            redShift = redShift,
            greenShift = greenShift,
            blueShift = blueShift,
        )
    }

    fun writeSetEncodings(out: OutputStream, encodings: IntArray = intArrayOf(ENCODING_RAW)) {
        val buf = ByteBuffer.allocate(4 + encodings.size * 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_SET_ENCODINGS.toByte())
        buf.put(0)
        buf.putShort(encodings.size.toShort())
        encodings.forEach { buf.putInt(it) }
        out.write(buf.array())
        out.flush()
    }

    fun writeFramebufferUpdateRequest(
        out: OutputStream,
        incremental: Boolean,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_FRAMEBUFFER_UPDATE_REQUEST.toByte())
        buf.put(if (incremental) 1 else 0)
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        out.write(buf.array())
        out.flush()
    }

    fun writePointerEvent(out: OutputStream, buttonMask: Int, x: Int, y: Int) {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_POINTER_EVENT.toByte())
        buf.put(buttonMask.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        out.write(buf.array())
        out.flush()
    }

    fun writeKeyEvent(out: OutputStream, down: Boolean, keysym: Int) {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.put(CLIENT_KEY_EVENT.toByte())
        buf.put(if (down) 1 else 0)
        buf.putShort(0)
        buf.putInt(keysym)
        out.write(buf.array())
        out.flush()
    }

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int, val encoding: Int)

    fun readFramebufferUpdateHeader(input: InputStream): Int {
        val data = DataInputStream(input)
        val type = data.readUnsignedByte()
        if (type != MSG_FRAMEBUFFER_UPDATE) {
            error("Unexpected RFB message $type")
        }
        data.readUnsignedByte()
        return data.readUnsignedShort()
    }

    fun readRectHeader(input: InputStream): Rect {
        val data = DataInputStream(input)
        return Rect(
            x = data.readUnsignedShort(),
            y = data.readUnsignedShort(),
            width = data.readUnsignedShort(),
            height = data.readUnsignedShort(),
            encoding = data.readInt(),
        )
    }

    /**
     * Decodes a Raw rectangle of 32-bpp little-endian BGRA into ARGB ints.
     */
    fun decodeRawBgra(pixels: IntArray, stride: Int, rect: Rect, bytes: ByteArray) {
        var src = 0
        for (row in 0 until rect.height) {
            var dst = (rect.y + row) * stride + rect.x
            for (col in 0 until rect.width) {
                val b = bytes[src].toInt() and 0xff
                val g = bytes[src + 1].toInt() and 0xff
                val r = bytes[src + 2].toInt() and 0xff
                val a = bytes[src + 3].toInt() and 0xff
                pixels[dst] = (a shl 24) or (r shl 16) or (g shl 8) or b
                src += 4
                dst++
            }
        }
    }

    fun writeData(out: DataOutputStream, bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }
}
