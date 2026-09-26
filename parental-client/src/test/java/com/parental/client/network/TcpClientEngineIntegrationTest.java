package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DefaultDeviceInfoCollector;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Integration Test cho {@link TcpClientEngine}: dùng server ảo
 * {@link FakeTcpServer} trên dynamic port (0) để kiểm tra vòng đời thực:
 * kết nối → HANDSHAKE → HEARTBEAT/ACK → auto-reconnect → clean shutdown.
 *
 * <p>Chống deadlock trên Windows: mọi chờ đều có timeout; fake server chạy
 * daemon thread; client là daemon thread; {@code @Timeout} chặn test chạy quá
 * lâu; stop() luôn được gọi trong {@code @AfterEach}.</p>
 */
@Timeout(90)
class TcpClientEngineIntegrationTest {

    private static final long AWAIT_TIMEOUT_MS = 15_000;

    private FakeTcpServer server;
    private TcpClientEngine engine;
    private DeviceInfoCollector deviceInfoCollector;

    /** Listener ghi nhận COMMAND/CONFIG_SYNC cho test dispatch. */
    private static final class RecordingHandler implements ServerCommandHandler {
        final List<CommandPayload> commands = new CopyOnWriteArrayList<>();
        final List<ConfigSyncPayload> configs = new CopyOnWriteArrayList<>();
        final CountDownLatch commandLatch = new CountDownLatch(1);
        final CountDownLatch configLatch = new CountDownLatch(1);

        @Override
        public void onCommand(CommandPayload command) {
            commands.add(command);
            commandLatch.countDown();
        }

        @Override
        public void onConfigSync(ConfigSyncPayload config) {
            configs.add(config);
            configLatch.countDown();
        }
    }

    private RecordingHandler recordingHandler;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeTcpServer(); // tự start
        deviceInfoCollector = new DefaultDeviceInfoCollector();
        recordingHandler = new RecordingHandler();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop(); // idempotent — luôn dọn dẹp, không quăng exception
        }
        server.close();
    }

    /** Client config ngắn: heartbeat 300ms để test nhanh, backoff 100ms..1s. */
    private ClientConfig shortTimingsConfig() {
        return ClientConfig.builder()
                .serverHost("127.0.0.1")
                .serverPort(server.getPort())
                .heartbeatIntervalMs(300)
                .initialReconnectDelayMs(100)
                .maxReconnectDelayMs(1_000)
                .build();
    }

    private TcpClientEngine newEngine(ServerCommandHandler handler) {
        return new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), handler);
    }

    private static void awaitCondition(String description, long timeoutMs,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
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
    @DisplayName("Client tự kết nối và gửi HANDSHAKE với payload đầy đủ, hợp lệ")
    void clientSendsValidHandshake() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();

        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS), "Server phải nhận HANDSHAKE");

        BaseMessage<?> handshakeMsg = server.received.stream()
                .filter(m -> m.getType() == MessageType.HANDSHAKE)
                .findFirst().orElseThrow();

        assertEquals(engine.getConfig().getClientId(), handshakeMsg.getClientId());
        assertTrue(handshakeMsg.getTimestamp() > 0, "timestamp epoch seconds phải > 0");

        HandshakePayload payload = server.firstHandshakePayload();
        assertNotNull(payload);
        assertEquals("DESKTOP", payload.deviceType());
        assertNotNull(payload.deviceName());
        assertFalse(payload.deviceName().isBlank());
        assertNotNull(payload.osVersion());
        assertFalse(payload.osVersion().isBlank());
        assertNotNull(payload.ipAddress());
        assertFalse(payload.ipAddress().isBlank());
        assertNotNull(payload.macAddress());
        assertFalse(payload.macAddress().isBlank());
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("Client gửi HEARTBEAT định kỳ sau handshake và nhận ACK cập nhật lastAck")
    void clientSendsPeriodicHeartbeatAndReceivesAck() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();

        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(server.awaitFirstHeartbeat(AWAIT_TIMEOUT_MS), "Client phải gửi HEARTBEAT định kỳ");
        awaitCondition("Client phải nhận ACK từ server", AWAIT_TIMEOUT_MS,
                () -> engine.getLastAckTimestampMs() > 0);
        assertTrue(server.count(MessageType.HEARTBEAT) >= 1);

        // Chờ thêm 1 chu kỳ: phải có nhiều hơn 1 heartbeat (định kỳ thật sự)
        long afterFirst = server.count(MessageType.HEARTBEAT);
        awaitCondition("heartbeat lặp lại định kỳ", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.HEARTBEAT) > afterFirst);
        assertTrue(engine.getLastAckTimestampMs() > 0);
    }

    @Test
    @DisplayName("Auto-Reconnect: server chết đột ngột rồi mở lại cùng port -> client tự kết nối lại")
    void autoReconnectAfterServerRestart() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();

        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS), "Kết nối lần 1 phải handshake thành công");
        assertTrue(server.awaitFirstHeartbeat(AWAIT_TIMEOUT_MS));

        // Server chết đột ngột
        server.close();
        // Chờ client chuyển sang trạng thái mất kết nối
        awaitCondition("client phát hiện mất kết nối", AWAIT_TIMEOUT_MS, () -> !engine.isConnected());

        // Server phục hồi trên CÙNG port
        server.reopen();

        // Client phải tự kết nối lại và handshake lại
        awaitCondition("client handshake lại sau khi server hồi phục", 30_000,
                () -> server.count(MessageType.HANDSHAKE) >= 2);
        awaitCondition("client kết nối lại thành công", 10_000, engine::isConnected);

        // Backoff phải được reset về initial sau reconnect thành công
        assertEquals(0, engine.getReconnectAttempt());
        long heartbeatsAfterReconnect = server.count(MessageType.HEARTBEAT);
        awaitCondition("heartbeat tiếp tục sau reconnect", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.HEARTBEAT) > heartbeatsAfterReconnect);
    }

    @Test
    @DisplayName("Auto-Reconnect khi server chưa sẵn sàng: client retry liên tục rồi kết nối được")
    void retriesWhenServerUnavailableThenConnects() throws Exception {
        int port = server.getPort();
        server.close(); // chưa có server nào lắng nghe

        engine = newEngine(recordingHandler);
        engine.start();

        // Đợi client thử ít nhất 2 lần (backoff 100ms, 200ms...)
        awaitCondition("client retry khi server không có", 3_000, () -> engine.getReconnectAttempt() >= 2);
        assertFalse(engine.isConnected());

        // Mở server trên cùng port
        server.reopen();
        assertTrue(server.awaitHandshake(30_000), "Client phải kết nối được khi server xuất hiện");
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("Server đẩy COMMAND và CONFIG_SYNC -> listener được gọi đúng payload")
    void serverPushedCommandAndConfigSyncAreDispatched() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        // Server chủ động đẩy COMMAND và CONFIG_SYNC xuống client
        CommandPayload command = new CommandPayload("cmd-001", ActionType.PAUSE_NETWORK, "Het gio su dung");
        ConfigSyncPayload config = new ConfigSyncPayload(
                List.of("tiktok.com", "facebook.com"), 14_400, 5_368_709_120L, "06:00", "22:00");
        // Fake server chỉ ghi nhận inbound; đẩy outbound qua socket của client hiện hành
        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server", command));
        pushToCurrentClient(BaseMessage.of(MessageType.CONFIG_SYNC, "fake-server", config));

        assertTrue(recordingHandler.commandLatch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "ServerCommandHandler.onCommand phải được gọi");
        assertTrue(recordingHandler.configLatch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                "ServerCommandHandler.onConfigSync phải được gọi");
        assertEquals("cmd-001", recordingHandler.commands.get(0).commandId());
        assertEquals(ActionType.PAUSE_NETWORK, recordingHandler.commands.get(0).action());
        assertEquals(2, recordingHandler.configs.get(0).blockedDomains().size());
    }

    /** Ghi 1 dòng LDJSON xuống socket client hiện hành từ phía fake server. */
    private void pushToCurrentClient(BaseMessage<?> message) {
        try {
            assertTrue(server.pushLine(new JsonProtocolParser().toLine(message)),
                    "Fake server phải push được gói tin xuống client (kết nối còn sống)");
        } catch (com.parental.common.protocol.ProtocolParseException e) {
            throw new IllegalStateException("Serialize failed in test", e);
        }
    }

    @Test
    @DisplayName("stop() clean shutdown: socket đóng, listener EOF, không treo tiến trình")
    void stopShutsDownCleanly() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(engine.isConnected());

        long start = System.currentTimeMillis();
        engine.stop();
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(engine.isRunning());
        assertFalse(engine.isConnected());
        assertTrue(elapsed < 5_000, "stop() phải hoàn tất nhanh (<5s), thực tế " + elapsed + "ms");

        // Server phải thấy kết nối bị đóng (EOF hoặc lỗi đọc) — kiểm tra gián tiếp:
        // không còn heartbeat mới sau khi stop.
        long heartbeats = server.count(MessageType.HEARTBEAT);
        Thread.sleep(400); // đủ cho nhiều chu kỳ heartbeat 300ms nếu scheduler chưa chết
        assertEquals(heartbeats, server.count(MessageType.HEARTBEAT),
                "Sau stop() không được còn heartbeat nào được gửi");
    }

    @Test
    @DisplayName("start() hai lần là no-op; stop() hai lần an toàn")
    void doubleStartAndDoubleStopAreSafe() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        engine.start(); // lần 2 no-op
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(engine.isConnected());

        engine.stop();
        engine.stop(); // lần 2 an toàn
        assertFalse(engine.isRunning());
    }

    @Test
    @DisplayName("Listener ném RuntimeException -> engine không chết, heartbeat tiếp tục")
    void listenerExceptionDoesNotKillEngine() throws Exception {
        ServerCommandHandler throwingHandler = new ServerCommandHandler() {
            @Override
            public void onCommand(CommandPayload command) {
                throw new IllegalStateException("boom");
            }
        };
        engine = newEngine(throwingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-bad", ActionType.ALERT, "test")));
        Thread.sleep(500); // thời gian đủ để dispatch xảy ra

        // Engine vẫn sống và vẫn gửi heartbeat sau khi listener ném lỗi
        long before = server.count(MessageType.HEARTBEAT);
        awaitCondition("heartbeat tiếp tục sau listener exception", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.HEARTBEAT) > before);
        assertTrue(engine.isConnected());
    }
}
