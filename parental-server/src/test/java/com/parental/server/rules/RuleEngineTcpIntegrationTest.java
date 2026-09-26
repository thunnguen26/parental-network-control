package com.parental.server.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.db.DatabaseManager;
import com.parental.server.network.TcpServerEngine;

/**
 * Integration test Task 4.2: {@link TcpServerEngine} + {@link RuleEvaluationEngine}
 * end-to-end qua TCP thật (socket loopback, file DB @TempDir WAL — không flaky):
 * <ol>
 *   <li>Client ảo handshake + METRICS_REPORT vi phạm quota → engine gửi
 *       COMMAND(PAUSE_NETWORK) qua socket, command_logs = SENT; client ACK → ACKED.</li>
 *   <li>Nới lỏng rule (quota lớn hơn) → engine tự gửi COMMAND(RESUME_NETWORK).</li>
 *   <li>Client chưa có rule → engine không can thiệp.</li>
 *   <li>Engine chạy không DB → không tạo Rule Engine (legacy path).</li>
 * </ol>
 */
@Timeout(90)
class RuleEngineTcpIntegrationTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

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
        // File DB @TempDir → WAL bật, reader không chặn writer (bài học Task 4.1)
        database = new DatabaseManager(tempDir.resolve("rule_engine_integration.db"));
        parser = new JsonProtocolParser();
        engine = new TcpServerEngine(0, 8, 30, database); // Rule Engine tự start cùng server
        engine.setRuleScanIntervalSeconds(1); // quét nhanh cho test
        engine.start();
    }

    @AfterEach
    void tearDown() {
        closeClientQuietly();
        engine.stop();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void connectClient(String clientId, String deviceName) throws IOException {
        clientSocket = new Socket("127.0.0.1", engine.getPort());
        clientSocket.setTcpNoDelay(true);
        clientSocket.setSoTimeout(20_000);
        clientReader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        clientWriter = new BufferedWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
        writeAndFlush(BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                deviceName, "DESKTOP", "Windows 11", "127.0.0.1", clientId)));
        // Đợi handshake được xử lý xong (upsert DB) trước khi tiếp tục
        awaitCondition("client ONLINE trong DB", Duration.ofSeconds(10), () ->
                engine.getClientDao().exists(clientId));
    }

    private void writeAndFlush(BaseMessage<?> message) throws IOException {
        try {
            clientWriter.write(parser.toLine(message));
        } catch (ProtocolParseException e) {
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

    /**
     * Đọc các dòng JSON từ socket client cho tới khi gặp COMMAND với action
     * mong muốn (các gói khác — ACK phản hồi HEARTBEAT... — được bỏ qua).
     */
    private BaseMessage<?> awaitCommand(ActionType action) throws IOException {
        Instant deadline = Instant.now().plus(COMMAND_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String line = clientReader.readLine();
            if (line == null) {
                throw new IOException("Server closed the stream while waiting for " + action);
            }
            try {
                BaseMessage<?> message = parser.fromLine(line);
                if (message.getType() == MessageType.COMMAND
                        && message.getPayload() instanceof CommandPayload payload
                        && payload.action() == action) {
                    return message;
                }
            } catch (ProtocolParseException e) {
                throw new IOException("Malformed line from server: " + line, e);
            }
        }
        throw new AssertionError("Timeout while waiting for COMMAND " + action + " over TCP");
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Vi phạm quota → COMMAND(PAUSE_NETWORK) qua socket + SENT trong DB; ACK → ACKED; nới lỏng rule → RESUME")
    void quotaViolationTriggersPauseCommandOverTcp() throws Exception {
        connectClient("rule-client-01", "PC-Rule-1");
        engine.getRuleDao().upsertRule("rule-client-01",
                new ConfigSyncPayload(List.of(), 0, 1_000, null, null));
        writeAndFlush(BaseMessage.of(MessageType.METRICS_REPORT, "rule-client-01",
                new MetricsPayload(3_000, 0, 0, 0, 0, "ACTIVE")));

        // Đợi lệnh COMMAND PAUSE từ Rule Engine qua socket thật
        BaseMessage<?> pauseCommand = awaitCommand(ActionType.PAUSE_NETWORK);
        CommandPayload pausePayload = (CommandPayload) pauseCommand.getPayload();
        assertEquals(RuleEvaluationEngine.REASON_TRAFFIC_QUOTA, pausePayload.reason());

        // command_logs: SENT (Task 4.2 ghi ngay sau khi gửi) + trạng thái khóa
        awaitCondition("command_logs SENT", Duration.ofSeconds(5), () ->
                "SENT".equals(engine.getCommandLogDao().getStatus(pausePayload.commandId())));
        awaitCondition("engine đánh dấu enforced", Duration.ofSeconds(5), () ->
                engine.getRuleEvaluationEngine().isEnforced("rule-client-01"));

        // Client ACK lệnh → DB chuyển ACKED (luồng ACK Task 3.3 + markAcked Task 4.1)
        writeAndFlush(BaseMessage.of(MessageType.ACK, "rule-client-01",
                Map.of("commandId", pausePayload.commandId())));
        awaitCondition("command_logs ACKED", Duration.ofSeconds(10), () ->
                "ACKED".equals(engine.getCommandLogDao().getStatus(pausePayload.commandId())));

        // Nới lỏng rule: quota 1.000 → 1.000.000 → hết vi phạm → RESUME qua socket
        engine.getRuleDao().upsertRule("rule-client-01",
                new ConfigSyncPayload(List.of(), 0, 1_000_000, null, null));
        BaseMessage<?> resumeCommand = awaitCommand(ActionType.RESUME_NETWORK);
        CommandPayload resumePayload = (CommandPayload) resumeCommand.getPayload();
        assertEquals(RuleEvaluationEngine.REASON_RESUME, resumePayload.reason());
        awaitCondition("RESUME ghi SENT", Duration.ofSeconds(5), () ->
                "SENT".equals(engine.getCommandLogDao().getStatus(resumePayload.commandId())));
        awaitCondition("gỡ khóa trong state", Duration.ofSeconds(5), () ->
                engine.getRuleEvaluationEngine().getEnforcedClients().isEmpty());
    }

    @Test
    @DisplayName("Client chưa có rule → engine không can thiệp dù metrics lệch chuẩn")
    void clientWithoutRuleNotTouched() throws Exception {
        connectClient("rule-client-02", "PC-Rule-2");
        writeAndFlush(BaseMessage.of(MessageType.METRICS_REPORT, "rule-client-02",
                new MetricsPayload(9_999_999, 0, 0, 0, 0, "ACTIVE")));

        // Đợi 1 lượt quét: không lệnh nào phải xuất hiện
        assertNoCommand(Duration.ofSeconds(3));
        assertTrue(engine.getRuleEvaluationEngine().getEnforcedClients().isEmpty());
    }

    @Test
    @DisplayName("Engine không DB → getRuleEvaluationEngine()/getRuleDao() null, vẫn chạy bình thường")
    void engineWithoutDatabaseHasNoRuleEngine() throws Exception {
        TcpServerEngine noDbEngine = new TcpServerEngine(0, 8, 30);
        try {
            noDbEngine.start();
            assertNull(noDbEngine.getRuleEvaluationEngine());
            assertNull(noDbEngine.getRuleDao());
            assertTrue(noDbEngine.isRunning());
        } finally {
            noDbEngine.stop();
        }
    }

    /** Khẳng định KHÔNG có COMMAND nào đến trong khoảng {@code quietPeriod}. */
    private void assertNoCommand(Duration quietPeriod) throws IOException {
        Instant deadline = Instant.now().plus(quietPeriod);
        while (Instant.now().isBefore(deadline)) {
            clientSocket.setSoTimeout(Math.max(1,
                    (int) Duration.between(Instant.now(), deadline).toMillis()));
            try {
                String line = clientReader.readLine();
                if (line == null) {
                    return; // server đóng — không có COMMAND
                }
                try {
                    BaseMessage<?> message = parser.fromLine(line);
                    if (message.getType() == MessageType.COMMAND) {
                        throw new AssertionError("Unexpected COMMAND while client has no rule: " + line);
                    }
                } catch (ProtocolParseException e) {
                    throw new IOException("Malformed line from server: " + line, e);
                }
            } catch (SocketTimeoutException expected) {
                // Không có dữ liệu trong khoảng im lặng — đúng kỳ vọng
                return;
            }
        }
    }
}
