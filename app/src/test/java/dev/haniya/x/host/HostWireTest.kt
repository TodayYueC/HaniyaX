package dev.haniya.x.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class HostWireTest {
    @Test fun `authentication and request have a bounded UTF8 payload`() {
        val token = "a".repeat(64).toByteArray()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeBytes("HXHB1")
            write(token)
            writeInt(5)
            writeInt(1)
            writeInt(0)
            writeInt(1)
            val name = "测试".toByteArray()
            writeInt(name.size)
            write(name)
        }
        val snapshot = bytes.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(snapshot))
        assertTrue(HostWire.authenticate(input, token))
        assertEquals(HostWire.Request(listOf("测试"), true, 5, 0), HostWire.request(input))
        assertFalse(HostWire.authenticate(DataInputStream(ByteArrayInputStream(snapshot)), ByteArray(64)))
    }

    @Test fun `reject oversized frames before allocating their payload`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeByte(HostWire.DATA)
            writeInt(Int.MAX_VALUE)
        }
        assertTrue(runCatching { HostWire.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }.isFailure)
    }

    @Test fun `round trip exit frame`() {
        val bytes = ByteArrayOutputStream()
        HostWire.write(DataOutputStream(bytes), HostWire.exit(124))
        val frame = HostWire.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(HostWire.EXIT, frame.type)
        assertEquals(124, frame.int())
    }
}
