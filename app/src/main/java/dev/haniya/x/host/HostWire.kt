package dev.haniya.x.host

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal object HostWire {
    private val magic = "HXHB1".toByteArray(Charsets.US_ASCII)
    const val MAX_FRAME = 32_768
    const val DATA = 1
    const val EOF = 2
    const val RESIZE = 3
    const val OUT = 4
    const val ERR = 5
    const val EXIT = 6
    const val READY = 7
    const val NOTICE = 8
    const val START = 9

    data class Request(val args: List<String>, val root: Boolean, val seconds: Int, val geometry: Int)

    data class Frame(val type: Int, val payload: ByteArray) {
        fun int() = ByteBuffer.wrap(payload).int
    }

    fun authenticate(input: DataInputStream, token: ByteArray): Boolean {
        val version = ByteArray(magic.size).also(input::readFully)
        val supplied = ByteArray(token.size).also(input::readFully)
        return magic.contentEquals(version) && MessageDigest.isEqual(supplied, token)
    }

    fun request(input: DataInputStream): Request {
        val seconds = input.readInt()
        val flags = input.readInt()
        val size = input.readInt()
        val count = input.readInt()
        require(seconds in 0..3600 && flags in 0..1 && count in 1..5) { "Invalid bridge request header" }
        val args = List(count) {
            val length = input.readInt()
            require(length in 0..MAX_FRAME) { "Invalid command argument size" }
            val bytes = ByteArray(length).also(input::readFully)
            val arg = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            require('\u0000' !in arg) { "NUL is not allowed in command arguments" }
            arg
        }
        return Request(args, flags == 1, seconds, size)
    }

    fun read(input: DataInputStream): Frame {
        val type = input.readUnsignedByte()
        val size = input.readInt()
        if (size !in 0..MAX_FRAME) throw IOException("Invalid host frame size")
        return Frame(type, ByteArray(size).also(input::readFully))
    }

    fun write(output: DataOutputStream, frame: Frame) {
        output.writeByte(frame.type)
        output.writeInt(frame.payload.size)
        output.write(frame.payload)
        output.flush()
    }

    fun text(type: Int, message: String) = Frame(type, message.toByteArray(Charsets.UTF_8))
    fun exit(code: Int) = Frame(EXIT, ByteBuffer.allocate(4).putInt(code).array())
    fun empty(type: Int) = Frame(type, byteArrayOf())
}
