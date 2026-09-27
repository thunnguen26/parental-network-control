package com.parental.common.protocol.payload;

/**
 * Payload của gói PAIR_RESPONSE — Server trả lời PAIR_REQUEST
 * (khớp Mục 3.7 của {@code docs/PROTOCOL.md}).
 *
 * @param success     {@code true} = ghép đôi thành công
 * @param deviceToken UUID token cấp quyền kết nối — chỉ có khi success=true
 * @param message     {@code "PAIRED"} khi thành công, hoặc lý do từ chối:
 *                    {@code "INVALID_PIN"} / {@code "PIN_EXPIRED"} /
 *                    {@code "PIN_ALREADY_USED"}
 */
public record PairResponsePayload(
        boolean success,
        String deviceToken,
        String message) {

    /** Thông điệp chuẩn khi ghép đôi thành công. */
    public static final String MSG_PAIRED = "PAIRED";
    /** PIN không tồn tại hoặc không khớp bất kỳ phiên ghép đôi nào. */
    public static final String MSG_INVALID_PIN = "INVALID_PIN";
    /** PIN đúng nhưng đã quá thời hạn hiệu lực (5 phút). */
    public static final String MSG_PIN_EXPIRED = "PIN_EXPIRED";
    /** PIN đúng, còn hạn nhưng đã được thiết bị khác dùng. */
    public static final String MSG_PIN_ALREADY_USED = "PIN_ALREADY_USED";
}
