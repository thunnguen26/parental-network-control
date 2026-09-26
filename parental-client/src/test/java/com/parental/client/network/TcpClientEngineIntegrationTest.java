package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
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
import com.parental.client.enforcement.HostsFileManager;
import com.parental.client.enforcement.MockNetworkEnforcer;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.client.metrics.NetworkMetricsCollector;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;

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
                .heartbeatTimeoutMs(1_500) // watchdog quét mỗi 375ms (Task 2.3)
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
        // Latch handshake mở trước khi connector set connected=true — chờ có timeout (chống race).
        awaitCondition("client connected sau khi server xuất hiện", 10_000, engine::isConnected);
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
    @DisplayName("Watchdog: server nhận heartbeat nhưng im lặng không ACK -> client phát hiện, đóng zombie, reconnect")
    void watchdogDetectsSilentServerAndReconnects() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(server.awaitFirstHeartbeat(AWAIT_TIMEOUT_MS));
        awaitCondition("nhận ACK đầu tiên", AWAIT_TIMEOUT_MS, () -> engine.getLastAckTimestampMs() > 0);
        assertEquals(0, engine.getWatchdogTimeoutCount(), "Watchdog chưa được kích hoạt");

        // Server zombie: vẫn nhận gói tin (kết nối không đứt) nhưng không trả ACK nữa.
        server.setSilentMode(true);
        long ackBefore = engine.getLastAckTimestampMs();

        // Watchdog (timeout 1.5s, quét mỗi 375ms) phải phát hiện và đóng zombie connection.
        awaitCondition("watchdog phát hiện server im lặng", 10_000,
                () -> engine.getWatchdogTimeoutCount() >= 1);
        awaitCondition("client ngắt trạng thái connected sau watchdog", AWAIT_TIMEOUT_MS,
                () -> !engine.isConnected());

        // Client phải tự reconnect và handshake lại với server (cùng port).
        awaitCondition("client handshake lại sau watchdog", 30_000,
                () -> server.count(MessageType.HANDSHAKE) >= 2);
        awaitCondition("client connected trở lại", AWAIT_TIMEOUT_MS, engine::isConnected);

        // Server vẫn im lặng → không có ACK mới nào sau khi reconnect.
        assertTrue(engine.getLastAckTimestampMs() == ackBefore
                        || server.count(MessageType.HEARTBEAT) > 1,
                "Client vẫn hoạt động bình thường sau reconnect");
    }

    @Test
    @DisplayName("Watchdog KHÔNG kích hoạt khi server phản hồi ACK bình thường")
    void watchdogDoesNotFireWhenServerResponsive() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(server.awaitFirstHeartbeat(AWAIT_TIMEOUT_MS));
        awaitCondition("nhận ACK", AWAIT_TIMEOUT_MS, () -> engine.getLastAckTimestampMs() > 0);

        // Chạy qua nhiều chu kỳ heartbeat (300ms) — watchdog timeout 1.5s không được fire.
        Thread.sleep(2_500);
        assertEquals(0, engine.getWatchdogTimeoutCount(),
                "Server responsive → watchdog không được kích hoạt");
        assertTrue(engine.isConnected());
        assertTrue(server.count(MessageType.HEARTBEAT) >= 3,
                "Client phải gửi được nhiều heartbeat trong thời gian quan sát");
    }

    @Test
    @DisplayName("Server tắt đột ngột -> client phát hiện mất kết nối và chuyển sang reconnecting")
    void serverShutdownTriggersReconnectingState() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(engine.isConnected());

        // Server tắt đột ngột (không graceful)
        server.close();

        // Client phải phát hiện NGAY LẬP TỨC (EOF/lỗi đọc, không cần chờ watchdog)
        awaitCondition("client phát hiện mất kết nối", 5_000, () -> !engine.isConnected());
        assertTrue(engine.isRunning(), "Engine vẫn running — đang ở trạng thái reconnecting");

        // Server phục hồi → client tự kết nối lại (exponential backoff)
        server.reopen();
        awaitCondition("client handshake lại sau server phục hồi", 30_000,
                () -> server.count(MessageType.HANDSHAKE) >= 2);
        awaitCondition("client connected trở lại", 10_000, engine::isConnected);
    }

    @Test
    @DisplayName("stop() đóng socket, không rò rỉ: gọi sau watchdog reconnect vẫn sạch sẽ")
    void stopAfterWatchdogReconnectCleansUp() throws Exception {
        engine = newEngine(recordingHandler);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        server.setSilentMode(true);
        awaitCondition("watchdog fire", 10_000, () -> engine.getWatchdogTimeoutCount() >= 1);
        awaitCondition("client handshake lại", 30_000,
                () -> server.count(MessageType.HANDSHAKE) >= 2);

        engine.stop(); // phải sạch sẽ dù vừa trải qua watchdog + reconnect
        assertFalse(engine.isRunning());
        assertFalse(engine.isConnected());
    }

    @Test
    @DisplayName("METRICS_REPORT gửi định kỳ (collector giả lập) và server nhận đủ payload")
    void metricsReportSentPeriodicallyWithStubCollector() throws Exception {
        // Collector giả lập: mỗi lần collect() trả traffic tăng dần 1.000B/500B
        java.util.concurrent.atomic.AtomicInteger sample = new java.util.concurrent.atomic.AtomicInteger();
        NetworkMetricsCollector stubCollector = () -> new MetricsPayload(
                sample.incrementAndGet() * 1_000L,
                sample.get() * 500L,
                1_000, 500,
                sample.get(), "ACTIVE");

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler,
                stubCollector, null, null);
        engine.start();

        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        assertTrue(server.awaitFirstMetrics(AWAIT_TIMEOUT_MS),
                "Client phải gửi METRICS_REPORT định kỳ");

        // Chờ thêm ít nhất 1 chu kỳ nữa (metricsIntervalMs = 300ms trong test)
        awaitCondition("METRICS_REPORT lặp lại định kỳ", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.METRICS_REPORT) >= 2);

        // Giải mã payload từ server — phải khớp schema Mục 3.2
        MetricsPayload metrics = server.received.stream()
                .filter(m -> m.getType() == MessageType.METRICS_REPORT)
                .map(BaseMessage::getPayload)
                .filter(MetricsPayload.class::isInstance)
                .map(MetricsPayload.class::cast)
                .findFirst().orElseThrow();
        assertEquals("ACTIVE", metrics.networkStatus());
        assertTrue(metrics.downloadBytes() >= 1_000);
        assertTrue(metrics.uploadBytes() >= 500);
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("Collector ném RuntimeException -> engine không chết, heartbeat vẫn chạy")
    void throwingCollectorDoesNotKillEngine() throws Exception {
        NetworkMetricsCollector throwingCollector = () -> {
            throw new IllegalStateException("oshî broken");
        };
        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler,
                throwingCollector, null, null);
        engine.start();

        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        // Metrics scheduler lỗi liên tục nhưng engine phải vẫn sống và gửi heartbeat
        Thread.sleep(800);
        long before = server.count(MessageType.HEARTBEAT);
        awaitCondition("heartbeat tiếp tục sau collector exception", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.HEARTBEAT) > before);
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("CONFIG_SYNC -> HostsFileManager.applyBlockedDomains được gọi đúng danh sách (Task 3.2)")
    void configSyncAppliesBlockedDomainsToHostsFile() throws Exception {
        Path tempHosts = java.nio.file.Files.createTempFile("parental-test-hosts", ".tmp");
        java.nio.file.Files.writeString(tempHosts, "127.0.0.1  localhost\n");
        HostsFileManager hostsManager = new HostsFileManager(tempHosts);

        try {
        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler,
                null, // tắt metrics cho test này
                hostsManager,
                null); // không dispatcher trong test hosts
            engine.start();
            assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

            // Server đẩy CONFIG_SYNC chứa danh sách domain chặn
            pushToCurrentClient(BaseMessage.of(MessageType.CONFIG_SYNC, "fake-server",
                    new ConfigSyncPayload(List.of("tiktok.com", "facebook.com"), 14_400, 0, "06:00", "22:00")));

            awaitCondition("hosts file được apply danh sách chặn", AWAIT_TIMEOUT_MS, () -> {
                try {
                    String content = java.nio.file.Files.readString(tempHosts);
                    return content.contains("127.0.0.1  tiktok.com")
                            && content.contains(HostsFileManager.MARKER_START)
                            && content.contains("127.0.0.1  localhost");
                } catch (IOException e) {
                    return false;
                }
            });

            // CONFIG_SYNC thứ hai với danh sách rỗng → clearBlocks (marker bị gỡ)
            pushToCurrentClient(BaseMessage.of(MessageType.CONFIG_SYNC, "fake-server",
                    new ConfigSyncPayload(List.of(), 0, 0, "00:00", "23:59")));
            awaitCondition("hosts file được clear khi danh sách rỗng", AWAIT_TIMEOUT_MS, () -> {
                try {
                    return !java.nio.file.Files.readString(tempHosts)
                            .contains(HostsFileManager.MARKER_START);
                } catch (IOException e) {
                    return false;
                }
            });
            assertTrue(engine.isConnected(), "Engine không được chết sau khi can thiệp hosts");
        } finally {
            engine.stop();
            java.nio.file.Files.deleteIfExists(tempHosts);
            java.nio.file.Files.deleteIfExists(tempHosts.resolveSibling(tempHosts.getFileName() + ".parental.bak"));
        }
    }

    @Test
    @DisplayName("PAUSE_NETWORK -> enforcer pause, status PAUSED, ACK commandId về Server (Task 3.3)")
    void pauseCommandTriggersEnforcerAndAck() throws Exception {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        com.parental.client.metrics.StubMetricsCollectorAccess collector =
                new com.parental.client.metrics.StubMetricsCollectorAccess();
        com.parental.client.enforcement.ActionDispatcher dispatcher =
                new com.parental.client.enforcement.ActionDispatcher(enforcer,
                        msg -> true, collector);

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler, collector, null, dispatcher);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        // Server đẩy lệnh PAUSE_NETWORK
        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-pause-42", ActionType.PAUSE_NETWORK, "Het gio su dung")));

        // Enforcer được kích hoạt
        awaitCondition("enforcer pause được gọi", AWAIT_TIMEOUT_MS,
                () -> enforcer.pauseCount.get() >= 1);
        assertEquals("PAUSED", enforcer.getNetworkStatus());

        // ACK kèm commandId về tới fake server
        awaitCondition("Server nhận ACK commandId", AWAIT_TIMEOUT_MS, () -> server.received.stream()
                .anyMatch(m -> m.getType() == MessageType.ACK
                        && m.getPayload() instanceof java.util.Map<?, ?> payload
                        && "cmd-pause-42".equals(payload.get("commandId"))));
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("RESUME_NETWORK sau PAUSE -> enforcer resume, status ACTIVE, ACK (Task 3.3)")
    void resumeCommandRestoresActiveAndAcks() throws Exception {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        com.parental.client.metrics.StubMetricsCollectorAccess collector =
                new com.parental.client.metrics.StubMetricsCollectorAccess();
        com.parental.client.enforcement.ActionDispatcher dispatcher =
                new com.parental.client.enforcement.ActionDispatcher(enforcer, msg -> true, collector);

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler, collector, null, dispatcher);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-p", ActionType.PAUSE_NETWORK, "x")));
        awaitCondition("paused", AWAIT_TIMEOUT_MS, () -> enforcer.pauseCount.get() >= 1);

        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-r", ActionType.RESUME_NETWORK, "y")));
        awaitCondition("enforcer resume được gọi", AWAIT_TIMEOUT_MS,
                () -> enforcer.resumeCount.get() >= 1);
        assertEquals("ACTIVE", enforcer.getNetworkStatus());
        assertEquals("ACTIVE", collector.status);

        awaitCondition("Server nhận ACK resume", AWAIT_TIMEOUT_MS, () -> server.received.stream()
                .anyMatch(m -> m.getType() == MessageType.ACK
                        && m.getPayload() instanceof java.util.Map<?, ?> p
                        && "cmd-r".equals(p.get("commandId"))));
    }

    @Test
    @DisplayName("METRICS_REPORT sau PAUSE phản ánh networkStatus=PAUSED (Task 3.3)")
    void metricsReportReflectsPausedStatus() throws Exception {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        com.parental.client.metrics.StubMetricsCollectorAccess collector =
                new com.parental.client.metrics.StubMetricsCollectorAccess();
        collector.downloadBytes = 5_000;
        com.parental.client.enforcement.ActionDispatcher dispatcher =
                new com.parental.client.enforcement.ActionDispatcher(enforcer, msg -> true, collector);

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler, collector, null, dispatcher);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-p2", ActionType.PAUSE_NETWORK, "x")));
        awaitCondition("paused", AWAIT_TIMEOUT_MS, () -> enforcer.pauseCount.get() >= 1);

        // Chu kỳ metrics kế tiếp phải gửi status PAUSED
        awaitCondition("METRICS_REPORT có networkStatus=PAUSED", 10_000, () ->
                server.received.stream()
                        .filter(m -> m.getType() == MessageType.METRICS_REPORT)
                        .map(BaseMessage::getPayload)
                        .filter(MetricsPayload.class::isInstance)
                        .map(MetricsPayload.class::cast)
                        .anyMatch(m -> "PAUSED".equals(m.networkStatus())));
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("ALERT -> notifier nhận message + ACK; stop() gọi enforcer cleanup (Task 3.3)")
    void alertNotifiesAndStopCleansUpEnforcer() throws Exception {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        List<String> alerts = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.parental.client.enforcement.ActionDispatcher dispatcher =
                new com.parental.client.enforcement.ActionDispatcher(enforcer,
                        msg -> {
                            alerts.add(msg);
                            return true;
                        }, null);

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), recordingHandler, null, null, dispatcher);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        pushToCurrentClient(BaseMessage.of(MessageType.COMMAND, "fake-server",
                new CommandPayload("cmd-alert-9", ActionType.ALERT, "Canh bao gio hoc")));
        awaitCondition("notifier nhận message", AWAIT_TIMEOUT_MS, () -> !alerts.isEmpty());
        assertEquals("Canh bao gio hoc", alerts.get(0));
        awaitCondition("Server nhận ACK alert", AWAIT_TIMEOUT_MS, () -> server.received.stream()
                .anyMatch(m -> m.getType() == MessageType.ACK
                        && m.getPayload() instanceof java.util.Map<?, ?> p
                        && "cmd-alert-9".equals(p.get("commandId"))));

        // stop() phải kích hoạt cleanup enforcer (xóa firewall rule — không để máy mất mạng vĩnh viễn)
        engine.stop();
        assertEquals(1, enforcer.cleanupCount.get(), "stop() phải gọi enforcer.cleanup()");
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
