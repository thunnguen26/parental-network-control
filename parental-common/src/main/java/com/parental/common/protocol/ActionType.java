package com.parental.common.protocol;

/**
 * Hành động điều khiển mà Server gửi xuống Client qua gói COMMAND
 * (khớp Mục 3.3 của {@code docs/PROTOCOL.md}).
 */
public enum ActionType {
    PAUSE_NETWORK,
    RESUME_NETWORK,
    ALERT,
    /**
     * Mở rộng 10: Server yêu cầu client chặn vĩnh viễn 1 tiến trình —
     * {@code reason} mang tên tiến trình (vd {@code "somegame.exe"}). Client
     * lưu vào {@code blocked_processes.json} + taskkill ngay và quét lặp 60s.
     */
    BLOCK_PROCESS
}
