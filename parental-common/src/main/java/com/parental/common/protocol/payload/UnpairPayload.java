package com.parental.common.protocol.payload;

/**
 * Payload của gói UNPAIR — Server gửi khi phụ huynh XÓA thiết bị khỏi danh
 * sách quản lý (khớp Mục 3.8 của {@code docs/PROTOCOL.md}).
 *
 * <p>Luồng: Server gửi {@code UNPAIR} xuống client đang ONLINE rồi đóng socket
 * ngay sau đó. Phía client nhận được: khôi phục mạng bình thường
 * ({@code resumeNetwork} — gỡ firewall rule + hosts block), xóa
 * {@code client_identity.json} (mất deviceToken → hết quyền kết nối) rồi
 * quay về màn hình ghép đôi lần đầu (hoặc thoát nếu headless).</p>
 *
 * <p>Client bị xóa không handshake lại được: dòng {@code clients} (kèm
 * deviceToken) đã bị xóa khỏi DB — handshake sẽ bị từ chối với mã
 * {@code UNAUTHORIZED_DEVICE}.</p>
 *
 * @param reason lý do thu hồi ghép đôi, hiển thị cho người dùng máy con
 */
public record UnpairPayload(String reason) {

    /** Lý do mặc định khi phụ huynh xóa thiết bị từ Dashboard. */
    public static final String DEFAULT_REASON = "Thiết bị đã bị xóa khỏi danh sách quản lý";
}
