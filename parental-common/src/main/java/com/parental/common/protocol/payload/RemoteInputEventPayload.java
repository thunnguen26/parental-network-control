package com.parental.common.protocol.payload;

import java.util.Set;

/**
 * Payload của gói {@code INPUT_EVENT} (Mục 3.11 của {@code docs/PROTOCOL.md}).
 *
 * <p>Một sự kiện chuột/bàn phím do Server gửi trong phiên đã được người dùng
 * tại Client chấp thuận. Tọa độ {@code x}/{@code y} tính theo pixel của frame
 * đang xem — Client tự quy đổi sang tọa độ màn hình tuyệt đối của máy Client,
 * KHÔNG nhận tọa độ tuyệt đối từ Server.</p>
 *
 * @param sessionId   phiên đã được chấp thuận
 * @param eventType   một trong allowlist {@link #SUPPORTED_EVENT_TYPES}
 * @param x           tọa độ X theo frame (chỉ sự kiện chuột)
 * @param y           tọa độ Y theo frame (chỉ sự kiện chuột)
 * @param button      nút chuột 1/2/3 (down/up) hoặc số nấc cuộn (wheel)
 * @param keyCode     mã phím AWT {@code KeyEvent.VK_*} (chỉ key down/up)
 * @param frameWidth  độ rộng frame lúc gửi sự kiện
 * @param frameHeight độ cao frame lúc gửi sự kiện
 * @param seqNo       số thứ tự tăng đơn điệu trong phiên (chống phát lại)
 */
public record RemoteInputEventPayload(
        String sessionId,
        String eventType,
        int x,
        int y,
        int button,
        int keyCode,
        int frameWidth,
        int frameHeight,
        long seqNo) {

    public static final String MOUSE_MOVE = "MOUSE_MOVE";
    public static final String MOUSE_DOWN = "MOUSE_DOWN";
    public static final String MOUSE_UP = "MOUSE_UP";
    public static final String MOUSE_WHEEL = "MOUSE_WHEEL";
    public static final String KEY_DOWN = "KEY_DOWN";
    public static final String KEY_UP = "KEY_UP";

    /** Allowlist sự kiện được phép chuyển tới lớp inject input. */
    public static final Set<String> SUPPORTED_EVENT_TYPES =
            Set.of(MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, MOUSE_WHEEL, KEY_DOWN, KEY_UP);

    public RemoteInputEventPayload {
        sessionId = sessionId == null ? "" : sessionId;
        eventType = eventType == null ? "" : eventType;
    }

    public static boolean isSupportedEventType(String eventType) {
        return eventType != null && SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    /** Sự kiện chuột cần kiểm tra tọa độ trong khung frame. */
    public boolean isMouseEvent() {
        return MOUSE_MOVE.equals(eventType) || MOUSE_DOWN.equals(eventType)
                || MOUSE_UP.equals(eventType) || MOUSE_WHEEL.equals(eventType);
    }
}
