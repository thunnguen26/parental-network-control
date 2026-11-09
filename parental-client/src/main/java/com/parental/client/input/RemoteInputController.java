package com.parental.client.input;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/**
 * Phiên điều khiển chuột/bàn phím phía Client (Mục 3.11 của
 * {@code docs/PROTOCOL.md}).
 *
 * <p>Nguyên tắc an toàn:</p>
 * <ul>
 *   <li>Không bao giờ tự bật: chỉ chuyển sang {@link State#ACTIVE} sau khi
 *       {@link ConsentPrompter} nhận được lựa chọn Chấp nhận từ người dùng
 *       đang thao tác tại máy.</li>
 *   <li>Chỉ nhận {@code INPUT_EVENT} khi đang ACTIVE, đúng {@code sessionId},
 *       đúng thứ tự {@code seqNo}, đúng allowlist loại sự kiện, tọa độ nằm
 *       trong frame và trong giới hạn tốc độ.</li>
 *   <li>Luôn hiện {@link ControlIndicator} trong suốt phiên; người dùng có
 *       thể dừng ngay. Khi phiên kết thúc (dừng, từ chối, hết hạn, mất kết
 *       nối) luôn gọi {@link InputInjectionDriver#releaseAll()}.</li>
 *   <li>Không ghi log nội dung phím, không lưu input, không gửi input ngược
 *       lên Server.</li>
 * </ul>
 */
public final class RemoteInputController {

    private static final Logger log = LoggerFactory.getLogger(RemoteInputController.class);

    public static final int MAX_FRAME_DIMENSION = 4096;
    public static final int MAX_WHEEL_NOTCHES = 10;
    public static final int MAX_EVENTS_PER_SECOND = 200;
    public static final int MOUSE_MOVE_MIN_INTERVAL_MS = 16;

    public enum State {
        IDLE,
        WAITING_CONSENT,
        ACTIVE,
        STOPPED
    }

    public enum RequestResult {
        REQUESTED,
        REJECTED_INVALID,
        UNAVAILABLE
    }

    public enum EventResult {
        APPLIED,
        REJECTED_NOT_ACTIVE,
        REJECTED_INVALID,
        REJECTED_RATE_LIMITED
    }

    private final InputInjectionDriver driver;
    private final ConsentPrompter consentPrompter;
    private final ControlIndicator indicator;
    private final LongSupplier clock;

    private volatile Consumer<RemoteInputAckPayload> ackSender;

    private State state = State.IDLE;
    private String sessionId;
    private int maxSessionSeconds;
    private long consentDeadlineMs;
    private long sessionExpiresAtMs;
    private long acceptedAtMs;
    private long lastSeqNo;
    private String lastStatus = "";

    private ScheduledExecutorService timer;
    private ScheduledFuture<?> deadlineTask;

    private double rateTokens = MAX_EVENTS_PER_SECOND;
    private long rateRefillMs;
    private long lastMouseMoveMs = Long.MIN_VALUE / 4;

    public RemoteInputController(InputInjectionDriver driver,
                                 ConsentPrompter consentPrompter,
                                 ControlIndicator indicator,
                                 LongSupplier clock) {
        this.driver = driver;
        this.consentPrompter = consentPrompter;
        this.indicator = indicator;
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /**
     * Controller production: dùng Robot thật. Ở môi trường headless (test/CI)
     * không có consent GUI nên mọi yêu cầu sẽ trả {@code UNAVAILABLE} — tuyệt
     * đối không tự chấp nhận.
     */
    public static RemoteInputController createDefault() {
        InputInjectionDriver robotDriver = new RobotInputInjectionDriver();
        boolean headless;
        try {
            headless = GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            headless = true;
        }
        if (headless) {
            return new RemoteInputController(robotDriver, null, null, System::currentTimeMillis);
        }
        return new RemoteInputController(robotDriver,
                new SwingConsentPrompter(), new SwingControlIndicator(), System::currentTimeMillis);
    }

    /** Engine gắn callback gửi INPUT_ACK về Server; có thể đổi khi test. */
    public void setAckSender(Consumer<RemoteInputAckPayload> ackSender) {
        this.ackSender = ackSender;
    }

    /**
     * Server xin bật phiên. Nếu đang có phiên khác, phiên cũ bị thu hồi trước
     * (không thể “nối lại” quyền cũ bằng gói trễ).
     */
    public synchronized RequestResult handleStartRequest(String requestedSessionId,
                                                         int consentTimeoutSeconds,
                                                         int maxSessionSeconds) {
        expireIfNeeded();
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            log.warn("INPUT_CONTROL REQUEST rejected: missing sessionId");
            return RequestResult.REJECTED_INVALID;
        }
        if (state == State.WAITING_CONSENT && requestedSessionId.equals(sessionId)) {
            return RequestResult.REQUESTED; // gói lặp — giữ nguyên hộp thoại hiện tại
        }
        if (state == State.ACTIVE && requestedSessionId.equals(sessionId)) {
            // Gói REQUEST lặp (ví dụ ACK ACCEPTED bị mất): gửi lại ACCEPTED để
            // Server không chờ đến hết hạn trong khi Client đã ACTIVE.
            sendAck(requestedSessionId, RemoteInputAckPayload.STATUS_ACCEPTED,
                    "Người dùng tại Client đã chấp nhận", acceptedAtMs,
                    sessionExpiresAtMs);
            return RequestResult.REQUESTED;
        }
        if (state == State.WAITING_CONSENT || state == State.ACTIVE) {
            endSession(RemoteInputAckPayload.STATUS_STOPPED,
                    "Phiên điều khiển cũ bị thay thế", true);
        }

        this.sessionId = requestedSessionId;
        this.maxSessionSeconds = clamp(maxSessionSeconds, 1, 3600, 300);
        int consentSeconds = clamp(consentTimeoutSeconds, 1, 120, 20);
        this.consentDeadlineMs = now() + consentSeconds * 1000L;
        this.sessionExpiresAtMs = 0L;
        this.acceptedAtMs = 0L;
        this.lastSeqNo = 0L;
        this.lastStatus = "";
        resetRateLimiter();

        boolean available = driver != null && driver.isReady()
                && consentPrompter != null && indicator != null;
        if (!available) {
            state = State.STOPPED;
            lastStatus = "Client không có phiên tương tác để xin phép";
            log.warn("Remote input request {} rejected: {}", requestedSessionId, lastStatus);
            sendAck(requestedSessionId, RemoteInputAckPayload.STATUS_UNAVAILABLE,
                    lastStatus, 0L, 0L);
            this.sessionId = null;
            return RequestResult.UNAVAILABLE;
        }

        state = State.WAITING_CONSENT;
        lastStatus = "Đang chờ người dùng tại Client chấp nhận";
        scheduleDeadline(consentDeadlineMs, () -> onConsentTimeout(requestedSessionId));
        log.info("Remote input consent requested (sessionId={}, timeout={}s)",
                requestedSessionId, consentSeconds);
        ConsentPrompter prompter = consentPrompter;
        prompter.requestConsent(requestedSessionId, consentSeconds,
                decision -> onConsentDecision(requestedSessionId, decision));
        return RequestResult.REQUESTED;
    }

    private synchronized void onConsentDecision(String decidedSessionId,
                                               ConsentPrompter.Decision decision) {
        if (!decidedSessionId.equals(sessionId) || state != State.WAITING_CONSENT) {
            return; // gói trả lời trễ/không thuộc phiên hiện tại
        }
        if (decision == ConsentPrompter.Decision.ACCEPTED) {
            // Máy vừa khóa / UAC bật lên trong lúc hộp thoại đang mở: không
            // được bắt đầu phiên chỉ vì người dùng đã bấm Chấp nhận.
            if (driver == null || !driver.isReady()) {
                finishWithoutConsent(decidedSessionId, RemoteInputAckPayload.STATUS_UNAVAILABLE,
                        "Màn hình khóa/UAC hoặc không có phiên tương tác — không bắt đầu điều khiển");
                return;
            }
            cancelDeadline();
            acceptedAtMs = now();
            sessionExpiresAtMs = acceptedAtMs + maxSessionSeconds * 1000L;
            state = State.ACTIVE;
            lastStatus = "Đang điều khiển từ xa";
            scheduleDeadline(sessionExpiresAtMs,
                    () -> onSessionExpired(decidedSessionId));
            sendAck(decidedSessionId, RemoteInputAckPayload.STATUS_ACCEPTED,
                    "Người dùng tại Client đã chấp nhận", acceptedAtMs, sessionExpiresAtMs);
            ControlIndicator activeIndicator = indicator;
            if (activeIndicator != null) {
                activeIndicator.show(this::handleLocalStop);
            }
            log.info("Remote input session ACTIVE (sessionId={}, max={}s)",
                    decidedSessionId, maxSessionSeconds);
            return;
        }

        String status = decision == ConsentPrompter.Decision.EXPIRED
                ? RemoteInputAckPayload.STATUS_EXPIRED
                : decision == ConsentPrompter.Decision.UNAVAILABLE
                        ? RemoteInputAckPayload.STATUS_UNAVAILABLE
                        : RemoteInputAckPayload.STATUS_DECLINED;
        String message = switch (decision) {
            case ACCEPTED -> ""; // đã xử lý ở trên
            case DECLINED -> "Người dùng tại Client đã từ chối";
            case EXPIRED -> "Hết thời gian chờ người dùng chấp nhận";
            case UNAVAILABLE -> "Client không có GUI để xin phép";
        };
        finishWithoutConsent(decidedSessionId, status, message);
    }

    private synchronized void onConsentTimeout(String timedOutSessionId) {
        if (!timedOutSessionId.equals(sessionId) || state != State.WAITING_CONSENT) {
            return;
        }
        finishWithoutConsent(timedOutSessionId, RemoteInputAckPayload.STATUS_EXPIRED,
                "Hết thời gian chờ người dùng chấp nhận");
    }

    private void finishWithoutConsent(String finishedSessionId, String status, String message) {
        cancelDeadline();
        state = State.STOPPED;
        lastStatus = message;
        ConsentPrompter prompter = consentPrompter;
        if (prompter != null) {
            try {
                prompter.cancel();
            } catch (RuntimeException e) {
                log.debug("Consent prompter cancel ignored: {}", e.getMessage());
            }
        }
        sendAck(finishedSessionId, status, message, 0L, 0L);
        sessionId = null;
        releaseAllSafely();
        log.warn("Remote input NOT started ({}): {}", status, message);
    }

    private synchronized void onSessionExpired(String expiredSessionId) {
        if (!expiredSessionId.equals(sessionId) || state != State.ACTIVE) {
            return;
        }
        endSession(RemoteInputAckPayload.STATUS_EXPIRED,
                "Phiên điều khiển hết hạn — cần đồng ý lại", true);
    }

    /**
     * Xử lý một sự kiện input. Chỉ inject khi phiên ACTIVE hợp lệ. Mọi trường
     * hợp từ chối đều an toàn, không chạm tới driver.
     */
    public synchronized EventResult handleEvent(RemoteInputEventPayload event) {
        expireIfNeeded();
        if (event == null) {
            return EventResult.REJECTED_INVALID;
        }
        if (state != State.ACTIVE || sessionId == null
                || !sessionId.equals(event.sessionId())) {
            return EventResult.REJECTED_NOT_ACTIVE;
        }
        // Màn hình khóa/UAC/desktop bảo mật (hoặc mất phiên tương tác): dừng
        // ngay, không inject. Không bao giờ cố vượt qua bảo vệ của Windows.
        if (driver == null || !driver.isReady()) {
            endSession(RemoteInputAckPayload.STATUS_REJECTED,
                    "Màn hình khóa/UAC hoặc không có phiên tương tác — đã dừng điều khiển",
                    true);
            return EventResult.REJECTED_NOT_ACTIVE;
        }
        if (!RemoteInputEventPayload.isSupportedEventType(event.eventType())) {
            return EventResult.REJECTED_INVALID;
        }
        if (event.seqNo() <= lastSeqNo || event.seqNo() <= 0) {
            return EventResult.REJECTED_INVALID; // phát lại/gói trễ
        }
        if (!validGeometry(event)) {
            return EventResult.REJECTED_INVALID;
        }
        lastSeqNo = event.seqNo(); // đã kiểm tra hợp lệ — gói lỗi tốc độ vẫn tiêu thụ seq
        if (!allowEvent(now(), RemoteInputEventPayload.MOUSE_MOVE.equals(event.eventType()))) {
            log.debug("Remote input event dropped by rate limit (sessionId={})", sessionId);
            return EventResult.REJECTED_RATE_LIMITED;
        }
        try {
            inject(event);
            return EventResult.APPLIED;
        } catch (RuntimeException e) {
            log.error("Remote input injection failed (sessionId={}): {}",
                    sessionId, e.getMessage());
            endSession(RemoteInputAckPayload.STATUS_REJECTED,
                    "Lỗi inject input — phiên đã dừng", true);
            return EventResult.REJECTED_INVALID;
        }
    }

    private boolean validGeometry(RemoteInputEventPayload event) {
        int width = event.frameWidth();
        int height = event.frameHeight();
        if (width <= 0 || height <= 0
                || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) {
            return false;
        }
        if (event.isMouseEvent()) {
            if (event.x() < 0 || event.y() < 0
                    || event.x() >= width || event.y() >= height) {
                return false;
            }
        }
        return switch (event.eventType()) {
            case RemoteInputEventPayload.MOUSE_MOVE -> true;
            case RemoteInputEventPayload.MOUSE_DOWN,
                 RemoteInputEventPayload.MOUSE_UP -> event.button() >= 1 && event.button() <= 3;
            case RemoteInputEventPayload.MOUSE_WHEEL ->
                    event.button() != 0 && Math.abs(event.button()) <= MAX_WHEEL_NOTCHES;
            case RemoteInputEventPayload.KEY_DOWN,
                 RemoteInputEventPayload.KEY_UP -> event.keyCode() >= 1 && event.keyCode() <= 255;
            default -> false;
        };
    }

    private void inject(RemoteInputEventPayload event) {
        double nx = event.x() / (double) event.frameWidth();
        double ny = event.y() / (double) event.frameHeight();
        switch (event.eventType()) {
            case RemoteInputEventPayload.MOUSE_MOVE -> driver.moveMouse(nx, ny);
            // Di chuyển tới đúng tọa độ của sự kiện trước khi nhấn/nhả: nếu chỉ
            // dựa vào MOUSE_MOVE trước đó, một gói move bị mất có thể làm cú
            // click rơi sai vị trí. Toạ độ đã được validate trong frame.
            case RemoteInputEventPayload.MOUSE_DOWN -> {
                driver.moveMouse(nx, ny);
                driver.pressMouse(event.button());
            }
            case RemoteInputEventPayload.MOUSE_UP -> {
                driver.moveMouse(nx, ny);
                driver.releaseMouse(event.button());
            }
            case RemoteInputEventPayload.MOUSE_WHEEL ->
                    driver.wheel(nx, ny, event.button());
            case RemoteInputEventPayload.KEY_DOWN -> driver.pressKey(event.keyCode());
            case RemoteInputEventPayload.KEY_UP -> driver.releaseKey(event.keyCode());
            default -> throw new IllegalArgumentException(
                    "Unsupported event type: " + event.eventType());
        }
    }

    /** Server gửi STOP — chỉ có hiệu lực cho đúng phiên hiện tại. */
    public synchronized boolean handleStopRequest(String stoppedSessionId) {
        if (stoppedSessionId == null || !stoppedSessionId.equals(sessionId)
                || state == State.IDLE || state == State.STOPPED) {
            return false;
        }
        endSession(RemoteInputAckPayload.STATUS_STOPPED,
                "Server đã dừng điều khiển", true);
        return true;
    }

    /** Người dùng tại Client bấm “Dừng điều khiển” / đóng chỉ báo. */
    public synchronized void handleLocalStop() {
        if (state == State.ACTIVE || state == State.WAITING_CONSENT) {
            endSession(RemoteInputAckPayload.STATUS_STOPPED,
                    "Người dùng tại Client đã dừng điều khiển", true);
        }
    }

    /**
     * Mất kết nối/engine dừng: thu hồi quyền và nhả input. Không gửi ACK vì
     * socket đã đóng.
     */
    public synchronized void stopForConnectionLoss() {
        if (state == State.ACTIVE || state == State.WAITING_CONSENT) {
            endSession(RemoteInputAckPayload.STATUS_STOPPED,
                    "Mất kết nối — phiên điều khiển đã dừng", false);
        }
    }

    /** Dọn tài nguyên controller (engine shutdown). */
    public synchronized void shutdown() {
        stopForConnectionLoss();
    }

    private void endSession(String status, String message, boolean notifyPeer) {
        String endedSessionId = sessionId;
        cancelDeadline();
        state = State.STOPPED;
        lastStatus = message;
        sessionId = null;
        sessionExpiresAtMs = 0L;
        ConsentPrompter prompter = consentPrompter;
        if (prompter != null) {
            try {
                prompter.cancel();
            } catch (RuntimeException e) {
                log.debug("Consent prompter cancel ignored: {}", e.getMessage());
            }
        }
        ControlIndicator hiddenIndicator = indicator;
        if (hiddenIndicator != null) {
            try {
                hiddenIndicator.hide();
            } catch (RuntimeException e) {
                log.debug("Control indicator hide ignored: {}", e.getMessage());
            }
        }
        releaseAllSafely();
        if (notifyPeer && endedSessionId != null && !endedSessionId.isBlank()) {
            sendAck(endedSessionId, status, message, 0L, 0L);
        }
        log.info("Remote input session ended ({}): {}", status, message);
    }

    private void releaseAllSafely() {
        if (driver == null) {
            return;
        }
        try {
            driver.releaseAll();
        } catch (RuntimeException e) {
            log.warn("releaseAll input failed: {}", e.getMessage());
        }
    }

    /** Hết hạn chờ đồng ý hoặc hết thời lượng phiên. */
    public synchronized void expireIfNeeded() {
        long now = now();
        if (state == State.WAITING_CONSENT && now >= consentDeadlineMs) {
            finishWithoutConsent(sessionId, RemoteInputAckPayload.STATUS_EXPIRED,
                    "Hết thời gian chờ người dùng chấp nhận");
            return;
        }
        if (state == State.ACTIVE && sessionExpiresAtMs > 0 && now >= sessionExpiresAtMs) {
            endSession(RemoteInputAckPayload.STATUS_EXPIRED,
                    "Phiên điều khiển hết hạn — cần đồng ý lại", true);
        }
    }

    public synchronized State getState() {
        expireIfNeeded();
        return state;
    }

    public synchronized String getSessionId() {
        return sessionId;
    }

    public synchronized String getLastStatus() {
        return lastStatus;
    }

    public synchronized long getSessionExpiresAtMs() {
        return sessionExpiresAtMs;
    }

    private boolean allowEvent(long now, boolean mouseMove) {
        long elapsed = Math.max(0L, now - rateRefillMs);
        if (elapsed > 0) {
            rateTokens = Math.min(MAX_EVENTS_PER_SECOND,
                    rateTokens + elapsed * (MAX_EVENTS_PER_SECOND / 1000.0));
            rateRefillMs = now;
        }
        if (mouseMove && now - lastMouseMoveMs < MOUSE_MOVE_MIN_INTERVAL_MS) {
            return false;
        }
        if (rateTokens < 1.0) {
            return false;
        }
        rateTokens -= 1.0;
        if (mouseMove) {
            lastMouseMoveMs = now;
        }
        return true;
    }

    private void resetRateLimiter() {
        rateTokens = MAX_EVENTS_PER_SECOND;
        rateRefillMs = now();
        lastMouseMoveMs = Long.MIN_VALUE / 4;
    }

    private void scheduleDeadline(long atMs, Runnable action) {
        cancelDeadline();
        long delay = Math.max(0L, atMs - now());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "parental-client-input-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer = scheduler;
        deadlineTask = scheduler.schedule(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Remote input deadline task failed: {}", e.getMessage());
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelDeadline() {
        ScheduledFuture<?> task = deadlineTask;
        deadlineTask = null;
        if (task != null) {
            task.cancel(false);
        }
        ScheduledExecutorService scheduler = timer;
        timer = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void sendAck(String ackSessionId, String status, String message,
                         long acceptedAt, long expiresAt) {
        Consumer<RemoteInputAckPayload> sender = ackSender;
        if (sender == null) {
            return;
        }
        try {
            sender.accept(new RemoteInputAckPayload(ackSessionId, status, message,
                    acceptedAt, expiresAt));
        } catch (RuntimeException e) {
            log.warn("Failed to send INPUT_ACK ({}): {}", status, e.getMessage());
        }
    }

    private long now() {
        return clock.getAsLong();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
