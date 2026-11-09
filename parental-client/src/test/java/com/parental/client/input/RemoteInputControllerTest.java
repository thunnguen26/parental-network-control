package com.parental.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/** Kiểm thử headless cho phiên điều khiển phía Client (PROTOCOL.md Mục 3.11). */
class RemoteInputControllerTest {

    private static final String SESSION = "session-1";

    private final List<RemoteInputAckPayload> acks = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000L);
    private FakeDriver driver;
    private FakePrompter prompter;
    private FakeIndicator indicator;
    private RemoteInputController controller;

    @BeforeEach
    void setUp() {
        driver = new FakeDriver();
        prompter = new FakePrompter();
        indicator = new FakeIndicator();
        controller = new RemoteInputController(driver, prompter, indicator, now::get);
        controller.setAckSender(acks::add);
    }

    @Test
    @DisplayName("Chưa chấp thuận: mọi INPUT_EVENT bị từ chối, không chạm driver")
    void eventsBeforeConsentAreRejected() {
        assertEquals(RemoteInputController.RequestResult.REQUESTED,
                controller.handleStartRequest(SESSION, 10, 60));
        assertEquals(RemoteInputController.State.WAITING_CONSENT, controller.getState());

        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1)));
        assertTrue(driver.actions.isEmpty(), "Không được inject trước khi chấp thuận");
        assertEquals(0, indicator.showCount);
    }

    @Test
    @DisplayName("Từ chối: không bật phiên, gửi INPUT_ACK DECLINED, không inject")
    void declineNeverStartsSession() {
        controller.handleStartRequest(SESSION, 10, 60);
        prompter.decide(ConsentPrompter.Decision.DECLINED);

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(RemoteInputAckPayload.STATUS_DECLINED, lastAck().status());
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1)));
        assertTrue(driver.actions.isEmpty());
        assertEquals(0, indicator.showCount);
    }

    @Test
    @DisplayName("Không phản hồi: hết hạn chờ → EXPIRED, không inject")
    void unansweredConsentExpires() {
        controller.handleStartRequest(SESSION, 5, 60);
        now.addAndGet(5_001L);
        controller.expireIfNeeded();

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(RemoteInputAckPayload.STATUS_EXPIRED, lastAck().status());
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1)));
        assertTrue(driver.actions.isEmpty());
    }

    @Test
    @DisplayName("Không có GUI xin phép: UNAVAILABLE, không tự chấp nhận")
    void unavailableWithoutConsentUi() {
        RemoteInputController headless = new RemoteInputController(driver, null, null, now::get);
        headless.setAckSender(acks::add);

        assertEquals(RemoteInputController.RequestResult.UNAVAILABLE,
                headless.handleStartRequest(SESSION, 10, 60));
        assertEquals(RemoteInputController.State.STOPPED, headless.getState());
        assertEquals(RemoteInputAckPayload.STATUS_UNAVAILABLE, lastAck().status());
        assertTrue(driver.actions.isEmpty());
    }

    @Test
    @DisplayName("Chấp thuận đúng phiên: sự kiện hợp lệ được chuyển tới driver")
    void acceptedSessionInjectsValidEvents() {
        controller.handleStartRequest(SESSION, 10, 60);
        prompter.decide(ConsentPrompter.Decision.ACCEPTED);

        assertEquals(RemoteInputController.State.ACTIVE, controller.getState());
        assertEquals(RemoteInputAckPayload.STATUS_ACCEPTED, lastAck().status());
        assertTrue(lastAck().expiresAtEpochMs() > now.get());
        assertEquals(1, indicator.showCount);

        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.MOUSE_MOVE, 1)));
        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_DOWN,
                        10, 10, 1, 100, 100, 2)));
        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 3)));

        assertEquals(List.of("MOUSE_MOVE", "MOUSE_MOVE", "MOUSE_DOWN", "KEY_DOWN"),
                driver.actions, "Mouse down phải di chuyển tới đúng tọa độ trước khi nhấn");
        assertEquals(0.1, driver.lastMoveX, 0.0001);
        assertEquals(0.1, driver.lastMoveY, 0.0001);
    }

    @Test
    @DisplayName("Mouse down/up dùng tọa độ của chính sự kiện, không phụ thuộc gói move trước")
    void mouseButtonEventsMoveToEventCoordinates() {
        accept();
        driver.actions.clear();

        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_DOWN,
                        90, 80, 1, 100, 100, 2)));
        assertEquals(List.of("MOUSE_MOVE", "MOUSE_DOWN"), driver.actions);
        assertEquals(0.9, driver.lastMoveX, 0.0001);
        assertEquals(0.8, driver.lastMoveY, 0.0001);

        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_UP,
                        20, 30, 1, 100, 100, 3)));
        assertEquals(List.of("MOUSE_MOVE", "MOUSE_DOWN", "MOUSE_MOVE", "MOUSE_UP"),
                driver.actions);
        assertEquals(0.2, driver.lastMoveX, 0.0001);
        assertEquals(0.3, driver.lastMoveY, 0.0001);
    }

    @Test
    @DisplayName("Chấp nhận nhưng máy vừa khóa/UAC: không bật phiên, báo UNAVAILABLE")
    void acceptWhileSecureDesktopDoesNotStart() {
        controller.handleStartRequest(SESSION, 10, 60);
        driver.ready = false;

        prompter.decide(ConsentPrompter.Decision.ACCEPTED);

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(RemoteInputAckPayload.STATUS_UNAVAILABLE, lastAck().status());
        assertEquals(0, indicator.showCount, "Không được hiện chỉ báo điều khiển");
        assertTrue(driver.actions.isEmpty(), "Không được inject input");
    }

    @Test
    @DisplayName("REQUEST lặp khi đang ACTIVE: gửi lại ACCEPTED để Server không timeout nhầm")
    void duplicateRequestWhileActiveResendsAcceptedAck() {
        accept();
        int acksBefore = acks.size();

        assertEquals(RemoteInputController.RequestResult.REQUESTED,
                controller.handleStartRequest(SESSION, 10, 60));

        assertEquals(RemoteInputController.State.ACTIVE, controller.getState());
        assertTrue(acks.size() > acksBefore, "Phải gửi lại INPUT_ACK ACCEPTED");
        assertEquals(RemoteInputAckPayload.STATUS_ACCEPTED, lastAck().status());
        assertEquals(SESSION, lastAck().sessionId());
    }

    @Test
    @DisplayName("Sai phiên/tọa độ/loại/nút/phím/frame/seq đều bị từ chối an toàn")
    void invalidEventsAreRejectedSafely() {
        accept();
        driver.actions.clear();

        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event("other-session", RemoteInputEventPayload.KEY_DOWN, 1)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_MOVE,
                        100, 10, 0, 100, 100, 2))); // x == width
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(event(SESSION, "PASTE_TEXT", 2)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(new RemoteInputEventPayload(SESSION,
                        RemoteInputEventPayload.KEY_DOWN, 0, 0, 0, 300, 1, 1, 2)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_DOWN,
                        10, 10, 9, 100, 100, 2)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_MOVE,
                        10, 10, 0, 5_000, 100, 2)));

        assertTrue(driver.actions.isEmpty(), "Không sự kiện lỗi nào được inject");
    }

    @Test
    @DisplayName("seqNo trùng/giảm bị từ chối (chống phát lại)")
    void staleSequenceIsRejected() {
        accept();
        assertEquals(RemoteInputController.EventResult.APPLIED,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 5)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_UP, 5)));
        assertEquals(RemoteInputController.EventResult.REJECTED_INVALID,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_UP, 4)));
        assertEquals(1, driver.actions.size());
    }

    @Test
    @DisplayName("Vượt giới hạn tốc độ: phần dư bị bỏ, không inject")
    void rateLimitDropsExcessEvents() {
        accept();
        driver.actions.clear();
        int applied = 0;
        int limited = 0;
        for (int i = 1; i <= RemoteInputController.MAX_EVENTS_PER_SECOND + 50; i++) {
            RemoteInputController.EventResult result = controller.handleEvent(
                    event(SESSION, RemoteInputEventPayload.KEY_DOWN, i));
            if (result == RemoteInputController.EventResult.APPLIED) {
                applied++;
            } else if (result == RemoteInputController.EventResult.REJECTED_RATE_LIMITED) {
                limited++;
            }
        }
        assertEquals(RemoteInputController.MAX_EVENTS_PER_SECOND, applied);
        assertEquals(50, limited);
        assertEquals(applied, driver.actions.size());
        // Phiên vẫn ACTIVE — chỉ bỏ sự kiện vượt tốc độ, không tự dừng.
        assertEquals(RemoteInputController.State.ACTIVE, controller.getState());
    }

    @Test
    @DisplayName("Dừng tại Client: nhả phím/nút, ẩn chỉ báo, thu hồi quyền ngay")
    void localStopReleasesInputAndRevokesSession() {
        accept();
        controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1));
        controller.handleEvent(mouse(SESSION, RemoteInputEventPayload.MOUSE_DOWN,
                10, 10, 1, 100, 100, 2));

        controller.handleLocalStop();

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(1, driver.releaseAllCount);
        assertEquals(1, indicator.hideCount);
        assertEquals(RemoteInputAckPayload.STATUS_STOPPED, lastAck().status());
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_UP, 3)));
    }

    @Test
    @DisplayName("Màn hình khóa/UAC/không còn phiên tương tác: dừng ngay, không inject")
    void secureDesktopMidSessionStopsInjection() {
        accept();
        driver.ready = false;

        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1)));
        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(1, driver.releaseAllCount);
        assertEquals(RemoteInputAckPayload.STATUS_REJECTED, lastAck().status());
        assertTrue(driver.actions.isEmpty());
    }

    @Test
    @DisplayName("STOP của phiên cũ không dừng phiên mới")
    void staleStopDoesNotAffectCurrentSession() {
        accept();
        assertFalse(controller.handleStopRequest("old-session"));
        assertEquals(RemoteInputController.State.ACTIVE, controller.getState());

        assertTrue(controller.handleStopRequest(SESSION));
        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
    }

    @Test
    @DisplayName("Hết thời lượng phiên: thu hồi quyền + nhả input")
    void sessionExpiryRevokesAndReleases() {
        controller.handleStartRequest(SESSION, 10, 1);
        prompter.decide(ConsentPrompter.Decision.ACCEPTED);
        controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1));

        now.addAndGet(1_001L);
        controller.expireIfNeeded();

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(RemoteInputAckPayload.STATUS_EXPIRED, lastAck().status());
        assertEquals(1, driver.releaseAllCount);
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_UP, 2)));
    }

    @Test
    @DisplayName("Đồng ý trễ sau khi đã thu hồi không khôi phục quyền (gói trễ)")
    void lateConsentCannotReviveStoppedSession() {
        controller.handleStartRequest(SESSION, 10, 60);
        controller.stopForConnectionLoss();

        prompter.decide(ConsentPrompter.Decision.ACCEPTED);

        assertEquals(RemoteInputController.State.STOPPED, controller.getState());
        assertEquals(0, indicator.showCount);
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 1)));
        assertTrue(driver.actions.isEmpty());
    }

    @Test
    @DisplayName("Yêu cầu mới thay thế phiên cũ, không nối lại quyền cũ")
    void newRequestReplacesOldSession() {
        accept();
        assertNotNull(controller.getSessionId());

        controller.handleStartRequest("session-2", 10, 60);

        assertEquals(RemoteInputController.State.WAITING_CONSENT, controller.getState());
        assertEquals("session-2", controller.getSessionId());
        assertEquals(RemoteInputController.EventResult.REJECTED_NOT_ACTIVE,
                controller.handleEvent(event(SESSION, RemoteInputEventPayload.KEY_DOWN, 2)));
        assertTrue(acks.stream().anyMatch(a -> RemoteInputAckPayload.STATUS_STOPPED
                .equals(a.status())), "Phiên cũ phải được thông báo dừng");
    }

    private void accept() {
        controller.handleStartRequest(SESSION, 10, 60);
        prompter.decide(ConsentPrompter.Decision.ACCEPTED);
    }

    private RemoteInputAckPayload lastAck() {
        assertFalse(acks.isEmpty(), "Phải có INPUT_ACK");
        return acks.get(acks.size() - 1);
    }

    private static RemoteInputEventPayload event(String sessionId, String type, long seq) {
        return new RemoteInputEventPayload(sessionId, type, 0, 0, 0, 65, 1, 1, seq);
    }

    private static RemoteInputEventPayload mouse(String sessionId, String type,
                                                 int x, int y, int button,
                                                 int width, int height, long seq) {
        return new RemoteInputEventPayload(sessionId, type, x, y, button, 0,
                width, height, seq);
    }

    // ------------------------------------------------------------------
    // Fakes — không tạo Robot/không mở cửa sổ
    // ------------------------------------------------------------------

    static final class FakeDriver implements InputInjectionDriver {
        final List<String> actions = new CopyOnWriteArrayList<>();
        int releaseAllCount;
        boolean ready = true;
        double lastMoveX;
        double lastMoveY;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void moveMouse(double normalizedX, double normalizedY) {
            actions.add("MOUSE_MOVE");
            lastMoveX = normalizedX;
            lastMoveY = normalizedY;
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
            releaseAllCount++;
        }
    }

    static final class FakePrompter implements ConsentPrompter {
        private Consumer<Decision> callback;
        int requestCount;
        boolean cancelled;

        @Override
        public void requestConsent(String sessionId, int timeoutSeconds, Consumer<Decision> callback) {
            requestCount++;
            this.callback = callback;
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
            cancelled = true;
        }
    }

    static final class FakeIndicator implements ControlIndicator {
        int showCount;
        int hideCount;
        private Runnable stopAction;

        @Override
        public void show(Runnable stopAction) {
            showCount++;
            this.stopAction = stopAction;
        }

        @Override
        public void hide() {
            hideCount++;
        }

        void triggerStop() {
            if (stopAction != null) {
                stopAction.run();
            }
        }
    }
}
