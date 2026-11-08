package com.parental.server.network;

/**
 * Listener sự kiện kết nối của Client (Task 2.3) — chuẩn bị cho việc đẩy
 * trạng thái lên GUI và ghi CSDL ở các Phase sau. Các callback được gọi từ
 * thread mạng (handler/sweeper/engine): phải nhanh, không block; việc xử lý
 * nặng nên đưa vào hàng đợi riêng.
 */
public interface ClientConnectionListener {

    /** Client hoàn tất HANDSHAKE và được đăng ký vào {@link ClientSessionManager}. */
    default void onClientConnected(ClientSession session) {
    }

    /**
     * Client bị ngắt kết nối.
     *
     * @param clientId định danh client vừa mất kết nối
     * @param reason   nguyên nhân ngắt kết nối
     */
    default void onClientDisconnected(String clientId, DisconnectReason reason) {
    }

    /**
     * Client vừa gửi báo cáo hoạt động (ACTIVITY_REPORT — PROTOCOL.md Mục
     * 3.9, Activity Monitoring): Dashboard dùng để cập nhật "Đang mở: ..."
     * tức thời. Callback chạy trên handler thread — phải nhanh, không block;
     * UI tự bọc {@code SwingUtilities.invokeLater}.
     */
    default void onActivityReport(ClientSession session,
            com.parental.common.protocol.payload.ActivityReportPayload activity) {
    }

    /**
     * Client vừa ACK kết quả áp dụng CONFIG_SYNC (PROTOCOL.md Mục 3.5) —
     * status {@code APPLIED}/{@code PARTIAL}/{@code FAILED}. Dashboard dùng để
     * không báo "thành công" chỉ vì gói đã gửi đi.
     */
    default void onConfigAck(ClientSession session, long revision,
            String status, String message) {
    }
}
