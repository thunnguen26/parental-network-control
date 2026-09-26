package com.parental.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Integration Test cho Server TCP Engine: khởi động server thật trên dynamic
 * port (0), kết nối bằng TCP Socket client giả lập, kiểm tra luồng
 * HANDSHAKE → HEARTBEAT/ACK → disconnect cleanup.
 */
@Timeout(60)
class TcpServerEngineIntegrationTest {

    private TcpServerEngine engine;
    private JsonProtocolParser parser;

    @BeforeEach
    void setUp() throws Exception {
        parser = new JsonProtocolParser();
        engine = new TcpServerEngine(0, 8, 30); // port 0 = dynamic port
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // ------------------------------------------------------------------
    // Helpers: giả lập TCP client
    // ------------------------------------------------------------------

    /** TCP client giả lập: gửi HANDSHAKE rồi trả về socket + reader + writer. */
    private record FakeClient(Socket socket, BufferedReader reader, BufferedWriter writer) {
        void send(JsonProtocolParser p, BaseMessage<?> message) throws Exception {
            writer.write(p.toLine(message));
            writer.flush();
        }
    }

    private FakeClient connectAndHandshake(String clientId, String deviceName) throws Exception {
        Socket socket = new Socket("127.0.0.1", engine.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15_000); // chống treo vĩnh viễn khi đọc phản hồi
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        FakeClient client = new FakeClient(socket, reader, writer);
        client.send(parser, BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                deviceName, "DESKTOP", "Windows 11", "127.0.0.1", clientId)));
        return client;
    }

    private void awaitCondition(String description, Duration timeout, java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Timeout while waiting: " + description);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Server lắng nghe trên dynamic port (port 0)")
    void serverBindsToDynamicPort() {
        assertTrue(engine.isRunning());
        assertTrue(engine.getPort() > 0, "Port động phải > 0");
    }

    @Test
    @DisplayName("HANDSHAKE hợp lệ -> session đăng ký đúng clientId và deviceInfo")
    void handshakeRegistersSession() throws Exception {
        FakeClient client = connectAndHandshake("AA-BB-CC-DD-EE-01", "Laptop-ConTrai");

        awaitCondition("session được đăng ký", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("AA-BB-CC-DD-EE-01") != null);

        ClientSession session = engine.getSessionManager().get("AA-BB-CC-DD-EE-01");
        assertNotNull(session);
        assertEquals("AA-BB-CC-DD-EE-01", session.getClientId());
        assertNotNull(session.getDeviceInfo());
        assertEquals("Laptop-ConTrai", session.getDeviceInfo().deviceName());
        assertEquals(1, engine.getSessionManager().count());

        client.socket().close();
    }

    @Test
    @DisplayName("Gói đầu tiên không phải HANDSHAKE -> bị từ chối, không có session")
    void nonHandshakeFirstPacketIsRejected() throws Exception {
        Socket socket = new Socket("127.0.0.1", engine.getPort());
        socket.setSoTimeout(15_000);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(parser.toLine(BaseMessage.of(MessageType.HEARTBEAT, "rogue-client", java.util.Map.of())));
        writer.flush();

        awaitCondition("không đăng ký session rogue", Duration.ofSeconds(10),
                () -> engine.getSessionManager().count() == 0);

        assertNull(engine.getSessionManager().get("rogue-client"));
        assertEquals(0, engine.getSessionManager().count());
        socket.close();
    }

    @Test
    @DisplayName("HEARTBEAT -> server phản hồi ACK và cập nhật lastHeartbeatTimestamp")
    void heartbeatGetsAckAndUpdatesTimestamp() throws Exception {
        FakeClient client = connectAndHandshake("client-01", "PC-PhongKhach");

        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-01") != null);
        ClientSession session = engine.getSessionManager().get("client-01");
        long before = session.getLastHeartbeatTimestamp();

        client.send(parser, BaseMessage.of(MessageType.HEARTBEAT, "client-01", java.util.Map.of()));

        // Đọc phản hồi ACK từ server (dòng LDJSON)
        String ackLine = client.reader().readLine();
        assertNotNull(ackLine, "Server phải phản hồi ACK cho HEARTBEAT");
        BaseMessage<?> ack = parser.fromLine(ackLine);
        assertEquals(MessageType.ACK, ack.getType());

        awaitCondition("heartbeat timestamp cập nhật", Duration.ofSeconds(10),
                () -> session.getLastHeartbeatTimestamp() >= before);

        client.socket().close();
    }

    @Test
    @DisplayName("2 client kết nối đồng thời -> manager ghi nhận đủ 2 session")
    void twoClientsRegistered() throws Exception {
        FakeClient c1 = connectAndHandshake("client-01", "Laptop-ConTrai");
        FakeClient c2 = connectAndHandshake("client-02", "PC-PhongKhach");

        awaitCondition("2 session đăng ký", Duration.ofSeconds(10),
                () -> engine.getSessionManager().count() == 2);

        assertNotNull(engine.getSessionManager().get("client-01"));
        assertNotNull(engine.getSessionManager().get("client-02"));
        List<ClientSession> all = engine.getSessionManager().getAllSessions();
        assertEquals(2, all.size());

        c1.socket().close();
        c2.socket().close();
    }

    @Test
    @DisplayName("Client ngắt kết nối -> session tự động dọn dẹp sạch sẽ")
    void disconnectCleansUpSession() throws Exception {
        FakeClient client = connectAndHandshake("client-gone", "Laptop-ConTrai");

        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-gone") != null);
        assertEquals(1, engine.getSessionManager().count());

        // Ngắt kết nối đột ngột
        client.socket().close();

        awaitCondition("session bị dọn sau disconnect", Duration.ofSeconds(15),
                () -> engine.getSessionManager().count() == 0);

        assertNull(engine.getSessionManager().get("client-gone"));
        assertEquals(0, engine.getSessionManager().count());
    }

    @Test
    @DisplayName("Server đẩy lệnh chủ động xuống client qua session.send() (CommandPayload)")
    void serverPushesCommandToClient() throws Exception {
        FakeClient client = connectAndHandshake("client-ctrl", "Laptop-ConTrai");

        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-ctrl") != null);
        ClientSession session = engine.getSessionManager().get("client-ctrl");

        boolean sent = session.send(BaseMessage.of(MessageType.COMMAND, ClientHandler.SERVER_ID,
                new com.parental.common.protocol.payload.CommandPayload(
                        "cmd-001", com.parental.common.protocol.ActionType.PAUSE_NETWORK, "Het gio su dung")));
        assertTrue(sent, "send() phải thành công khi kết nối còn sống");

        String cmdLine = client.reader().readLine();
        assertNotNull(cmdLine, "Client phải nhận được COMMAND do server chủ động đẩy");
        BaseMessage<?> received = parser.fromLine(cmdLine);
        assertEquals(MessageType.COMMAND, received.getType());

        client.socket().close();
    }

    @Test
    @DisplayName("Sweep session quá hạn heartbeat -> bị gỡ khỏi manager")
    void sweepRemovesDeadSessions() throws Exception {
        FakeClient client = connectAndHandshake("client-dead", "Laptop-ConTrai");

        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-dead") != null);

        // Giả lập heartbeat cũ (quá hạn 30s) mà không cần chờ thật
        ClientSession session = engine.getSessionManager().get("client-dead");
        session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond() - 60);

        List<String> removed = engine.getSessionManager().sweepDeadSessions(30);
        assertTrue(removed.contains("client-dead"), "Session quá hạn phải bị sweep");
        assertEquals(0, engine.getSessionManager().count());
        assertTrue(session.isClosed(), "Session bị sweep phải được đóng");

        client.socket().close();
    }

    @Test
    @DisplayName("stop() graceful shutdown: server ngừng chạy, đóng sạch session và fire callback SERVER_SHUTDOWN")
    void stopShutsDownCleanly() throws Exception {
        List<String> disconnected = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<DisconnectReason> reasons = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine.getSessionManager().addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onClientDisconnected(String clientId, DisconnectReason reason) {
                disconnected.add(clientId);
                reasons.add(reason);
            }
        });

        FakeClient client = connectAndHandshake("client-x", "Laptop-ConTrai");
        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-x") != null);

        engine.stop();

        assertFalse(engine.isRunning());
        assertEquals(0, engine.getSessionManager().count());
        assertTrue(engine.getSessionManager().get("client-x") == null);

        // Task 2.3: stop() phải fire onClientDisconnected với reason SERVER_SHUTDOWN
        awaitCondition("callback SERVER_SHUTDOWN", Duration.ofSeconds(5),
                () -> !disconnected.isEmpty());
        assertTrue(disconnected.contains("client-x"));
        assertTrue(reasons.stream().anyMatch(r -> r == DisconnectReason.SERVER_SHUTDOWN));
        client.socket().close();
    }

    @Test
    @DisplayName("Sweeper định kỳ: client im lặng quá hạn -> tự động bị gỡ kèm callback HEARTBEAT_TIMEOUT")
    void periodicSweeperRemovesSilentClientWithCallback() throws Exception {
        // Engine sweep mỗi 1s với timeout 2s (constructor overload: port, pool, timeoutSeconds)
        TcpServerEngine shortEngine = new TcpServerEngine(0, 8, 2);
        List<String> disconnected = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<DisconnectReason> reasons = new java.util.concurrent.CopyOnWriteArrayList<>();
        shortEngine.getSessionManager().addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onClientDisconnected(String clientId, DisconnectReason reason) {
                disconnected.add(clientId);
                reasons.add(reason);
            }
        });
        shortEngine.start();
        try {
            Socket socket = new Socket("127.0.0.1", shortEngine.getPort());
            socket.setTcpNoDelay(true);
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, "silent-client",
                    new HandshakePayload("PC-Im-Lang", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-99"))));
            writer.flush();

            awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                    () -> shortEngine.getSessionManager().get("silent-client") != null);
            assertEquals(1, shortEngine.getSessionManager().count());

            // Client im lặng hoàn toàn (không gửi gì nữa) — sweeper (timeout 2s) phải gỡ.
            awaitCondition("sweeper tự gỡ client im lặng", Duration.ofSeconds(15),
                    () -> shortEngine.getSessionManager().count() == 0);

            // Callback fire trên sweeper thread — chờ có timeout, không assert tức thời (race).
            awaitCondition("Sweeper phải fire onClientDisconnected", Duration.ofSeconds(5),
                    () -> disconnected.contains("silent-client"));
            awaitCondition("Reason phải là HEARTBEAT_TIMEOUT", Duration.ofSeconds(5),
                    () -> reasons.stream().anyMatch(r -> r == DisconnectReason.HEARTBEAT_TIMEOUT));
            socket.close();
        } finally {
            shortEngine.stop();
        }
    }

    @Test
    @DisplayName("Listener nhận onClientConnected khi client handshake thành công")
    void connectedListenerFiresOnHandshake() throws Exception {
        List<String> connected = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine.getSessionManager().addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onClientConnected(ClientSession session) {
                connected.add(session.getClientId());
            }
        });

        FakeClient client = connectAndHandshake("client-cb", "Laptop-ConTrai");
        awaitCondition("callback connected", Duration.ofSeconds(10), () -> !connected.isEmpty());
        assertTrue(connected.contains("client-cb"));
        client.socket().close();
    }
}
