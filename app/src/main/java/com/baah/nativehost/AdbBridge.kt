package com.baah.nativehost

import android.graphics.BitmapFactory
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** The existing Python adapter speaks the public ADB wire protocol on NEO's private port. */
class AdbBridge(private val execute: (String) -> ByteArray) : AutoCloseable {
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", BuildConfig.BRIDGE_PORT)) }
    init { thread(name = "neo-adb", isDaemon = true) {
        while (!listener.isClosed) {
            val client = runCatching { listener.accept() }.getOrNull() ?: break
            clients += client
            thread(isDaemon = true) { try { serve(client) } finally { clients -= client } }
        }
    } }
    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 30_000; socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var packetSize = 4096
        runCatching { while (true) {
            val header = ByteArray(24).also(input::readFully)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = fields.int; val peer = fields.int; val arg = fields.int
            val length = fields.int; val sum = fields.int; val magic = fields.int
            require(length in 0..1_048_576 && magic == command.inv())
            val bytes = ByteArray(length).also(input::readFully)
            require(bytes.sumOf { it.toInt() and 255 } == sum)
            when (command) {
                0x4e584e43 -> { packetSize = arg.coerceIn(1, 65536); packet(output, command, 0x01000000, 65536, "device::baah-neo\u0000".toByteArray()) }
                0x4e45504f -> {
                    val service = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
                    packet(output, 0x59414b4f, 1, peer)
                    val response = runCatching {
                        require(service.startsWith("shell:") || service.startsWith("exec:"))
                        execute(service.substringAfter(':'))
                    }.getOrElse { "BAAH bridge error: ${it.message}\n".toByteArray() }
                    for (offset in response.indices step packetSize) {
                        packet(output, 0x45545257, 1, peer, response, offset, minOf(packetSize, response.size - offset))
                    }
                    packet(output, 0x45534c43, 1, peer)
                }
                0x59414b4f, 0x45534c43 -> Unit
                else -> error("不支持的 ADB 数据包")
            }
        } }
    }
    private fun packet(out: OutputStream, cmd: Int, local: Int, remote: Int, bytes: ByteArray = byteArrayOf(), offset: Int = 0, length: Int = bytes.size) {
        var checksum = 0
        for (index in offset until offset + length) checksum += bytes[index].toInt() and 255
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(local)
            .putInt(remote).putInt(length).putInt(checksum).putInt(cmd.inv()).array())
        out.write(bytes, offset, length); out.flush()
    }
    override fun close() { listener.close(); clients.forEach { runCatching { it.close() } } }
}
