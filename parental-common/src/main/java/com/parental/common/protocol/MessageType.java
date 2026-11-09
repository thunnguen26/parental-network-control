package com.parental.common.protocol;

/**
 * Loại gói tin giao thức, khớp Mục 2 của {@code docs/PROTOCOL.md}.
 */
public enum MessageType {
    HANDSHAKE,
    HEARTBEAT,
    METRICS_REPORT,
    COMMAND,
    CONFIG_SYNC,
    ACK,
    /** Ghép đôi lần đầu: client gửi PIN + thông tin máy (PROTOCOL.md Mục 3.6). */
    PAIR_REQUEST,
    /** Phản hồi ghép đôi: server trả success/deviceToken/message (Mục 3.7). */
    PAIR_RESPONSE,
    /** Thu hồi ghép đôi: server thông báo client bị xóa khỏi danh sách quản lý (Mục 3.8). */
    UNPAIR,
    /** Giám sát hoạt động: client báo cửa sổ active + tiến trình đang chạy (Mục 3.9). */
    ACTIVITY_REPORT,
    /**
     * Điều khiển stream màn hình remote (Mục 3.10): server ra lệnh start/stop,
     * client gửi frame ảnh JPEG về server mỗi frame.
     */
    STREAM_CONTROL,
    /**
     * Frame ảnh màn hình remote (Mục 3.10): client gửi hình ảnh JPEG nén
     * về server để hiển thị trên RemoteScreenDialog.
     */
    STREAM_FRAME,

    /**
     * Điều khiển chuột/bàn phím từ xa (Mục 3.11): server xin phép hoặc thu hồi
     * một phiên điều khiển của đúng client đang xem màn hình.
     */
    INPUT_CONTROL,
    /**
     * Sự kiện chuột/bàn phím (Mục 3.11): server chỉ gửi khi client đã chấp
     * thuận phiên hiện tại; client từ chối mọi sự kiện ngoài phiên đó.
     */
    INPUT_EVENT,
    /**
     * Phản hồi đồng ý/trạng thái phiên điều khiển (Mục 3.11): client báo
     * ACCEPTED/DECLINED/EXPIRED/STOPPED/REJECTED/UNAVAILABLE.
     */
    INPUT_ACK
}
