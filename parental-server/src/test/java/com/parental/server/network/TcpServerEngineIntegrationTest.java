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
    @DisplayName("stop() graceful shutdown: server ngừng chạy và đóng sạch session")
    void stopShutsDownCleanly() throws Exception {
        FakeClient client = connectAndHandshake("client-x", "Laptop-ConTrai");
        awaitCondition("session sẵn sàng", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get("client-x") != null);

        engine.stop();

        assertFalse(engine.isRunning());
        assertEquals(0, engine.getSessionManager().count());
        assertTrue(engine.getSessionManager().get("client-x") == null);
        client.socket().close();
    }
}
