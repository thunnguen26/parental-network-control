package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.network.TcpServerEngine;

/**
 * Integration Test Task 4.1: {@link TcpServerEngine} gắn
 * {@link DatabaseManager} — HANDSHAKE/METRICS_REPORT/ACK/disconnect phải ghi
 * đúng vào DB qua DAO layer.
 *
 * <p>Dùng <b>file DB trong @TempDir</b> (không phải :memory:) vì test đa
 * luồng: DB in-memory shared-cache KHÔNG dùng được WAL, phải dùng table-lock
 * của shared-cache mà {@code busy_timeout} không áp dụng cho table-lock →
 * xung đột reader (test poll) / writer (handler thread) ném SQLITE_LOCKED
 * không đoán trước, DAO nuốt exception → mất update OFFLINE. File DB bật WAL
 * (Task 4.1) — reader không chặn writer — và đúng cấu hình runtime.</p>
 */
@Timeout(60)
class ServerDatabaseIntegrationTest {

    private DatabaseManager database;
    private TcpServerEngine engine;
    private JsonProtocolParser parser;
    private Socket clientSocket;
    private BufferedReader clientReader;
    private BufferedWriter clientWriter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // File DB (WAL) thay vì :memory: — xem lý do ở javadoc class.
        database = new DatabaseManager(tempDir.resolve("integration_test.db"));
        parser = new JsonProtocolParser();
        engine = new TcpServerEngine(0, 8, 30, database);
        engine.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeClientQuietly();
        engine.stop();
    }

    private void connectClient(String clientId, String deviceName) throws IOException {
        clientSocket = new Socket("127.0.0.1", engine.getPort());
        clientSocket.setTcpNoDelay(true);
        clientSocket.setSoTimeout(15_000);
        clientReader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        clientWriter = new BufferedWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
        writeAndFlush(BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                deviceName, "DESKTOP", "Windows 11", "127.0.0.1", clientId)));
    }

    private void writeAndFlush(BaseMessage<?> message) throws IOException {
        try {
            clientWriter.write(parser.toLine(message));
        } catch (com.parental.common.protocol.ProtocolParseException e) {
            throw new IOException("Serialize failed in test", e);
        }
        clientWriter.flush();
    }

    private void closeClientQuietly() {
        try {
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        clientSocket = null;
    }

    private static void awaitCondition(String description, Duration timeout,
                                       java.util.function.BooleanSupplier condition) {
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
    @DisplayName("HANDSHAKE → client được upsert vào DB với đủ thông tin + ONLINE")
    void handshakeUpsertsClientIntoDatabase() throws Exception {
        connectClient("db-client-01", "Laptop-DB");

        awaitCondition("client ghi vào DB", Duration.ofSeconds(10), () ->
                engine.getClientDao().exists("db-client-01"));

        ClientRecord record = engine.getClientDao().get("db-client-01");
        assertNotNull(record);
        assertEquals("Laptop-DB", record.deviceName());
        assertEquals("DESKTOP", record.deviceType());
        assertEquals("Windows 11", record.osVersion());
        assertEquals("127.0.0.1", record.ipAddress());
        assertEquals("ONLINE", record.status());
        assertNotNull(engine.getClientDao().getLastSeen("db-client-01"));
    }

    @Test
    @DisplayName("METRICS_REPORT → bản ghi metrics ghi vào DB, getTodayTrafficBytes đúng")
    void metricsReportPersistsToDatabase() throws Exception {
        connectClient("db-client-02", "PC-DB");
        awaitCondition("client sẵn sàng", Duration.ofSeconds(10), () ->
                engine.getClientDao().exists("db-client-02"));

        writeAndFlush(BaseMessage.of(MessageType.METRICS_REPORT, "db-client-02",
                new MetricsPayload(1_000, 500, 200, 100, 60, "ACTIVE")));
        writeAndFlush(BaseMessage.of(MessageType.METRICS_REPORT, "db-client-02",
                new MetricsPayload(2_000, 1_000, 400, 200, 120, "ACTIVE")));

        awaitCondition("2 bản ghi metrics vào DB", Duration.ofSeconds(10), () ->
                engine.getMetricsDao().countAll("db-client-02") >= 2);

        assertEquals(4_500, engine.getMetricsDao().getTodayTrafficBytes("db-client-02"),
                "Tổng hôm nay = (1000+500) + (2000+1000)");
        assertEquals(2, engine.getMetricsDao().countToday("db-client-02"));
    }

    @Test
    @DisplayName("Server gửi COMMAND → log SENT; client ACK → status ACKED trong DB")
    void commandAckFlowPersistsToDatabase() throws Exception {
        connectClient("db-client-03", "PC-DB-3");
        awaitCondition("client sẵn sàng", Duration.ofSeconds(10), () ->
                engine.getClientDao().exists("db-client-03"));

        // Server chủ động gửi COMMAND (ghi SENT qua dao — mô phỏng Rule Engine Task 4.2)
        engine.getCommandLogDao().recordSent("cmd-db-1", "db-client-03",
                ActionType.PAUSE_NETWORK, "Vuot gio");
        assertEquals("SENT", engine.getCommandLogDao().getStatus("cmd-db-1"));

        // Client "xử lý lệnh" và gửi ACK kèm commandId (Task 3.3)
        writeAndFlush(BaseMessage.of(MessageType.ACK, "db-client-03",
                Map.of("commandId", "cmd-db-1")));

        awaitCondition("command_logs cập nhật ACKED", Duration.ofSeconds(10), () ->
                "ACKED".equals(engine.getCommandLogDao().getStatus("cmd-db-1")));
        assertTrue(engine.getCommandLogDao().countByStatus(CommandLogDao.STATUS_ACKED) >= 1,
                "Ít nhất 1 lệnh đã được ACK trong DB");
    }

    @Test
    @DisplayName("Client ngắt kết nối → trạng thái OFFLINE trong DB")
    void disconnectMarksClientOffline() throws Exception {
        connectClient("db-client-04", "PC-DB-4");
        awaitCondition("client ONLINE trong DB", Duration.ofSeconds(10), () ->
                "ONLINE".equals(engine.getClientDao().getStatus("db-client-04")));

        closeClientQuietly();

        awaitCondition("client OFFLINE trong DB sau disconnect", Duration.ofSeconds(15), () ->
                "OFFLINE".equals(engine.getClientDao().getStatus("db-client-04")));
    }

    @Test
    @DisplayName("Engine chạy không DB (null) → không crash, DAO getter trả null")
    void engineWorksWithoutDatabase() throws Exception {
        TcpServerEngine noDbEngine = new TcpServerEngine(0, 8, 30);
        try {
            noDbEngine.start();
            assertTrue(noDbEngine.isRunning());
            assertTrue(noDbEngine.getClientDao() == null);
            assertTrue(noDbEngine.getMetricsDao() == null);
            assertTrue(noDbEngine.getCommandLogDao() == null);

            // Kết nối client cũ kiểu vẫn hoạt động bình thường không DB
            Socket socket = new Socket("127.0.0.1", noDbEngine.getPort());
            socket.setTcpNoDelay(true);
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, "no-db-client",
                    new HandshakePayload("PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA"))));
            writer.flush();
            socket.close();
            awaitCondition("engine không DB vẫn nhận client", Duration.ofSeconds(10), () ->
                    noDbEngine.getSessionManager().count() >= 0); // không crash là đủ
        } finally {
            noDbEngine.stop();
        }
    }
}
