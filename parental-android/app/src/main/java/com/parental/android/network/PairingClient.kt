package com.parental.android.network

import com.parental.android.protocol.LdjsonProtocol
import com.parental.android.protocol.MessageType
import com.parental.android.protocol.PairRequestPayload
import com.parental.android.protocol.PairResponsePayload
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Kết quả ghép đôi — success + token + thông điệp từ server. */
data class PairingResult(
    val success: Boolean,
    val deviceToken: String?,
    val message: String
)

/**
 * Ghép đôi one-shot (Mục 3.6/3.7): mở TCP → gửi PAIR_REQUEST (PIN 6 số +
 * thông tin máy, clientId = UUID bền vững) → chờ PAIR_RESPONSE (soTimeout
 * 10s) → đóng socket. KHÔNG giữ phiên — client chuyển sang engine HANDSHAKE
 * kèm token. Khớp hành vi `PairingClient` desktop; pure Kotlin (test JVM).
 */
class PairingClient(
    private val host: String,
    private val port: Int,
    private val protocol: LdjsonProtocol = LdjsonProtocol.DEFAULT
) {

    companion object {
        const val SO_TIMEOUT_MS = 10_000
        const val CONNECT_TIMEOUT_MS = 8_000
    }

    /**
     * Thực hiện ghép đôi. Ném IOException khi không kết nối được server —
     * caller (ViewModel) biến thành thông báo lỗi UI.
     */
    fun pair(request: PairRequestPayload, clientId: String): PairingResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = SO_TIMEOUT_MS

            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            writer.write(protocol.toLine(MessageType.PAIR_REQUEST, clientId, request))
            writer.flush()

            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            )
            val line = reader.readLine() ?: return PairingResult(false, null, "Server closed connection")
            val parsed = protocol.fromLine(line)
            if (parsed.type != MessageType.PAIR_RESPONSE) {
                return PairingResult(false, null, "Unexpected response: ${parsed.type}")
            }
            val payload = parsed.payload as? PairResponsePayload
                ?: return PairingResult(false, null, "Malformed PAIR_RESPONSE payload")
            return PairingResult(payload.success, payload.deviceToken, payload.message)
        }
    }
}
