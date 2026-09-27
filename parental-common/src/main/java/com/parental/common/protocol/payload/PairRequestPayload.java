package com.parental.common.protocol.payload;

/**
 * Payload của gói PAIR_REQUEST — Client gửi khi muốn ghép đôi lần đầu
 * (khớp Mục 3.6 của {@code docs/PROTOCOL.md}).
 *
 * <p>Client chưa có {@code deviceToken} gửi mã PIN 6 số (do phụ huynh sinh
 * từ Server GUI) kèm thông tin thiết bị. Server kiểm tra PIN qua
 * {@code PairingDao}; đúng → cấp {@code deviceToken} trả về trong
 * {@link PairResponsePayload}.</p>
 *
 * <p>{@code clientId} trong envelope PAIR_REQUEST là UUID định danh bền
 * vững từ {@code client_identity.json} — token được cấp gắn với chính
 * clientId này (UPSERT 1 dòng duy nhất trong bảng {@code clients}).</p>
 */
public record PairRequestPayload(
        String pin,
        String deviceName,
        String deviceType,
        String osVersion,
        String ipAddress,
        String macAddress) {
}
