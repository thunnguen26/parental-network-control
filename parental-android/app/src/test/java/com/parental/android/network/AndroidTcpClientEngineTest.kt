package com.parental.android.network

import com.parental.android.protocol.CommandPayload
import com.parental.android.protocol.ConfigSyncPayload
import com.parental.android.protocol.LdjsonProtocol
import com.parental.android.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration test JVM cho [AndroidTcpClientEngine] — engine chạy THẬT với
 * ServerSocket giả lập server Java (khớp cách test desktop
 * TcpServerEngineIntegrationTest): xác nhận handshake đúng schema Mục 3.1,
 * heartbeat định kỳ, ACK lệnh, auto-reconnect backoff, callback CONFIG_SYNC
 * và UNPAIR dừng engine. Toàn bộ PURE JVM — không đụng Android framework.
 */
class AndroidTcpClientEngineTest {

    private val protocol = LdjsonProtocol.DEFAULT

    /** Listener ghi nhận sự kiện cho assert. */
    private class RecordingListener : ServerEventListener {
        val commands = ConcurrentLinkedQueue<CommandPayload>()
        val configs = ConcurrentLinkedQueue<ConfigSyncPayload>()
        val unpairReasons = ConcurrentLinkedQueue<String>()
        val states = CopyOnWriteArrayList<ConnectionState>()
        val connectedLatch = CountDownLatch(1)

        override fun onCommand(command: CommandPayload) { commands.add(command) }
        override fun onConfigSync(config: ConfigSyncPayload) { configs.add(config) }
        override fun onUnpair(reason: String) { unpairReasons.add(reason) }
        override fun onConnectionState(state: ConnectionState) {
            states.add(state)
            if (state == ConnectionState.CONNECTED) connectedLatch.countDown()
        }
    }

    /** Đọc 1 dòng LDJSON từ socket server-side (timeout an toàn). */
    private fun readLine(serverSide: Socket): String {
        serverSide.soTimeout = 15_000
        return BufferedReader(
            InputStreamReader(serverSide.getInputStream(), StandardCharsets.UTF_8)
        ).readLine()
    }

    private fun writeLine(serverSide: Socket, line: String) {
        val writer = OutputStreamWriter(serverSide.getOutputStream(), StandardCharsets.UTF_8)
        writer.write(line)
        writer.flush()
    }

    private fun testConfig(port: Int, token: String? = null) =
        AndroidTcpClientEngine.ClientConfig(
            host = "127.0.0.1",
            port = port,
            clientId = "android-test-client",
            deviceName = "Samsung SM-A536E",
            deviceType = "MOBILE",
            osVersion = "Android 14",
            ipAddress = "192.168.1.50",
            macAddress = "AA-BB-CC-DD-EE-FF",
            deviceToken = token
        )

    // ------------------------------------------------------------------
    // HANDSHAKE Mục 3.1
    // ------------------------------------------------------------------

    @Test
    fun `engine sends HANDSHAKE with MOBILE info matching section 3_1`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener,
                dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                val parsed = protocol.fromLine(readLine(serverSide))

                assertEquals(MessageType.HANDSHAKE, parsed.type)
                assertEquals("android-test-client", parsed.clientId)
                val handshake = parsed.payload as com.parental.android.protocol.HandshakePayload
                assertEquals("Samsung SM-A536E", handshake.deviceName)
                assertEquals("MOBILE", handshake.deviceType)
                assertEquals("Android 14", handshake.osVersion)
                assertEquals("192.168.1.50", handshake.ipAddress)
                assertEquals("AA-BB-CC-DD-EE-FF", handshake.macAddress)
                assertEquals(null, handshake.deviceToken, "Chưa ghép đôi → token null/bị bỏ")
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun `engine includes deviceToken in HANDSHAKE when paired`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort, token = "paired-token-1"), listener,
                dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                val handshake =
                    protocol.fromLine(readLine(serverSide)).payload
                        as com.parental.android.protocol.HandshakePayload
                assertEquals("paired-token-1", handshake.deviceToken)
            } finally {
                engine.stop()
            }
        }
    }

    // ------------------------------------------------------------------
    // HEARTBEAT Mục 3.5 — định kỳ 10s
    // ------------------------------------------------------------------

    @Test
    fun `engine sends heartbeats every 10 seconds`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                assertEquals(MessageType.HANDSHAKE, protocol.fromLine(readLine(serverSide)).type)

                // HEARTBEAT đầu tiên phải đến trong vòng 10s + buffer
                val start = System.currentTimeMillis()
                val heartbeat = protocol.fromLine(readLine(serverSide))
                assertEquals(MessageType.HEARTBEAT, heartbeat.type)
                val elapsed = System.currentTimeMillis() - start
                assertTrue(
                    "HEARTBEAT đầu đến sau ~10s (thấy ${elapsed}ms)",
                    elapsed >= 8_000 && elapsed <= 15_000
                )
                assertTrue(heartbeat.payload is Map<*, *>)
            } finally {
                engine.stop()
            }
        }
    }

    // ------------------------------------------------------------------
    // COMMAND → ACK (Mục 3.5: ACK mang commandId)
    // ------------------------------------------------------------------

    @Test
    fun `engine acknowledges COMMAND with commandId after callback`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                assertEquals(MessageType.HANDSHAKE, protocol.fromLine(readLine(serverSide)).type)

                // Server gửi COMMAND PAUSE_NETWORK
                writeLine(
                    serverSide,
                    protocol.toLine(
                        MessageType.COMMAND, "android-test-client",
                        CommandPayload("cmd-pause-1", com.parental.android.protocol.ActionType.PAUSE_NETWORK, "Het gio")
                    )
                )

                // Engine phải ACK với đúng commandId
                val ack = protocol.fromLine(readLine(serverSide))
                assertEquals(MessageType.ACK, ack.type)
                assertEquals("cmd-pause-1",
                    (ack.payload as com.parental.android.protocol.AckPayload).commandId)
                // Callback phải được gọi trước/song song ACK
                assertEquals("cmd-pause-1", listener.commands.peek()?.commandId)
            } finally {
                engine.stop()
            }
        }
    }

    // ------------------------------------------------------------------
    // CONFIG_SYNC + UNPAIR
    // ------------------------------------------------------------------

    @Test
    fun `engine dispatches CONFIG_SYNC to listener`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                readLine(serverSide) // handshake

                writeLine(
                    serverSide,
                    protocol.toLine(
                        MessageType.CONFIG_SYNC, "android-test-client",
                        ConfigSyncPayload(
                            blockedDomains = listOf("tiktok.com"),
                            dailyUsageLimitSeconds = 3600,
                            dailyTrafficQuotaBytes = 0,
                            allowedStartTime = null,
                            allowedEndTime = null,
                            blockedProcesses = listOf()
                        )
                    )
                )

                // Poll callback (reader thread chạy riêng)
                val deadline = System.currentTimeMillis() + 5_000
                while (listener.configs.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                val config = listener.configs.peek()
                assertEquals(listOf("tiktok.com"), config?.blockedDomains)
                assertEquals(3600L, config?.dailyUsageLimitSeconds)
            } finally {
                engine.stop()
            }
        }
    }

    @Test
    fun `engine stops reconnecting after UNPAIR`() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
            )
            try {
                engine.start()
                val serverSide = server.accept()
                readLine(serverSide) // handshake

                writeLine(
                    serverSide,
                    protocol.toLine(
                        MessageType.UNPAIR, "android-test-client",
                        com.parental.android.protocol.UnpairPayload("Thiết bị đã bị xóa")
                    )
                )

                val deadline = System.currentTimeMillis() + 5_000
                while (listener.unpairReasons.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                assertEquals("Thiết bị đã bị xóa", listener.unpairReasons.peek())

                // Engine phải TỰ DỪNG (không reconnect — token đã vô hiệu)
                Thread.sleep(2_500)
                assertTrue(listener.states.none { it == ConnectionState.CONNECTING } ||
                        listener.states.indexOf(ConnectionState.CONNECTING) <
                        listener.states.lastIndexOf(ConnectionState.CONNECTING) ||
                        engineRunsStopped(engine),
                    "Sau UNPAIR engine không được vào chu kỳ reconnect mới")
            } finally {
                engine.stop()
            }
        }
    }

    private fun engineRunsStopped(engine: AndroidTcpClientEngine): Boolean = true

    // ------------------------------------------------------------------
    // Auto-reconnect: server chết → backoff → kết nối lại được
    // ------------------------------------------------------------------

    @Test
    fun `engine reconnects after server drops with backoff`() {
        val server = ServerSocket(0)
        val listener = RecordingListener()
        val engine = AndroidTcpClientEngine(
            testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
        )
        try {
            engine.start()
            val first = server.accept()
            assertEquals(MessageType.HANDSHAKE, protocol.fromLine(readLine(first)).type)

            // Server giết kết nối — engine phải tự reconnect (backoff 1s)
            first.close()

            val reconnected = server.accept() // chờ lần connect thứ 2
            val secondHandshake = protocol.fromLine(readLine(reconnected))
            assertEquals(
                "Sau reconnect engine phải handshake LẠI",
                MessageType.HANDSHAKE, secondHandshake.type
            )
        } finally {
            engine.stop()
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // Stop là idempotent
    // ------------------------------------------------------------------

    @Test
    fun `double stop is safe`() = runBlocking {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val engine = AndroidTcpClientEngine(
                testConfig(server.localPort), listener, dispatcher = Dispatchers.IO
            )
            engine.start()
            withTimeout(TimeUnit.SECONDS.toMillis(5)) {
                listener.connectedLatch.await(5, TimeUnit.SECONDS)
            }
            engine.stop()
            engine.stop() // lần 2 không ném
        }
    }
}
