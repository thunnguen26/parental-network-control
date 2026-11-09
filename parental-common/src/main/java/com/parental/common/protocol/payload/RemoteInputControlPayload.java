package com.parental.common.protocol.payload;

/**
 * Payload của gói {@code INPUT_CONTROL} (Mục 3.11 của {@code docs/PROTOCOL.md}).
 *
 * <p>Server chỉ gửi {@code REQUEST} khi cửa sổ xem màn hình của đúng Client
 * đang mở; Client phải hỏi người dùng đang thao tác tại máy trước khi bật bất
 * kỳ phiên điều khiển nào. {@code STOP} thu hồi phiên ngay lập tức.</p>
 *
 * @param sessionId              UUID phiên do Server sinh
 * @param action                 {@code "REQUEST"} hoặc {@code "STOP"}
 * @param consentTimeoutSeconds  thời gian chờ người dùng chấp nhận (giây),
 *                               0 = mặc định 20
 * @param maxSessionSeconds      thời lượng tối đa sau khi chấp nhận (giây),
 *                               0 = mặc định 300
 */
public record RemoteInputControlPayload(
        String sessionId,
        String action,
        int consentTimeoutSeconds,
        int maxSessionSeconds) {

    public static final String ACTION_REQUEST = "REQUEST";
    public static final String ACTION_STOP = "STOP";

    public static final int DEFAULT_CONSENT_TIMEOUT_SECONDS = 20;
    public static final int DEFAULT_MAX_SESSION_SECONDS = 300;

    public RemoteInputControlPayload {
        sessionId = sessionId == null ? "" : sessionId;
        action = action == null ? ACTION_STOP : action;
    }

    public int effectiveConsentTimeoutSeconds() {
        return consentTimeoutSeconds > 0
                ? Math.min(consentTimeoutSeconds, 120)
                : DEFAULT_CONSENT_TIMEOUT_SECONDS;
    }

    public int effectiveMaxSessionSeconds() {
        return maxSessionSeconds > 0
                ? Math.min(maxSessionSeconds, 3600)
                : DEFAULT_MAX_SESSION_SECONDS;
    }
}
