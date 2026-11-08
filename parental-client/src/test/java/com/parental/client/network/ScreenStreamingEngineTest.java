package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.client.config.ClientConfig;
import com.parental.client.screen.ScreenCaptureService;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;

/**
 * Regression test cho Live Screen Streaming (PROTOCOL.md Mục 3.10) phía Client.
 *
 * <p><b>Lỗi đã sửa:</b> {@link TcpClientEngine} khởi tạo
 * {@code screenCaptureService = null} trong constructor đầy đủ, nên mọi
 * {@code STREAM_CONTROL START} bị bỏ qua — client không bao giờ gửi
 * {@code STREAM_FRAME} dù server mở RemoteScreenDialog.</p>
 *
 * <p>Test dùng {@link FakeTcpServer} + {@link FakeScreenCaptureService}
 * (không cần display/Robot), nên chạy được headless trong Maven.</p>
 */
@Timeout(60)
class ScreenStreamingEngineTest {

    private static final long AWAIT_TIMEOUT_MS = 15_000;

    private final JsonProtocolParser parser = new JsonProtocolParser();
    private FakeTcpServer server;
    private TcpClientEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    /** Capture service giả: không chụp thật, chỉ trả frame 1x1 JPEG giả. */
    static final class FakeScreenCaptureService extends ScreenCaptureService {
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicInteger seq = new AtomicInteger();
        private final AtomicReference<ScreenControlRequest> request =
                new AtomicReference<>(ScreenControlRequest.DEFAULT);
        final AtomicInteger framesCaptured = new AtomicInteger();

        @Override
        public void start(ScreenControlRequest newRequest) {
            if (newRequest != null) {
                request.set(newRequest);
            }
            started.set(true);
            seq.set(0);
        }

        @Override
        public void stop() {
            started.set(false);
        }

        @Override
        public boolean isStarted() {
            return started.get();
        }

        @Override
        public ScreenControlRequest currentRequest() {
            return request.get();
        }

        @Override
        public ScreenFramePayload captureFrame() {
            if (!started.get()) {
                return null;
            }
            framesCaptured.incrementAndGet();
            return new ScreenFramePayload(1, 1, "AA==", 0.5,
                    seq.getAndIncrement(), System.currentTimeMillis());
        }
    }

    private void startEngine(FakeScreenCaptureService captureService) throws Exception {
        server = new FakeTcpServer();
        ClientConfig config = ClientConfig.builder()
                .serverHost("127.0.0.1")
                .serverPort(server.getPort())
                .clientId("stream-client-1")
                .heartbeatIntervalMs(5_000)
                .metricsIntervalMs(5_000)
                .activityIntervalMs(0)
                .build();
        engine = new TcpClientEngine(
                config,
                () -> new HandshakePayload("PC-Stream", "DESKTOP", "Windows 11",
                        "127.0.0.1", "AA-11", null),
                parser,
                new ServerCommandHandler() { },
                null, null, null, null, null, null, null,
                captureService);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS), "Handshake phải tới server giả");
    }

    private void sendStreamControl(String action, long fps)
            throws com.parental.common.protocol.ProtocolParseException {
        assertTrue(server.pushLine(parser.toLine(BaseMessage.of(MessageType.STREAM_CONTROL,
                "fake-server", new StreamControlPayload(action, 64, 48, 0.5, fps)))),
                "Server giả phải đẩy được STREAM_CONTROL");
    }

    @Test
    @DisplayName("STREAM_CONTROL START → client gửi STREAM_FRAME; STOP → dừng gửi")
    void startStreamSendsFramesAndStopStopsThem() throws Exception {
        FakeScreenCaptureService capture = new FakeScreenCaptureService();
        startEngine(capture);

        sendStreamControl(StreamControlPayload.ACTION_START, 10);
        awaitCondition("frame STREAM_FRAME đầu tiên", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.STREAM_FRAME) >= 1);
        assertTrue(capture.isStarted(), "Screen capture phải được bật khi START");

        sendStreamControl(StreamControlPayload.ACTION_STOP, 10);
        awaitCondition("capture service dừng", AWAIT_TIMEOUT_MS, () -> !capture.isStarted());
        long afterStop = server.count(MessageType.STREAM_FRAME);
        Thread.sleep(600);
        assertEquals(afterStop, server.count(MessageType.STREAM_FRAME),
                "Sau STOP không được gửi thêm frame");
    }

    @Test
    @DisplayName("Mất kết nối khi đang stream → client dừng chụp (không stream lại sau reconnect)")
    void connectionLossStopsCapture() throws Exception {
        FakeScreenCaptureService capture = new FakeScreenCaptureService();
        startEngine(capture);

        sendStreamControl(StreamControlPayload.ACTION_START, 10);
        awaitCondition("stream đang chạy", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.STREAM_FRAME) >= 1 && capture.isStarted());

        // Server đóng đột ngột (ra ngoài, mất mạng...) — KHÔNG gửi STREAM_CONTROL STOP
        server.close();

        awaitCondition("capture dừng khi mất kết nối", AWAIT_TIMEOUT_MS, () -> !capture.isStarted());
    }

    @Test
    @DisplayName("START lặp không nhân đôi tần suất frame (không rò scheduler)")
    void repeatedStartDoesNotDoubleFrameRate() throws Exception {
        FakeScreenCaptureService capture = new FakeScreenCaptureService();
        startEngine(capture);

        sendStreamControl(StreamControlPayload.ACTION_START, 5); // 200 ms/frame
        awaitCondition("frame đầu tiên", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.STREAM_FRAME) >= 1);
        sendStreamControl(StreamControlPayload.ACTION_START, 5); // START lặp

        Thread.sleep(1_200);
        long frames = server.count(MessageType.STREAM_FRAME);
        // 1 scheduler ≈ 6 frame/1.2s; 2 scheduler ≈ 12. Ngưỡng 9 phân biệt rõ.
        assertTrue(frames <= 9,
                "START lặp không được tạo scheduler thứ 2 (nhận " + frames + " frame/1.2s)");
    }

    @Test
    @DisplayName("Engine tạo sẵn ScreenCaptureService (constructor production)")
    void engineAlwaysHasScreenCaptureService() throws Exception {
        FakeTcpServer fakeServer = new FakeTcpServer();
        try {
            ClientConfig config = ClientConfig.builder()
                    .serverHost("127.0.0.1")
                    .serverPort(fakeServer.getPort())
                    .clientId("stream-client-2")
                    .activityIntervalMs(0)
                    .build();
            TcpClientEngine productionEngine = new TcpClientEngine(
                    config,
                    () -> new HandshakePayload("PC-Stream", "DESKTOP", "Windows 11",
                            "127.0.0.1", "AA-12", null),
                    parser,
                    new ServerCommandHandler() { },
                    null, null, null, null, null, null, null);

            Field field = TcpClientEngine.class.getDeclaredField("screenCaptureService");
            field.setAccessible(true);
            assertNotNull(field.get(productionEngine),
                    "screenCaptureService phải được khởi tạo, nếu null thì Live Screen không bao giờ chạy");
        } finally {
            fakeServer.close();
        }
    }

    private static void awaitCondition(String description, long timeoutMs,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofMillis(timeoutMs));
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
}
