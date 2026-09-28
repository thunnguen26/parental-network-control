package com.parental.android.network

import com.parental.android.protocol.AckPayload
import com.parental.android.protocol.CommandPayload
import com.parental.android.protocol.ConfigSyncPayload
import com.parental.android.protocol.HandshakePayload
import com.parental.android.protocol.LdjsonProtocol
import com.parental.android.protocol.MessageType
import com.parental.android.protocol.UnpairPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Trạng thái kết nối cho UI (Status Indicator 3 mức). */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Callback sự kiện server — KHỚP ClientCommandHandler Java:
 * - [onCommand]: server gửi COMMAND (pause/resume/alert/block_process).
 * - [onConfigSync]: server đẩy cấu hình chính sách (blockedDomains/Processes...).
 * - [onUnpair]: phụ huynh xóa thiết bị — client quên token, quay về ghép đôi.
 * - [onConnectionState]: thay đổi trạng thái (UI + Foreground Service).
 */
interface ServerEventListener {
    fun onCommand(command: CommandPayload)
    fun onConfigSync(config: ConfigSyncPayload)
    fun onUnpair(reason: String)
    fun onConnectionState(state: ConnectionState)
    /** Nhận PAIR_RESPONSE (lần ghép đôi đầu); null token = bị từ chối. */
    fun onPairResponse(success: Boolean, deviceToken: String?, message: String) {}
}

/**
 * TCP Client Engine Android (Mở rộng 13) — PURE KOTLIN, KHÔNG import Android
 * framework (unit test JVM được). Đóng gói toàn bộ vòng đời kết nối:
 *
 * - HANDSHAKE ngay khi TCP mở (kèm deviceToken nếu đã ghép đôi).
 * - HEARTBEAT mỗi 10s giữ phiên (server watchdog 15s × 3 nhịp).
 * - Auto-reconnect exponential backoff 1s → 2s → 4s → ... → 30s (cap).
 * - Reader thread nhận COMMAND / CONFIG_SYNC / UNPAIR / PAIR_RESPONSE,
 *   ACK lệnh sau khi callback xử lý xong (khớp hành vi desktop).
 *
 * <p>ĐÓNG GÓI TOÀN BỘ socket IO trong coroutine IO — UI không bao giờ đụng
 * blocking read; mọi state dùng Atomic/volatile (AGENTS.md Concurrency).</p>
 */
class AndroidTcpClientEngine(
    private val config: ClientConfig,
    private val listener: ServerEventListener,
    private val protocol: LdjsonProtocol = LdjsonProtocol.DEFAULT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam: thay vì tạo Socket thật, factory trả socket (dùng cho unit test). */
    private val socketFactory: (host: String, port: Int) -> Socket = { host, port ->
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    }
) {

    companion object {
        /** Chu kỳ HEARTBEAT (ms) — spec yêu cầu 10–15s, chọn 10s khớp desktop. */
        const val HEARTBEAT_INTERVAL_MS = 10_000L
        /** Timeout mỗi lần thử mở TCP (ms). */
        const val CONNECT_TIMEOUT_MS = 8_000
        /** Backoff đầu tiên sau lần mất kết nối (ms). */
        const val RECONNECT_INITIAL_BACKOFF_MS = 1_000L
        /** Cap backoff — khớp desktop TcpClientEngine (1s→30s). */
        const val RECONNECT_MAX_BACKOFF_MS = 30_000L
    }

    /** Cấu hình kết nối (host/port/định danh thiết bị). */
    data class ClientConfig(
        val host: String,
        val port: Int,
        val clientId: String,
        val deviceName: String,
        val deviceType: String = "MOBILE",
        val osVersion: String,
        val ipAddress: String,
        val macAddress: String,
        val deviceToken: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val reconnectAttempt = AtomicReference(0)

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var heartbeatJob: Job? = null

    /** writeLock — serialize ghi từ heartbeat/command-ack trên cùng socket. */
    private val writeLock = Any()

    // ------------------------------------------------------------------
    // Vòng đời
    // ------------------------------------------------------------------

    /** Bắt đầu engine: vòng lặp connect→handshake→heartbeat→read. */
    fun start() {
        if (!running.compareAndSet(false, true)) return // idempotent
        scope.launch { connectLoop() }
    }

    /** Ngắt kết nối + dừng engine (nút "Ngắt kết nối" hoặc Service bị hủy). */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeSocketQuietly()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connectLoop() {
        while (running.get()) {
            try {
                listener.onConnectionState(ConnectionState.CONNECTING)
                val newSocket = socketFactory(config.host, config.port)
                socket = newSocket
                writer = OutputStreamWriter(
                    newSocket.getOutputStream(), StandardCharsets.UTF_8
                )
                connected.set(true)
                reconnectAttempt.set(0)
                listener.onConnectionState(ConnectionState.CONNECTED)

                sendHandshake(newSocket)
                startHeartbeat()
                readLoop(newSocket) // block tới khi server ngắt / stop()
            } catch (e: Exception) {
                if (running.get()) {
                    // Lỗi kết nối/đọc — rơi xuống reconnect bên dưới
                }
            } finally {
                connected.set(false)
                heartbeatJob?.cancel()
                heartbeatJob = null
                closeSocketQuietly()
            }

            if (!running.get()) break

            // Exponential backoff: 1s, 2s, 4s, ... cap 30s
            val attempt = reconnectAttempt.incrementAndGet()
            val backoff = minOf(
                RECONNECT_INITIAL_BACKOFF_MS shl (attempt - 1).coerceAtMost(10),
                RECONNECT_MAX_BACKOFF_MS
            )
            listener.onConnectionState(ConnectionState.DISCONNECTED)
            delay(backoff)
        }
    }

    private suspend fun sendHandshake(sock: Socket) {
        val handshake = HandshakePayload(
            deviceName = config.deviceName,
            deviceType = config.deviceType, // "MOBILE" — server hiển thị dòng MOBILE
            osVersion = config.osVersion,
            ipAddress = config.ipAddress,
            macAddress = config.macAddress,
            deviceToken = config.deviceToken // null = CHƯA ghép đôi (lenient Java parse)
        )
        val line = protocol.toLine(MessageType.HANDSHAKE, config.clientId, handshake)
        writeTo(sock, line)
    }

    /** HEARTBEAT định kỳ — payload {} (Mục 3.5). */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && running.get() && connected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!connected.get() || !running.get()) break
                try {
                    socket?.let { writeTo(it, protocol.toLine(MessageType.HEARTBEAT, config.clientId, emptyMap<String, Any>())) }
                } catch (e: Exception) {
                    break // ghi lỗi → reader cũng sắp chết → reconnect
                }
            }
        }
    }

    /** Blocking đọc từng dòng LDJSON — dispatch theo type (chỉ các gói server→client). */
    private fun readLoop(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
        while (running.get() && !sock.isClosed) {
            val line = reader.readLine() ?: break // EOF — server đóng
            if (line.isBlank()) continue
            handleInbound(line)
        }
    }

    private fun handleInbound(line: String) {
        val parsed = try {
            protocol.fromLine(line)
        } catch (e: ProtocolParseException) {
            return // dòng lạ — bỏ qua, không chết reader (khớp defensive desktop)
        }
        when (parsed.type) {
            MessageType.COMMAND -> {
                val command = parsed.payload as? CommandPayload ?: return
                listener.onCommand(command)
                ackCommand(command.commandId) // ACK sau khi xử lý xong
            }

            MessageType.CONFIG_SYNC -> {
                (parsed.payload as? ConfigSyncPayload)?.let { listener.onConfigSync(it) }
            }

            MessageType.UNPAIR -> {
                val reason = (parsed.payload as? UnpairPayload)?.reason
                    ?: UnpairPayload.DEFAULT_REASON
                listener.onUnpair(reason)
                // Server sẽ đóng socket ngay sau UNPAIR — không reconnect (quên token trước)
                running.set(false)
            }

            MessageType.PAIR_RESPONSE -> {
                (parsed.payload as? PairResponsePayload)?.let {
                    listener.onPairResponse(it.success, it.deviceToken, it.message)
                }
            }

            else -> Unit // HEARTBEAT/ACK: bỏ qua (desktop cũng không xử lý ACK từ server)
        }
    }

    private fun PairResponsePayload?.let(f: (PairResponsePayload) -> Unit) {
        if (this != null) f(this)
    }

    /** ACK một lệnh COMMAND (Mục 3.5: payload {commandId}). */
    private fun ackCommand(commandId: String) {
        try {
            socket?.let {
                writeTo(it, protocol.toLine(MessageType.ACK, config.clientId, AckPayload(commandId)))
            }
        } catch (_: Exception) {
            // ACK best-effort — lỗi ghi không crash
        }
    }

    /** Ghi 1 dòng LDJSON + flush (thread-safe qua writeLock). */
    private fun writeTo(sock: Socket, line: String) {
        synchronized(writeLock) {
            val out = writer ?: throw IllegalStateException("Writer not ready")
            out.write(line)
            out.flush()
        }
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
            writer = null
        }
    }
}
