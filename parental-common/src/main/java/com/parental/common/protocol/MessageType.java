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
    ACTIVITY_REPORT
}
