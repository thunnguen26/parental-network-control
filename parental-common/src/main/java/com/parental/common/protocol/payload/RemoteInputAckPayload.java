package com.parental.common.protocol.payload;

/**
 * Payload của gói {@code INPUT_ACK} (Mục 3.11 của {@code docs/PROTOCOL.md}) —
 * Client báo kết quả đồng ý hoặc trạng thái kết thúc phiên điều khiển.
 *
 * @param sessionId          phiên tương ứng
 * @param status             một trong các hằng {@code STATUS_*}
 * @param message            mô tả ngắn (không chứa nội dung người dùng gõ)
 * @param acceptedAtEpochMs  epoch millis lúc chấp nhận (0 nếu không có)
 * @param expiresAtEpochMs   epoch millis hết hạn phiên (0 nếu không có)
 */
public record RemoteInputAckPayload(
        String sessionId,
        String status,
        String message,
        long acceptedAtEpochMs,
        long expiresAtEpochMs) {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_STOPPED = "STOPPED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    public RemoteInputAckPayload {
        sessionId = sessionId == null ? "" : sessionId;
        status = status == null ? "" : status;
        message = message == null ? "" : message;
    }

    public boolean isAccepted() {
        return STATUS_ACCEPTED.equals(status);
    }

    /** Tạo ACK gọn không kèm mốc thời gian. */
    public static RemoteInputAckPayload of(String sessionId, String status, String message) {
        return new RemoteInputAckPayload(sessionId, status, message, 0L, 0L);
    }
}
