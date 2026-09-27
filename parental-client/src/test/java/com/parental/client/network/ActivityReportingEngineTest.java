package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.client.activity.ActivityCollector;
import com.parental.client.config.ClientConfig;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Integration test engine-level cho Activity Monitoring (PROTOCOL.md Mục
 * 3.9): {@link TcpClientEngine} với {@link ActivityCollector} stub gửi
 * ACTIVITY_REPORT định kỳ qua TCP thật ({@link FakeTcpServer}) — scheduler
 * riêng không block heartbeat; collector ném RuntimeException không chết;
 * tắt khi collector null hoặc {@code activityIntervalMs=0}.
 */
@Timeout(90)
class ActivityReportingEngineTest {

    private static final long AWAIT_TIMEOUT_MS = 10_000;

    private FakeTcpServer server;
    private TcpClientEngine engine;
    private final DeviceInfoCollector deviceInfoCollector =
            () -> new com.parental.common.protocol.payload.HandshakePayload(
                    "PC-Test", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-FF");

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeTcpServer();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
        server.close();
    }

    private ClientConfig config(long activityIntervalMs) {
        return ClientConfig.builder()
                .serverHost("127.0.0.1")
                .serverPort(server.getPort())
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1_500)
                .metricsIntervalMs(5_000)
                .activityIntervalMs(activityIntervalMs)
                .initialReconnectDelayMs(100)
                .maxReconnectDelayMs(500)
                .build();
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

    @Test
    @DisplayName("Engine gửi ACTIVITY_REPORT định kỳ với payload từ collector")
    void engineSendsActivityReportsPeriodically() throws Exception {
        AtomicInteger collectCount = new AtomicInteger();
        ActivityCollector stub = () -> {
            collectCount.incrementAndGet();
            return new ActivityReportPayload("Bài tập Toán - Chrome", "chrome.exe",
                    java.util.List.of("chrome.exe", "notepad.exe"), System.currentTimeMillis());
        };

        engine = new TcpClientEngine(config(500), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { },
                null, null, null, null, null, stub);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        awaitCondition("server nhận ACTIVITY_REPORT", AWAIT_TIMEOUT_MS, () ->
                server.count(MessageType.ACTIVITY_REPORT) >= 2);
        assertTrue(collectCount.get() >= 2, "Collector phải được gọi nhiều lần");

        // Payload đến đúng schema Mục 3.9
        ActivityReportPayload received = server.received.stream()
                .filter(m -> m.getType() == MessageType.ACTIVITY_REPORT)
                .map(BaseMessage::getPayload)
                .filter(ActivityReportPayload.class::isInstance)
                .map(ActivityReportPayload.class::cast)
                .findFirst().orElseThrow();
        assertEquals("chrome.exe", received.activeProcessName());
        assertEquals("Bài tập Toán - Chrome", received.activeWindowTitle());

        // Heartbeat vẫn chạy song song — scheduler activity không block
        awaitCondition("heartbeat vẫn chạy song song", AWAIT_TIMEOUT_MS, () ->
                server.count(MessageType.HEARTBEAT) >= 2);
    }

    @Test
    @DisplayName("Collector ném RuntimeException → engine sống, heartbeat tiếp tục (defensive)")
    void collectorExceptionDoesNotKillEngine() throws Exception {
        ActivityCollector throwingCollector = () -> {
            throw new IllegalStateException("boom");
        };

        engine = new TcpClientEngine(config(200), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { },
                null, null, null, null, null, throwingCollector);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        long heartbeatsBefore = server.count(MessageType.HEARTBEAT);
        awaitCondition("heartbeat tiếp tục sau collector exception", AWAIT_TIMEOUT_MS, () ->
                server.count(MessageType.HEARTBEAT) > heartbeatsBefore);
        assertTrue(engine.isConnected());
        assertEquals(0, server.count(MessageType.ACTIVITY_REPORT),
                "Collector ném lỗi → không gửi báo cáo nào");
    }

    @Test
    @DisplayName("Collector trả payload rỗng → không gửi ACTIVITY_REPORT (không nhiễu nhật ký)")
    void emptyActivityNotSent() throws Exception {
        ActivityCollector emptyCollector = () ->
                new ActivityReportPayload("", "", java.util.List.of(), System.currentTimeMillis());

        engine = new TcpClientEngine(config(200), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { },
                null, null, null, null, null, emptyCollector);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        Thread.sleep(1_000); // qua vài chu kỳ activity
        assertEquals(0, server.count(MessageType.ACTIVITY_REPORT),
                "Payload rỗng không được gửi");
        assertTrue(engine.isConnected());
    }

    @Test
    @DisplayName("activityCollector null hoặc activityIntervalMs=0 → tắt giám sát (legacy behavior)")
    void disabledWhenNullCollectorOrZeroInterval() throws Exception {
        // collector null
        engine = new TcpClientEngine(config(500), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { },
                null, null, null, null, null, null);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        Thread.sleep(1_000);
        assertEquals(0, server.count(MessageType.ACTIVITY_REPORT), "Collector null → không gửi");
        engine.stop();

        // interval = 0 (tắt qua config) với collector có sẵn
        engine = new TcpClientEngine(config(0), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { },
                null, null, null, null, null,
                () -> new ActivityReportPayload("t", "a.exe", java.util.List.of("a.exe"), 1L));
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));
        Thread.sleep(1_000);
        assertEquals(0, server.count(MessageType.ACTIVITY_REPORT), "Interval 0 → không gửi");
        assertTrue(engine.isConnected(), "Engine vẫn hoạt động bình thường");
    }
}
