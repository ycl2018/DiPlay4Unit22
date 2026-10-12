package com.shilapi.xcertplay.adb

import com.shilapi.xcertplay.network.ChanganS202HeadUnit
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A shell on the head unit's own adbd ("ADB over network", 127.0.0.1:5555), for the few commands an
 * ordinary app may not run, such as switching the BYD cluster's navigation mode. Speaks the plain
 * ADB protocol (AOSP adb/protocol.txt); the TLS variant used by Android 11+ wireless debugging is
 * not supported.
 *
 * adbd trusts a key once the driver approves it in the car's "Allow debugging?" dialog. Only
 * [connect] with `mayAsk = true` offers the key for approval. Background use never does, so the
 * dialog cannot appear while driving.
 */
class LocalAdb(
    private val key: KeyPair,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
    private val shellPreamble: ByteArray? = LocalAdbShellPolicy.currentPreamble(),
) : Closeable {
    enum class Access { READY, NOT_APPROVED, UNREACHABLE, UNSUPPORTED }

    @Volatile private var socket: Socket? = null
    private val cancelled = AtomicBoolean(false)
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextStreamId = 1

    @Synchronized
    fun connect(mayAsk: Boolean): Access {
        if (cancelled.get()) return Access.UNREACHABLE
        if (socket?.isClosed == false) return Access.READY
        return try {
            val address = InetSocketAddress(host, port)
            val opened = Socket().also { socket = it }.apply {
                if (cancelled.get()) throw IOException("ADB operation cancelled")
                connect(address, CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
                tcpNoDelay = true
            }
            socket = opened
            input = BufferedInputStream(opened.getInputStream())
            output = opened.getOutputStream()
            send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, "host::\u0000".toByteArray()))
            handshake(mayAsk).also { if (it != Access.READY) closeQuietly() }
        } catch (_: SocketTimeoutException) {
            closeQuietly()
            if (mayAsk) Access.NOT_APPROVED else Access.UNREACHABLE
        } catch (_: IOException) {
            closeQuietly()
            Access.UNREACHABLE
        }
    }

    /** Runs [command] in adbd's shell and returns its output, or null if the link failed. */
    fun shell(command: String): String? = shell(command, READ_TIMEOUT_MS)

    /** Same as [shell], with a larger bounded read window for a one-shot slow command. */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int): String? {
        if (cancelled.get()) return null
        if (socket?.isClosed != false && connect(mayAsk = false) != Access.READY) return null
        return try {
            require(readTimeoutMillis in 1..MAX_COMMAND_TIMEOUT_MS)
            socket?.soTimeout = readTimeoutMillis
            val local = nextStreamId++
            val boundary = shellPreamble?.let { VendorShellOutput() }
            val remoteCommand = boundary?.wrap(command) ?: command
            send(AdbPacket(AdbPacket.OPEN, local, 0, "shell:$remoteCommand\u0000".toByteArray()))
            var remote = 0
            var finished = false
            var preambleSent = false
            val text = java.io.ByteArrayOutputStream()
            while (!finished) {
                val packet = receive()
                when {
                    packet.command == AdbPacket.OKAY && packet.arg1 == local -> {
                        remote = packet.arg0
                        if (shellPreamble != null && !preambleSent) {
                            send(AdbPacket(AdbPacket.WRTE, local, remote, shellPreamble.copyOf()))
                            preambleSent = true
                        }
                    }
                    packet.command == AdbPacket.WRTE && packet.arg1 == local -> {
                        text.write(boundary?.accept(packet.payload) ?: packet.payload)
                        send(AdbPacket(AdbPacket.OKAY, local, packet.arg0, ByteArray(0)))
                    }
                    packet.command == AdbPacket.CLSE && packet.arg1 == local -> {
                        if (remote != 0) send(AdbPacket(AdbPacket.CLSE, local, remote, ByteArray(0)))
                        finished = true
                    }
                    // A stream left over from an earlier, interrupted command: close it and move on.
                    packet.command == AdbPacket.WRTE ->
                        send(AdbPacket(AdbPacket.CLSE, packet.arg1, packet.arg0, ByteArray(0)))
                }
            }
            if (boundary != null && !boundary.started) throw IOException("shell validation did not complete")
            text.toString("UTF-8").trim().also { socket?.soTimeout = READ_TIMEOUT_MS }
        } catch (_: IOException) {
            closeQuietly()
            null
        }
    }

    /** Exclusive interactive stream. Use a dedicated LocalAdb instance; never mix with [shell]. */
    @Synchronized
    fun openShell(command: String): InteractiveShell? {
        if (cancelled.get()) return null
        if (socket?.isClosed != false && connect(mayAsk = false) != Access.READY) return null
        return try {
            val local = nextStreamId++
            val boundary = shellPreamble?.let { VendorShellOutput() }
            val remoteCommand = boundary?.wrap(command) ?: command
            send(AdbPacket(AdbPacket.OPEN, local, 0, "shell:$remoteCommand\u0000".toByteArray()))
            val reply = receive()
            if (reply.command != AdbPacket.OKAY || reply.arg1 != local) throw IOException("interactive open rejected")
            val initialOutput = boundary?.let { sendShellPreamble(local, reply.arg0, it) } ?: ByteArray(0)
            InteractiveShell(local, reply.arg0, ::send, ::receive,
                { timeout -> socket?.soTimeout = timeout }, ::closeQuietly, initialOutput)
        } catch (_: IOException) {
            closeQuietly()
            null
        }
    }

    /** One request at a time, with strict stream ownership, byte/packet limits and a total deadline. */
    class InteractiveShell internal constructor(
        private val local: Int,
        private val remote: Int,
        private val sendPacket: (AdbPacket) -> Unit,
        private val readPacket: () -> AdbPacket,
        private val setTimeout: (Int) -> Unit,
        private val closeConnection: () -> Unit,
        private var initialOutput: ByteArray = ByteArray(0),
    ) : Closeable {
        @Volatile private var closed = false
        private companion object {
            val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-adb-stream-deadline").apply { isDaemon = true }
            }
        }
        @Synchronized
        fun exchangeBounded(request: String, timeoutMillis: Int = 5000): String? {
            if (closed || request.toByteArray(Charsets.UTF_8).size > 1024 || '\n' in request || '\r' in request) return null
            val boundedMillis = timeoutMillis.coerceIn(1, 10000)
            // Socket read timeout alone restarts per read; an independent deadline also bounds drips.
            val deadlineTask = deadlines.schedule({
                closed = true
                closeConnection()
            }, boundedMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            return try {
                val deadline = System.nanoTime() + boundedMillis * 1_000_000L
                sendPacket(AdbPacket(AdbPacket.WRTE, local, remote, "$request\n".toByteArray(Charsets.UTF_8)))
                var acknowledged = false
                val response = InteractiveResponseBuffer()
                response.accept(initialOutput)
                initialOutput = ByteArray(0)
                var packets = 0
                while (!acknowledged || !response.complete) {
                    if (++packets > 64) throw IOException("interactive packet limit")
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) throw SocketTimeoutException("interactive deadline")
                    setTimeout(remaining.toInt().coerceAtLeast(1))
                    val packet = readPacket()
                    if (packet.arg0 != remote || packet.arg1 != local) throw IOException("wrong interactive stream")
                    when (packet.command) {
                        AdbPacket.OKAY -> acknowledged = true
                        AdbPacket.WRTE -> {
                            sendPacket(AdbPacket(AdbPacket.OKAY, local, remote, ByteArray(0)))
                            response.accept(packet.payload)
                        }
                        AdbPacket.CLSE -> throw IOException("interactive stream ended")
                        else -> throw IOException("unexpected interactive packet")
                    }
                }
                response.text()
            } catch (_: IOException) {
                close()
                null
            } finally {
                deadlineTask.cancel(false)
            }
        }
        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { sendPacket(AdbPacket(AdbPacket.CLSE, local, remote, ByteArray(0))) }
            closeConnection()
        }
    }

    @Synchronized
    override fun close() = closeQuietly()

    /** Retires this client and unblocks pending I/O without waiting for its synchronized operation. */
    fun cancelPendingOperations() {
        cancelled.set(true)
        runCatching { socket?.close() }
    }

    private fun handshake(mayAsk: Boolean): Access {
        var packet = receive()
        if (packet.command == AdbPacket.STLS) return Access.UNSUPPORTED
        if (packet.command == AdbPacket.CNXN) return Access.READY
        if (packet.command != AdbPacket.AUTH || packet.arg0 != AdbPacket.AUTH_TOKEN) return Access.UNREACHABLE
        send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, AdbKeys.sign(packet.payload, key.private)))
        packet = receive()
        if (packet.command == AdbPacket.CNXN) return Access.READY
        if (!mayAsk) return Access.NOT_APPROVED
        // adbd did not know the key: offer it, which opens the approval dialog on the car's screen.
        send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLIC_KEY, 0, AdbKeys.publicKeyMessage(key.public)))
        return awaitApproval()
    }

    private fun awaitApproval(): Access {
        val pendingInput = input ?: throw IOException("not connected")
        val deadline = System.nanoTime() + APPROVAL_TIMEOUT_MS * 1_000_000L
        socket?.soTimeout = APPROVAL_RECHECK_MS
        try {
            while (System.nanoTime() < deadline) {
                // 保留超时前收到的半包，静默检查后仍可继续解析原连接的响应。
                pendingInput.mark(AdbPacket.MAX_PAYLOAD + 24)
                try {
                    val packet = receive()
                    return if (packet.command == AdbPacket.CNXN) Access.READY else Access.NOT_APPROVED
                } catch (_: SocketTimeoutException) {
                    pendingInput.reset()
                    // 部分车机保存了授权密钥，却不唤醒原连接；只用已保存的密钥检查，不再弹窗。
                    val approved = LocalAdb(key, host, port).use { it.connect(mayAsk = false) == Access.READY }
                    if (approved) {
                        closeQuietly()
                        return connect(mayAsk = false)
                    }
                }
            }
            return Access.NOT_APPROVED
        } finally {
            socket?.soTimeout = READ_TIMEOUT_MS
        }
    }

    private fun send(packet: AdbPacket) {
        val out = output ?: throw IOException("not connected")
        out.write(packet.encode())
        out.flush()
    }

    private fun receive(): AdbPacket = AdbPacket.read(input ?: throw IOException("not connected"))

    /** Prime a vendor shell before returning it to a caller that will exchange its own protocol. */
    private fun sendShellPreamble(local: Int, remote: Int, boundary: VendorShellOutput): ByteArray {
        val preamble = shellPreamble ?: return ByteArray(0)
        send(AdbPacket(AdbPacket.WRTE, local, remote, preamble.copyOf()))
        val output = java.io.ByteArrayOutputStream()
        val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
        var acknowledged = false
        var packets = 0
        while (!acknowledged || !boundary.started) {
            if (++packets > 64) throw IOException("shell validation packet limit")
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException("shell validation deadline")
            socket?.soTimeout = remaining.toInt().coerceAtLeast(1)
            val packet = receive()
            if (packet.arg0 != remote || packet.arg1 != local) throw IOException("wrong preamble stream")
            when (packet.command) {
                AdbPacket.OKAY -> acknowledged = true
                AdbPacket.WRTE -> {
                    output.write(boundary.accept(packet.payload))
                    send(AdbPacket(AdbPacket.OKAY, local, remote, ByteArray(0)))
                }
                AdbPacket.CLSE -> throw IOException("shell validation rejected")
                else -> throw IOException("unexpected shell validation packet")
            }
        }
        socket?.soTimeout = READ_TIMEOUT_MS
        return output.toByteArray()
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        nextStreamId = 1
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 5_000
        const val APPROVAL_TIMEOUT_MS = 60_000
        const val APPROVAL_RECHECK_MS = 1_000
        const val MAX_COMMAND_TIMEOUT_MS = 30_000
    }
}

/** The shell emits a unique boundary after vendor validation, independently of ADB flow-control ACKs. */
internal class VendorShellOutput {
    private val token = "DIPLAY_SHELL_${java.util.UUID.randomUUID()}"
    private val marker = "\u001e$token\u001f".toByteArray(Charsets.UTF_8)
    private var matched = 0
    var started = false
        private set

    fun wrap(command: String): String = "printf '\\036%s\\037' '$token'; {\n$command\n}"

    fun accept(payload: ByteArray): ByteArray {
        if (started) return payload
        payload.forEachIndexed { index, byte ->
            // The marker's first byte cannot occur anywhere else in the marker.
            matched = if (byte == marker[matched]) matched + 1 else if (byte == marker[0]) 1 else 0
            if (matched == marker.size) {
                started = true
                return payload.copyOfRange(index + 1, payload.size)
            }
        }
        return ByteArray(0)
    }
}

/** The Changan S202 vendor adbd validates the first stdin line of every shell command. */
internal object LocalAdbShellPolicy {
    fun currentPreamble(): ByteArray? = runCatching {
        if (ChanganS202HeadUnit.detected) "adb36987\n".toByteArray(Charsets.UTF_8) else null
    }.getOrNull()

    internal fun preamble(board: String?, hardware: String?): ByteArray? =
        if (ChanganS202HeadUnit.matches(board, hardware)) "adb36987\n".toByteArray(Charsets.UTF_8) else null
}

/** ADB payload boundaries need not coincide with UTF-8 characters or response lines. */
internal class InteractiveResponseBuffer {
    private val bytes = java.io.ByteArrayOutputStream()
    var complete = false
        private set
    fun accept(payload: ByteArray) {
        if (complete || bytes.size() + payload.size > 4096) throw IOException("interactive response limit")
        bytes.write(payload)
        val text = bytes.toString("UTF-8")
        if (text == "done\n" || text.endsWith("\ndone\n")) complete = true
    }
    fun text(): String = bytes.toString("UTF-8").removeSuffix("done\n").trimEnd('\n', '\r')
}
