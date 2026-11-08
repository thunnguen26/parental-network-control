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
    BLOCK_PROCESS,

    /**
     * Khóa phiên đăng nhập hiện tại (Tính năng 3.4). Client dùng
     * {@code rundll32 user32.dll,LockWorkStation}; không đóng ứng dụng.
     */
    LOCK_SESSION,
    /** Đăng xuất phiên hiện tại (shutdown /l) — hỏi xác nhận trên Dashboard. */
    LOGOFF_SESSION,
    /** Khởi động lại máy (shutdown /r /t 5) — hỏi xác nhận trên Dashboard. */
    RESTART_DEVICE,
    /** Tắt máy (shutdown /s /t 5) — hỏi xác nhận trên Dashboard. */
    SHUTDOWN_DEVICE
}
