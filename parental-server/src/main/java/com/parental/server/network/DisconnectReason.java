package com.parental.server.network;

/**
 * Lý do một phiên Client bị ngắt kết nối (Task 2.3) — dùng cho callback
 * {@link ClientConnectionListener#onClientDisconnected(String, DisconnectReason)}
 * để Phase sau (GUI/CSDL) phân biệt rõ nguyên nhân.
 */
public enum DisconnectReason {

    /** Client chủ động ngắt kết nối (socket EOF hoặc lỗi đọc). */
    CLIENT_DISCONNECTED,

    /** Client không gửi HEARTBEAT/bất kỳ dữ liệu nào vượt quá ngưỡng timeout. */
    HEARTBEAT_TIMEOUT,

    /** Kết nối bị lỗi I/O khi đang hoạt động. */
    IO_ERROR,

    /** Client mới kết nối với cùng clientId — phiên cũ bị thay thế. */
    REPLACED,

    /** Server đang graceful shutdown và chủ động đóng phiên. */
    SERVER_SHUTDOWN,

    /** Phụ huynh xóa thiết bị — server gửi UNPAIR rồi chủ động ngắt (Delete/Unpair). */
    UNPAIRED,

    /** Lý do khác/không xác định. */
    UNKNOWN
}
