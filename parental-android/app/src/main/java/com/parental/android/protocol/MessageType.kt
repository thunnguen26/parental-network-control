package com.parental.android.protocol

/**
 * Loại gói tin giao thức — KHỚP CHÍNH XÁC enum Java `MessageType` của
 * `parental-common` (Jackson serialize enum bằng tên, không dùng số).
 * Khớp Mục 2 của docs/PROTOCOL.md.
 */
enum class MessageType {
    HANDSHAKE,
    HEARTBEAT,
    METRICS_REPORT,
    COMMAND,
    CONFIG_SYNC,
    ACK,
    /** Ghép đôi lần đầu: client gửi PIN + thông tin máy (Mục 3.6). */
    PAIR_REQUEST,
    /** Phản hồi ghép đôi: server trả success/deviceToken/message (Mục 3.7). */
    PAIR_RESPONSE,
    /** Thu hồi ghép đôi: server thông báo client bị xóa (Mục 3.8). */
    UNPAIR,
    /** Giám sát hoạt động (Mục 3.9) — client Android KHÔNG gửi (desktop-only). */
    ACTIVITY_REPORT
}

/** Action của lệnh COMMAND — khớp `ActionType` Java (Mục 3.3). */
enum class ActionType {
    PAUSE_NETWORK,
    RESUME_NETWORK,
    ALERT,
    BLOCK_PROCESS
}
