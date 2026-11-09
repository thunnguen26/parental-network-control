package com.parental.server.remoteinput;

import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/**
 * Trạng thái phiên điều khiển chuột/bàn phím phía Server (Mục 3.11 của
 * {@code docs/PROTOCOL.md}).
 *
 * <p>Chỉ gửi {@code INPUT_EVENT} sau khi Client đã ACK {@code ACCEPTED} đúng
 * {@code sessionId}. Phiên tự dừng khi hết hạn chờ đồng ý, hết thời lượng,
 * viewer đóng, thiết bị offline hoặc gửi thất bại. Gói ACK/EVENT của phiên cũ
 * không bao giờ khôi phục quyền điều khiển.</p>
 *
 * <p>Không phụ thuộc Swing/ClientSession để chạy test headless — transport được
 * trừu tượng hóa qua {@link Transport}.</p>
 */
public final class ServerRemoteInputController {

    private static final Logger log = LoggerFactory.getLogger(ServerRemoteInputController.class);

    public static final int MAX_FRAME_DIMENSION = 4096;
    public static final int MAX_WHEEL_NOTCHES = 10;
    public static final int MAX_EVENTS_PER_SECOND = 250;
    public static final int MOUSE_MOVE_MIN_INTERVAL_MS = 10;

    public enum State {
        IDLE,
        WAITING_CONSENT,
        ACTIVE,
        STOPPED
    }

    public enum SendResult {
        SENT,
        NOT_ACTIVE,
        INVALID,
        RATE_LIMITED,
        SEND_FAILED
    }

    /** Cách gửi gói tin tới đúng Client; test dùng fake. */
    public interface Transport {
        boolean isOnline();

        boolean send(BaseMessage<?> message);
    }

    private final String clientId;
    private final Transport transport;
    private final LongSupplier clock;
    private final int consentTimeoutSeconds;
    private final int maxSessionSeconds;

    private State state = State.IDLE;
    private String sessionId;
    private long consentDeadlineMs;
    private long sessionExpiresAtMs;
    private long lastSeqNo;
    private String lastStatus = "Chưa yêu cầu điều khiển";

    private double rateTokens = MAX_EVENTS_PER_SECOND;
    private long rateRefillMs;
    private long lastMouseMoveMs = Long.MIN_VALUE / 4;

    public ServerRemoteInputController(String clientId, Transport transport) {
        this(clientId, transport, System::currentTimeMillis,
                RemoteInputControlPayload.DEFAULT_CONSENT_TIMEOUT_SECONDS,
                RemoteInputControlPayload.DEFAULT_MAX_SESSION_SECONDS);
    }

    public ServerRemoteInputController(String clientId, Transport transport, LongSupplier clock,
                                       int consentTimeoutSeconds, int maxSessionSeconds) {
        this.clientId = clientId;
        this.transport = transport;
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.consentTimeoutSeconds = Math.max(1, Math.min(120, consentTimeoutSeconds));
        this.maxSessionSeconds = Math.max(1, Math.min(3600, maxSessionSeconds));
    }

    /**
     * Xin bật phiên: sinh sessionId, gửi REQUEST. Trả sessionId hoặc null nếu
     * thiết bị offline/gửi lỗi. Không tự coi là đã bật.
     */
    public synchronized String requestControl() {
        expireIfNeeded();
        if (state == State.WAITING_CONSENT || state == State.ACTIVE) {
            return sessionId; // đã có yêu cầu/phiên đang chạy
        }
        if (transport == null || !transport.isOnline()) {
            state = State.STOPPED;
            lastStatus = "Thiết bị không trực tuyến — không thể điều khiển";
            log.info("[{}] Remote input request rejected: client offline", clientId);
            return null;
        }
        String newSessionId = UUID.randomUUID().toString();
        sessionId = newSessionId;
        state = State.WAITING_CONSENT;
        consentDeadlineMs = now() + consentTimeoutSeconds * 1000L;
        sessionExpiresAtMs = 0L;
        lastSeqNo = 0L;
        resetRateLimiter();
        lastStatus = "Đang chờ Client đồng ý...";
        boolean sent = transport.send(BaseMessage.of(MessageType.INPUT_CONTROL, "server",
                new RemoteInputControlPayload(newSessionId, RemoteInputControlPayload.ACTION_REQUEST,
                        consentTimeoutSeconds, maxSessionSeconds)));
        if (!sent) {
            state = State.STOPPED;
            sessionId = null;
            lastStatus = "Không gửi được yêu cầu điều khiển tới Client";
            log.warn("[{}] INPUT_CONTROL REQUEST could not be sent", clientId);
            return null;
        }
        log.info("[{}] Remote input consent requested (sessionId={})", clientId, newSessionId);
        return newSessionId;
    }

    /**
     * Xử lý ACK từ Client. Chỉ ACCEPTED đúng sessionId đang chờ mới kích hoạt
     * phiên; mọi trạng thái từ chối/hết hạn/lỗi đều đóng phiên.
     */
    public synchronized boolean onAck(RemoteInputAckPayload ack) {
        expireIfNeeded();
        if (ack == null || sessionId == null || !sessionId.equals(ack.sessionId())) {
            return false; // ACK trễ/khác phiên — không khôi phục quyền
        }
        if (state == State.WAITING_CONSENT && ack.isAccepted()) {
            state = State.ACTIVE;
            long requestedExpiry = ack.expiresAtEpochMs() > 0
                    ? ack.expiresAtEpochMs()
                    : now() + maxSessionSeconds * 1000L;
            sessionExpiresAtMs = Math.min(requestedExpiry,
                    now() + maxSessionSeconds * 1000L);
            lastStatus = "Đang điều khiển từ xa";
            log.info("[{}] Remote input ACTIVE (sessionId={}, expiresIn={}ms)",
                    clientId, sessionId, Math.max(0L, sessionExpiresAtMs - now()));
            return true;
        }
        if (ack.isAccepted()) {
            return false; // ACCEPTED ngoài trạng thái chờ — bỏ qua
        }
        state = State.STOPPED;
        sessionId = null;
        sessionExpiresAtMs = 0L;
        lastStatus = switch (ack.status()) {
            case RemoteInputAckPayload.STATUS_DECLINED ->
                    "Client đã từ chối điều khiển";
            case RemoteInputAckPayload.STATUS_EXPIRED ->
                    "Yêu cầu/phiên điều khiển đã hết hạn";
            case RemoteInputAckPayload.STATUS_UNAVAILABLE ->
                    "Client không hỗ trợ/không thể hiện hộp thoại đồng ý";
            default -> "Đã dừng điều khiển (" + ack.status() + ")";
        };
        log.info("[{}] Remote input ended by client: {} — {}", clientId, ack.status(),
                ack.message());
        return true;
    }

    public synchronized SendResult sendMouseMove(int x, int y, int frameWidth, int frameHeight) {
        return sendEvent(RemoteInputEventPayload.MOUSE_MOVE, x, y, 0, 0,
                frameWidth, frameHeight);
    }

    public synchronized SendResult sendMouseButton(boolean down, int button,
                                                   int x, int y,
                                                   int frameWidth, int frameHeight) {
        return sendEvent(down ? RemoteInputEventPayload.MOUSE_DOWN
                : RemoteInputEventPayload.MOUSE_UP, x, y, button, 0, frameWidth, frameHeight);
    }

    public synchronized SendResult sendMouseWheel(int notches, int x, int y,
                                                  int frameWidth, int frameHeight) {
        return sendEvent(RemoteInputEventPayload.MOUSE_WHEEL, x, y, notches, 0,
                frameWidth, frameHeight);
    }

    public synchronized SendResult sendKey(boolean down, int keyCode) {
        return sendEvent(down ? RemoteInputEventPayload.KEY_DOWN
                : RemoteInputEventPayload.KEY_UP, 0, 0, 0, keyCode, 1, 1);
    }

    /** Gửi 1 sự kiện sau khi kiểm tra toàn bộ trạng thái/giới hạn. */
    public synchronized SendResult sendEvent(String eventType, int x, int y, int button,
                                             int keyCode, int frameWidth, int frameHeight) {
        expireIfNeeded();
        if (state != State.ACTIVE || sessionId == null) {
            return SendResult.NOT_ACTIVE;
        }
        if (!validEvent(eventType, x, y, button, keyCode, frameWidth, frameHeight)) {
            return SendResult.INVALID;
        }
        if (!allowEvent(now(), RemoteInputEventPayload.MOUSE_MOVE.equals(eventType))) {
            return SendResult.RATE_LIMITED;
        }
        long seqNo = ++lastSeqNo;
        RemoteInputEventPayload payload = new RemoteInputEventPayload(sessionId, eventType,
                x, y, button, keyCode, frameWidth, frameHeight, seqNo);
        boolean sent;
        try {
            sent = transport != null && transport.send(
                    BaseMessage.of(MessageType.INPUT_EVENT, "server", payload));
        } catch (RuntimeException e) {
            sent = false;
            log.warn("[{}] INPUT_EVENT send threw: {}", clientId, e.getMessage());
        }
        if (!sent) {
            stop("Gửi input thất bại — phiên đã dừng", false);
            return SendResult.SEND_FAILED;
        }
        return SendResult.SENT;
    }

    private static boolean validEvent(String eventType, int x, int y, int button,
                                      int keyCode, int frameWidth, int frameHeight) {
        if (!RemoteInputEventPayload.isSupportedEventType(eventType)) {
            return false;
        }
        int width = frameWidth;
        int height = frameHeight;
        if (width <= 0 || height <= 0
                || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) {
            return false;
        }
        boolean mouse = RemoteInputEventPayload.MOUSE_MOVE.equals(eventType)
                || RemoteInputEventPayload.MOUSE_DOWN.equals(eventType)
                || RemoteInputEventPayload.MOUSE_UP.equals(eventType)
                || RemoteInputEventPayload.MOUSE_WHEEL.equals(eventType);
        if (mouse && (x < 0 || y < 0 || x >= width || y >= height)) {
            return false;
        }
        return switch (eventType) {
            case RemoteInputEventPayload.MOUSE_MOVE -> true;
            case RemoteInputEventPayload.MOUSE_DOWN,
                 RemoteInputEventPayload.MOUSE_UP -> button >= 1 && button <= 3;
            case RemoteInputEventPayload.MOUSE_WHEEL ->
                    button != 0 && Math.abs(button) <= MAX_WHEEL_NOTCHES;
            case RemoteInputEventPayload.KEY_DOWN,
                 RemoteInputEventPayload.KEY_UP -> keyCode >= 1 && keyCode <= 255;
            default -> false;
        };
    }

    /**
     * Dừng phiên phía Server. Gửi {@code INPUT_CONTROL STOP} (nếu đang có
     * phiên) để Client nhả input ngay; trạng thái lập tức về STOPPED nên mọi
     * event đến sau đều bị từ chối.
     */
    public synchronized void stop(String reason, boolean notifyClient) {
        String endedSessionId = sessionId;
        boolean hadSession = state == State.WAITING_CONSENT || state == State.ACTIVE;
        state = State.STOPPED;
        sessionId = null;
        sessionExpiresAtMs = 0L;
        lastStatus = reason == null || reason.isBlank()
                ? "Đã dừng điều khiển" : reason;
        if (hadSession && notifyClient && endedSessionId != null && transport != null) {
            try {
                transport.send(BaseMessage.of(MessageType.INPUT_CONTROL, "server",
                        new RemoteInputControlPayload(endedSessionId,
                                RemoteInputControlPayload.ACTION_STOP, 0, 0)));
            } catch (RuntimeException e) {
                log.debug("[{}] INPUT_CONTROL STOP not sent: {}", clientId, e.getMessage());
            }
        }
        if (hadSession) {
            log.info("[{}] Remote input stopped: {}", clientId, lastStatus);
        }
    }

    public synchronized void stop(String reason) {
        stop(reason, true);
    }

    /** Kiểm tra hết hạn chờ đồng ý / hết thời lượng phiên. */
    public synchronized void expireIfNeeded() {
        long now = now();
        if (state == State.WAITING_CONSENT && now >= consentDeadlineMs) {
            // Gửi STOP cả khi chưa nhận ACCEPTED: nếu ACK ACCEPTED bị mất,
            // Client có thể đã ACTIVE và sẽ tự thu hồi + nhả input ngay,
            // thay vì tiếp tục hiện chỉ báo điều khiển đến hết maxSession.
            stop("Client không phản hồi hoặc không hỗ trợ điều khiển", true);
            log.info("[{}] Remote input consent expired", clientId);
            return;
        }
        if (state == State.ACTIVE && sessionExpiresAtMs > 0 && now >= sessionExpiresAtMs) {
            stop("Phiên điều khiển hết hạn — cần đồng ý lại", true);
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

    private long now() {
        return clock.getAsLong();
    }
}
