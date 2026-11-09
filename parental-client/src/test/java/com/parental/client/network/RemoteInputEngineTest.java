package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.client.config.ClientConfig;
import com.parental.client.input.ConsentPrompter;
import com.parental.client.input.ControlIndicator;
import com.parental.client.input.InputInjectionDriver;
import com.parental.client.input.RemoteInputController;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/**
 * Integration test qua TCP thật (server giả) cho điều khiển chuột/bàn phím:
 * từ chối/không phản hồi → không inject; chấp thuận → inject; STOP/mất kết nối
 * → thu hồi + nhả input. Không chạm Robot/không mở cửa sổ.
 */
@Timeout(60)
class RemoteInputEngineTest {

    private static final long AWAIT_TIMEOUT_MS = 10_000;

    private final JsonProtocolParser parser = new JsonProtocolParser();
    private final FakeDriver driver = new FakeDriver();
    private final FakePrompter prompter = new FakePrompter();
    private final FakeIndicator indicator = new FakeIndicator();
    private FakeTcpServer server;
    private TcpClientEngine engine;
    private RemoteInputController controller;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("Chưa chấp thuận hoặc từ chối: không có input nào được áp dụng")
    void noInjectionWithoutConsent() throws Exception {
        startEngine();
        pushEvent("s1", RemoteInputEventPayload.KEY_DOWN, 1);
        Thread.sleep(400);
        assertEquals(0, driver.actions.size(), "Chưa yêu cầu đồng ý → không inject");

        pushControl(RemoteInputControlPayload.ACTION_REQUEST, "s1", 10, 60);
        awaitCondition("hộp thoại xin phép", AWAIT_TIMEOUT_MS,
                () -> prompter.requestCount.get() == 1);
        pushEvent("s1", RemoteInputEventPayload.KEY_DOWN, 2);
        Thread.sleep(400);
        assertEquals(0, driver.actions.size(), "Đang chờ chấp thuận → không inject");

        prompter.decide(ConsentPrompter.Decision.DECLINED);
        awaitCondition("INPUT_ACK DECLINED", AWAIT_TIMEOUT_MS,
                () -> hasAck(RemoteInputAckPayload.STATUS_DECLINED));

        pushEvent("s1", RemoteInputEventPayload.KEY_DOWN, 3);
        Thread.sleep(400);
        assertEquals(0, driver.actions.size(), "Đã từ chối → không inject");
        assertEquals(0, indicator.showCount.get());
    }

    @Test
    @DisplayName("Chấp thuận: sự kiện hợp lệ tới driver; STOP thu hồi ngay")
    void acceptedSessionInjectsUntilStop() throws Exception {
        startEngine();
        pushControl(RemoteInputControlPayload.ACTION_REQUEST, "s2", 10, 60);
        awaitCondition("hộp thoại xin phép", AWAIT_TIMEOUT_MS,
                () -> prompter.requestCount.get() == 1);
        prompter.decide(ConsentPrompter.Decision.ACCEPTED);
        awaitCondition("INPUT_ACK ACCEPTED", AWAIT_TIMEOUT_MS,
                () -> hasAck(RemoteInputAckPayload.STATUS_ACCEPTED));
        assertEquals(RemoteInputController.State.ACTIVE, controller.getState());
        assertEquals(1, indicator.showCount.get());

        pushEvent("s2", RemoteInputEventPayload.MOUSE_MOVE, 1);
        awaitCondition("mouse move tới driver", AWAIT_TIMEOUT_MS,
                () -> driver.count("MOUSE_MOVE") >= 1);
        pushEvent("s2", RemoteInputEventPayload.KEY_DOWN, 2);
        awaitCondition("key down tới driver", AWAIT_TIMEOUT_MS,
                () -> driver.count("KEY_DOWN") >= 1);

        pushControl(RemoteInputControlPayload.ACTION_STOP, "s2", 0, 0);
        awaitCondition("phiên dừng sau STOP", AWAIT_TIMEOUT_MS,
                () -> controller.getState() == RemoteInputController.State.STOPPED);
        assertTrue(driver.releaseAllCount.get() >= 1, "Phải nhả phím/nút khi dừng");

        pushEvent("s2", RemoteInputEventPayload.KEY_DOWN, 3);
        Thread.sleep(400);
        assertEquals(1, driver.count("KEY_DOWN"), "Sau STOP không được inject thêm");
    }

    @Test
    @DisplayName("Mất kết nối: thu hồi phiên, nhả input, không giữ quyền sau reconnect")
    void connectionLossRevokesControl() throws Exception {
        startEngine();
        pushControl(RemoteInputControlPayload.ACTION_REQUEST, "s3", 10, 60);
        awaitCondition("hộp thoại xin phép", AWAIT_TIMEOUT_MS,
                () -> prompter.requestCount.get() == 1);
        prompter.decide(ConsentPrompter.Decision.ACCEPTED);
        awaitCondition("phiên ACTIVE", AWAIT_TIMEOUT_MS,
                () -> controller.getState() == RemoteInputController.State.ACTIVE);

        pushEvent("s3", RemoteInputEventPayload.KEY_DOWN, 1);
        awaitCondition("key down tới driver", AWAIT_TIMEOUT_MS,
                () -> driver.count("KEY_DOWN") >= 1);

        server.close();
        awaitCondition("thu hồi khi mất kết nối", AWAIT_TIMEOUT_MS,
                () -> controller.getState() != RemoteInputController.State.ACTIVE
                        && driver.releaseAllCount.get() >= 1);
        assertEquals(1, indicator.hideCount.get());
        assertEquals(1, driver.count("KEY_DOWN"), "Không inject thêm sau khi mất kết nối");
    }

    @Test
    @DisplayName("Không ảnh hưởng handshake/heartbeat sau khi gói điều khiển bị từ chối")
    void rejectedControlDoesNotBreakSession() throws Exception {
        startEngine();
        pushControl(RemoteInputControlPayload.ACTION_REQUEST, "s4", 10, 60);
        awaitCondition("hộp thoại xin phép", AWAIT_TIMEOUT_MS,
                () -> prompter.requestCount.get() == 1);
        prompter.decide(ConsentPrompter.Decision.DECLINED);
        awaitCondition("INPUT_ACK DECLINED", AWAIT_TIMEOUT_MS,
                () -> hasAck(RemoteInputAckPayload.STATUS_DECLINED));

        awaitCondition("heartbeat vẫn chạy", AWAIT_TIMEOUT_MS,
                () -> server.count(MessageType.HEARTBEAT) >= 1);
        assertTrue(engine.isConnected(), "Từ chối điều khiển không được làm hỏng kết nối");
    }

    // ------------------------------------------------------------------

    private void startEngine() throws Exception {
        server = new FakeTcpServer();
        ClientConfig config = ClientConfig.builder()
                .serverHost("127.0.0.1")
                .serverPort(server.getPort())
                .clientId("input-client-1")
                .heartbeatIntervalMs(2_000)
                .metricsIntervalMs(5_000)
                .activityIntervalMs(0)
                .build();
        controller = new RemoteInputController(driver, prompter, indicator,
                System::currentTimeMillis);
        engine = new TcpClientEngine(
                config,
                () -> new HandshakePayload("PC-Input", "DESKTOP", "Windows 11",
                        "127.0.0.1", "AA-21", null),
                parser,
                new ServerCommandHandler() { },
                null, null, null, null, null, null, null, null,
                controller);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS), "Handshake phải tới server giả");
    }

    private void pushControl(String action, String sessionId, int ttl, int maxSeconds)
            throws ProtocolParseException {
        assertTrue(server.pushLine(parser.toLine(BaseMessage.of(MessageType.INPUT_CONTROL,
                        "fake-server", new RemoteInputControlPayload(sessionId, action,
                                ttl, maxSeconds)))),
                "Server giả phải đẩy được INPUT_CONTROL");
    }

    private void pushEvent(String sessionId, String eventType, long seqNo)
            throws ProtocolParseException {
        RemoteInputEventPayload event = new RemoteInputEventPayload(sessionId, eventType,
                10, 10, 0, 65, 100, 100, seqNo);
        assertTrue(server.pushLine(parser.toLine(BaseMessage.of(MessageType.INPUT_EVENT,
                "fake-server", event))), "Server giả phải đẩy được INPUT_EVENT");
    }

    /** ACK phải thực sự đi qua TCP tới server giả (engine sở hữu ack sender). */
    private boolean hasAck(String status) {
        return server.received.stream()
                .filter(message -> message.getType() == MessageType.INPUT_ACK)
                .map(BaseMessage::getPayload)
                .filter(RemoteInputAckPayload.class::isInstance)
                .map(RemoteInputAckPayload.class::cast)
                .anyMatch(ack -> status.equals(ack.status()));
    }

    private static void awaitCondition(String description, long timeoutMs,
                                       BooleanSupplier condition) {
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

    static final class FakeDriver implements InputInjectionDriver {
        final List<String> actions = new CopyOnWriteArrayList<>();
        final AtomicInteger releaseAllCount = new AtomicInteger();

        long count(String action) {
            return actions.stream().filter(action::equals).count();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void moveMouse(double normalizedX, double normalizedY) {
            actions.add("MOUSE_MOVE");
        }

        @Override
        public void pressMouse(int button) {
            actions.add("MOUSE_DOWN");
        }

        @Override
        public void releaseMouse(int button) {
            actions.add("MOUSE_UP");
        }

        @Override
        public void wheel(double normalizedX, double normalizedY, int notches) {
            actions.add("MOUSE_WHEEL");
        }

        @Override
        public void pressKey(int keyCode) {
            actions.add("KEY_DOWN");
        }

        @Override
        public void releaseKey(int keyCode) {
            actions.add("KEY_UP");
        }

        @Override
        public void releaseAll() {
            releaseAllCount.incrementAndGet();
        }
    }

    static final class FakePrompter implements ConsentPrompter {
        final AtomicInteger requestCount = new AtomicInteger();
        private volatile Consumer<Decision> callback;

        @Override
        public void requestConsent(String sessionId, int timeoutSeconds, Consumer<Decision> callback) {
            this.callback = callback;
            requestCount.incrementAndGet();
        }

        void decide(Decision decision) {
            Consumer<Decision> pending = callback;
            callback = null;
            if (pending != null) {
                pending.accept(decision);
            }
        }

        @Override
        public void cancel() {
            callback = null;
        }
    }

    static final class FakeIndicator implements ControlIndicator {
        final AtomicInteger showCount = new AtomicInteger();
        final AtomicInteger hideCount = new AtomicInteger();

        @Override
        public void show(Runnable stopAction) {
            showCount.incrementAndGet();
        }

        @Override
        public void hide() {
            hideCount.incrementAndGet();
        }
    }
}
