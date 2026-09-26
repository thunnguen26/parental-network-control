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
}
