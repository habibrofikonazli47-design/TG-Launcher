package com.israadev.nuxlauncher.core.social

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges Minecraft's local TCP connection to reliable LiveKit data packets.
 *
 * Guest: Minecraft connects to a loopback ServerSocket opened by this class.
 * Host: each remote OPEN creates a loopback TCP connection to Minecraft's
 * integrated-server LAN port detected by GameActivity.
 *
 * The class is intentionally transport-agnostic: its send callback is wired to
 * NuxWorldTransportManager.sendPacket(), and incoming packets are passed to
 * onPacket(). It does not open public listening ports.
 */
class NuxWorldTcpBridge(
    private val sendPacket: (ByteArray, String?) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "NuxWorldTcpBridge"
        private const val TOPIC_VERSION = 1
        private const val MAX_CHUNK_BYTES = 4 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
    }

    private data class Connection(
        val socket: Socket,
        val output: BufferedOutputStream,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "TGWorld-TCP").apply { isDaemon = true }
    }
    // Preserve TCP byte order: LiveKit packets must be handled sequentially per room.
    private val packetExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TGWorld-Packets").apply { isDaemon = true }
    }
    private val connections = ConcurrentHashMap<String, Connection>()
    private val closed = AtomicBoolean(false)
    @Volatile private var mode: Mode = Mode.NONE
    @Volatile private var hostLanPort: Int = 0
    @Volatile private var guestServer: ServerSocket? = null
    @Volatile private var hostIdentity: String? = null

    private enum class Mode { NONE, HOST, GUEST }

    /** Start a loopback-only listener for the guest's Minecraft client. */
    @Synchronized
    fun startGuest(): Int {
        check(!closed.get()) { "TCP bridge is closed" }
        stopSockets()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = SOCKET_TIMEOUT_MS
        guestServer = server
        mode = Mode.GUEST
        executor.execute {
            while (!closed.get() && mode == Mode.GUEST && !server.isClosed) {
                try {
                    val socket = server.accept().apply {
                        tcpNoDelay = true
                        keepAlive = true
                    }
                    val id = UUID.randomUUID().toString()
                    val connection = Connection(socket, BufferedOutputStream(socket.getOutputStream()))
                    connections[id] = connection
                    sendControl("OPEN", id, null)
                    executor.execute { pumpGuestToHost(id, connection) }
                } catch (_: java.net.SocketTimeoutException) {
                    // Re-check lifecycle flags.
                } catch (error: SocketException) {
                    if (!server.isClosed) Log.w(TAG, "Guest listener socket error", error)
                    break
                } catch (error: Throwable) {
                    if (!closed.get()) Log.w(TAG, "Guest accept failed", error)
                }
            }
        }
        return server.localPort
    }

    /** Start host forwarding to Minecraft's integrated server LAN port. */
    @Synchronized
    fun startHost(lanPort: Int) {
        require(lanPort in 1..65535) { "Invalid Minecraft LAN port" }
        check(!closed.get()) { "TCP bridge is closed" }
        stopSockets()
        hostLanPort = lanPort
        mode = Mode.HOST
    }

    /** Deliver one LiveKit data packet, including the authenticated sender identity. */
    fun onPacket(payload: ByteArray, senderIdentity: String) {
        if (closed.get() || payload.isEmpty() || payload.size > 16 * 1024) return
        packetExecutor.execute {
            try {
                val message = JSONObject(payload.toString(Charsets.UTF_8))
                if (message.optInt("v") != TOPIC_VERSION) return@execute
                val type = message.optString("type")
                val id = message.optString("id")
                if (id.isBlank() || id.length > 64) return@execute

                when (mode) {
                    Mode.HOST -> handleHostPacket(type, id, message, senderIdentity)
                    Mode.GUEST -> handleGuestPacket(type, id, message, senderIdentity)
                    Mode.NONE -> Unit
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Ignoring invalid TCP bridge packet", error)
            }
        }
    }

    private fun handleHostPacket(type: String, id: String, message: JSONObject, sender: String) {
        val key = "$sender:$id"
        when (type) {
            "OPEN" -> {
                if (connections.containsKey(key)) return
                try {
                    val socket = Socket()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", hostLanPort), 5_000)
                    val connection = Connection(socket, BufferedOutputStream(socket.getOutputStream()))
                    connections[key] = connection
                    executor.execute { pumpHostToGuest(key, id, sender, connection) }
                } catch (error: Throwable) {
                    sendControl("ERROR", id, sender, error.message ?: "Host connection failed")
                    Log.w(TAG, "Cannot connect to Minecraft LAN port $hostLanPort", error)
                }
            }
            "DATA" -> {
                val connection = connections[key] ?: return
                val bytes = decodeData(message) ?: return
                synchronized(connection.output) {
                    if (!connection.closed.get()) {
                        connection.output.write(bytes)
                        connection.output.flush()
                    }
                }
            }
            "CLOSE" -> closeConnection(key)
        }
    }

    private fun handleGuestPacket(type: String, id: String, message: JSONObject, sender: String) {
        if (hostIdentity == null && type in setOf("DATA", "CLOSE", "ERROR")) hostIdentity = sender
        if (hostIdentity != null && hostIdentity != sender) return
        val connection = connections[id] ?: return
        when (type) {
            "DATA" -> {
                val bytes = decodeData(message) ?: return
                synchronized(connection.output) {
                    if (!connection.closed.get()) {
                        connection.output.write(bytes)
                        connection.output.flush()
                    }
                }
            }
            "CLOSE", "ERROR" -> closeConnection(id)
        }
    }

    private fun pumpGuestToHost(id: String, connection: Connection) {
        pumpSocket(id, connection) { bytes -> sendData(id, bytes, null) }
    }

    private fun pumpHostToGuest(key: String, id: String, target: String, connection: Connection) {
        pumpSocket(key, connection) { bytes -> sendData(id, bytes, target) }
    }

    private fun pumpSocket(key: String, connection: Connection, emit: (ByteArray) -> Unit) {
        try {
            val input = BufferedInputStream(connection.socket.getInputStream())
            val buffer = ByteArray(MAX_CHUNK_BYTES)
            while (!closed.get() && !connection.closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                emit(buffer.copyOf(count))
            }
        } catch (error: EOFException) {
            // Normal peer shutdown.
        } catch (error: SocketException) {
            if (!connection.closed.get()) Log.d(TAG, "TCP peer disconnected: ${error.message}")
        } catch (error: Throwable) {
            if (!connection.closed.get()) Log.w(TAG, "TCP forwarding stopped", error)
        } finally {
            val id = key.substringAfterLast(':')
            closeConnection(key)
            if (mode == Mode.GUEST) sendControl("CLOSE", id, null)
            else if (mode == Mode.HOST) {
                val target = key.substringBeforeLast(':')
                sendControl("CLOSE", id, target)
            }
        }
    }

    private fun sendData(id: String, bytes: ByteArray, target: String?) {
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val packet = JSONObject()
            .put("v", TOPIC_VERSION)
            .put("type", "DATA")
            .put("id", id)
            .put("data", encoded)
            .toString()
            .toByteArray(Charsets.UTF_8)
        sendPacket(packet, target)
    }

    private fun sendControl(type: String, id: String, target: String?, reason: String? = null) {
        val json = JSONObject()
            .put("v", TOPIC_VERSION)
            .put("type", type)
            .put("id", id)
        if (!reason.isNullOrBlank()) json.put("reason", reason.take(160))
        sendPacket(json.toString().toByteArray(Charsets.UTF_8), target)
    }

    private fun decodeData(message: JSONObject): ByteArray? {
        val encoded = message.optString("data")
        if (encoded.isBlank() || encoded.length > 12 * 1024) return null
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                .takeIf { it.isNotEmpty() && it.size <= MAX_CHUNK_BYTES }
        }.getOrNull()
    }

    private fun closeConnection(key: String) {
        val connection = connections.remove(key) ?: return
        if (!connection.closed.compareAndSet(false, true)) return
        runCatching { connection.socket.close() }
    }

    @Synchronized
    private fun stopSockets() {
        runCatching { guestServer?.close() }
        guestServer = null
        connections.keys.toList().forEach(::closeConnection)
        hostIdentity = null
        hostLanPort = 0
        mode = Mode.NONE
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopSockets()
        packetExecutor.shutdownNow()
        executor.shutdownNow()
    }
}
