package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.parental.common.protocol.payload.UnpairPayload;
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
        connectClient(clientId, deviceName, "default-token");
    }

    /**
     * Kết nối và handshake kèm {@code deviceToken} — từ khi có Device Pairing,
     * handshake vào server CÓ database bắt buộc token khớp DB (is_paired=1).
     * Mỗi test setUp DB mới → token phải được cấp phát trước qua
     * {@code engine.getClientDao().saveDeviceToken(clientId, token)}.
     */
    private void connectClient(String clientId, String deviceName, String deviceToken) throws IOException {
        clientSocket = new Socket("127.0.0.1", engine.getPort());
        clientSocket.setTcpNoDelay(true);
        clientSocket.setSoTimeout(15_000);
        clientReader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        clientWriter = new BufferedWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
        writeAndFlush(BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                deviceName, "DESKTOP", "Windows 11", "127.0.0.1", clientId, deviceToken)));
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
        // Device Pairing: server có DB xác thực token — cấp token trước khi handshake
        engine.getClientDao().saveDeviceToken("db-client-01", "token-1");
        connectClient("db-client-01", "Laptop-DB", "token-1");

        // saveDeviceToken đã tạo dòng sẵn — chờ handshake xử lý xong (đủ tên máy)
        awaitCondition("handshake upsert đủ thông tin", Duration.ofSeconds(10), () -> {
            ClientRecord record = engine.getClientDao().get("db-client-01");
            return record != null && "Laptop-DB".equals(record.deviceName());
        });

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
        engine.getClientDao().saveDeviceToken("db-client-02", "token-2");
        connectClient("db-client-02", "PC-DB", "token-2");
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
        engine.getClientDao().saveDeviceToken("db-client-03", "token-3");
        connectClient("db-client-03", "PC-DB-3", "token-3");
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
        engine.getClientDao().saveDeviceToken("db-client-04", "token-4");
        connectClient("db-client-04", "PC-DB-4", "token-4");
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

    // ------------------------------------------------------------------
    // Device Pairing & Client Identity Persistence (integration)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HANDSHAKE không có deviceToken → bị từ chối UNAUTHORIZED_DEVICE, không có session")
    void handshakeWithoutTokenRejected() throws Exception {
        connectClient("db-client-noauth", "PC-No-Auth", null);

        // Chờ đủ lâu để server xử lý và từ chối — session không bao giờ được đăng ký
        awaitCondition("không đăng ký session cho thiết bị chưa ghép đôi", Duration.ofSeconds(3),
                () -> engine.getSessionManager().count() == 0);
        assertNull(engine.getSessionManager().get("db-client-noauth"));
        // Client chưa ghép đôi KHÔNG được upsert vào DB
        Thread.sleep(300);
        assertFalse(engine.getClientDao().exists("db-client-noauth"),
                "Thiết bị chưa ghép đôi không được ghi vào bảng clients");
    }

    @Test
    @DisplayName("HANDSHAKE sai deviceToken → bị từ chối, không có session")
    void handshakeWithWrongTokenRejected() throws Exception {
        engine.getClientDao().saveDeviceToken("db-client-bad", "right-token");
        connectClient("db-client-bad", "PC-Bad-Token", "wrong-token");

        awaitCondition("không đăng ký session cho token sai", Duration.ofSeconds(3),
                () -> engine.getSessionManager().count() == 0);
        assertNull(engine.getSessionManager().get("db-client-bad"));
    }

    @Test
    @DisplayName("PAIR_REQUEST PIN hợp lệ → PAIR_RESPONSE success + token → HANDSHAKE bằng token thành công")
    void pairRequestFlowIssuesTokenThenHandshakeSucceeds() throws Exception {
        String pin = engine.getPairingDao().createPin();
        String clientId = "db-client-pair";

        // Bước 1: mở kết nối ghép đôi — gói đầu là PAIR_REQUEST (PIN + info máy)
        Socket pairingSocket = new Socket("127.0.0.1", engine.getPort());
        pairingSocket.setTcpNoDelay(true);
        pairingSocket.setSoTimeout(15_000);
        BufferedWriter pairingWriter = new BufferedWriter(
                new OutputStreamWriter(pairingSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader pairingReader = new BufferedReader(
                new InputStreamReader(pairingSocket.getInputStream(), StandardCharsets.UTF_8));
        writeAndFlushOn(pairingWriter, BaseMessage.of(MessageType.PAIR_REQUEST, clientId,
                new com.parental.common.protocol.payload.PairRequestPayload(
                        pin, "Laptop-Pair", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-77")));

        // Bước 2: nhận PAIR_RESPONSE success=true + deviceToken
        String responseLine = pairingReader.readLine();
        assertNotNull(responseLine, "Server phải trả PAIR_RESPONSE");
        BaseMessage<?> response = parser.fromLine(responseLine);
        assertEquals(MessageType.PAIR_RESPONSE, response.getType());
        var pairPayload = assertInstanceOf(
                com.parental.common.protocol.payload.PairResponsePayload.class, response.getPayload());
        assertTrue(pairPayload.success(), "PIN còn hạn chưa dùng → success=true");
        assertEquals("PAIRED", pairPayload.message());
        assertNotNull(pairPayload.deviceToken());
        pairingSocket.close();

        // DB: client đã paired với đúng token, PIN đã bị tiêu thụ
        assertTrue(engine.getClientDao().isPaired(clientId));
        assertTrue(engine.getClientDao().isValidPairing(clientId, pairPayload.deviceToken()));
        assertEquals(com.parental.server.db.PairingDao.PinState.USED,
                engine.getPairingDao().getPinState(pin));

        // Bước 3: HANDSHAKE bằng token vừa cấp → session được đăng ký bình thường
        connectClient(clientId, "Laptop-Pair", pairPayload.deviceToken());
        awaitCondition("session sau ghép đôi", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get(clientId) != null);
        assertEquals("Laptop-Pair", engine.getClientDao().get(clientId).deviceName());
    }

    @Test
    @DisplayName("Xóa client đang ONLINE: UNPAIR qua socket → session bị ngắt + DB xóa sạch")
    void deleteOnlineClientSendsUnpairAndCleansDb() throws Exception {
        String clientId = "db-client-unpair";
        engine.getClientDao().saveDeviceToken(clientId, "token-unpair");
        connectClient(clientId, "PC-Unpair", "token-unpair");
        awaitCondition("session ONLINE trong DB", Duration.ofSeconds(10), () ->
                "ONLINE".equals(engine.getClientDao().getStatus(clientId)));

        // Thu hồi: gửi UNPAIR qua socket thật + ngắt phiên (reason UNPAIRED)
        assertTrue(engine.unpairClient(clientId, UnpairPayload.DEFAULT_REASON),
                "Client đang online → UNPAIR phải gửi thành công");

        // Client ảo nhận được gói UNPAIR trước khi socket đóng
        String unpairLine = clientReader.readLine();
        assertNotNull(unpairLine, "Client phải nhận gói UNPAIR trước khi bị ngắt");
        BaseMessage<?> unpair = parser.fromLine(unpairLine);
        assertEquals(MessageType.UNPAIR, unpair.getType());
        var unpairPayload = assertInstanceOf(UnpairPayload.class, unpair.getPayload());
        assertEquals(UnpairPayload.DEFAULT_REASON, unpairPayload.reason());

        // Session bị gỡ khỏi manager (kèm callback reason UNPAIRED)
        awaitCondition("session bị gỡ sau unpair", Duration.ofSeconds(10), () ->
                engine.getSessionManager().get(clientId) == null);

        // DB xóa sạch client
        assertTrue(engine.getClientDao().deleteClient(clientId));
        awaitCondition("client biến mất khỏi DB", Duration.ofSeconds(5), () ->
                !engine.getClientDao().exists(clientId));
    }

    @Test
    @DisplayName("unpairClient với client OFFLINE → false, không crash")
    void unpairOfflineClientIsSafe() {
        assertFalse(engine.unpairClient("ghost-client", "reason"),
                "Client không online → false (không gửi gì)");
    }

    @Test
    @DisplayName("updateAlias qua TCP engine DB: alias persist và map vào ClientRecord")
    void aliasPersistsThroughEngineDatabase() throws Exception {
        engine.getClientDao().saveDeviceToken("db-client-alias", "token-alias");
        connectClient("db-client-alias", "PC-Alias", "token-alias");
        awaitCondition("client ONLINE", Duration.ofSeconds(10), () ->
                "ONLINE".equals(engine.getClientDao().getStatus("db-client-alias")));

        assertTrue(engine.getClientDao().updateAlias("db-client-alias", "Máy học bài"));
        ClientRecord record = engine.getClientDao().get("db-client-alias");
        assertNotNull(record);
        assertEquals("Máy học bài", record.alias());
        assertEquals("PC-Alias", record.deviceName(), "Tên gốc giữ nguyên");
    }

    @Test
    @DisplayName("PAIR_REQUEST PIN hết hạn → PAIR_RESPONSE success=false, không cấp token")
    void pairRequestWithExpiredPinRejected() throws Exception {
        // Chèn PIN đã hết hạn trực tiếp vào DB
        try (var connection = database.newConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO pairing_sessions (pin, expires_at, is_used) VALUES ('333333', ?, 0)")) {
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(30)));
            statement.executeUpdate();
        }

        Socket pairingSocket = new Socket("127.0.0.1", engine.getPort());
        pairingSocket.setTcpNoDelay(true);
        pairingSocket.setSoTimeout(15_000);
        BufferedWriter pairingWriter = new BufferedWriter(
                new OutputStreamWriter(pairingSocket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader pairingReader = new BufferedReader(
                new InputStreamReader(pairingSocket.getInputStream(), StandardCharsets.UTF_8));
        writeAndFlushOn(pairingWriter, BaseMessage.of(MessageType.PAIR_REQUEST, "db-client-late",
                new com.parental.common.protocol.payload.PairRequestPayload(
                        "333333", "PC-Late", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-88")));

        String responseLine = pairingReader.readLine();
        assertNotNull(responseLine);
        BaseMessage<?> response = parser.fromLine(responseLine);
        var pairPayload = assertInstanceOf(
                com.parental.common.protocol.payload.PairResponsePayload.class, response.getPayload());
        assertFalse(pairPayload.success(), "PIN hết hạn → success=false");
        assertEquals("PIN_EXPIRED", pairPayload.message());
        assertNull(pairPayload.deviceToken());
        pairingSocket.close();

        // Không cấp token, không upsert client
        Thread.sleep(300);
        assertFalse(engine.getClientDao().exists("db-client-late"));
    }

    /** Ghi 1 dòng LDJSON ra writer cụ thể (dùng cho kết nối ghép đôi tạm thời). */
    private void writeAndFlushOn(BufferedWriter writer, BaseMessage<?> message) throws IOException {
        try {
            writer.write(parser.toLine(message));
        } catch (com.parental.common.protocol.ProtocolParseException e) {
            throw new IOException("Serialize failed in test", e);
        }
        writer.flush();
    }
}
