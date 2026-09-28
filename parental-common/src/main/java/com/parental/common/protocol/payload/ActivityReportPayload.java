package com.parental.common.protocol.payload;

import java.util.List;

/**
 * Payload của gói ACTIVITY_REPORT — Client gửi định kỳ báo cáo hoạt động
 * (khớp Mục 3.9 của {@code docs/PROTOCOL.md}, Activity Monitoring).
 *
 * <p>Client thu thập cửa sổ đang focus (tiêu đề + tiến trình) và danh sách
 * tiến trình ứng dụng tiêu biểu, gửi về server để phụ huynh theo dõi trên
 * Dashboard ("Đang mở: chrome.exe — Giải bài tập Hóa 12 | VietJack"). Server
 * lưu mỗi báo cáo vào bảng {@code activity_logs}.</p>
 *
 * <p><b>Mở rộng 10 — URL/domain từ thanh địa chỉ</b>: khi cửa sổ active là
 * trình duyệt, client dùng UIA ValuePattern đọc URL hiện hành và bóc tách
 * domain, truyền qua {@code activeUrlOrDomain} để menu "Chặn nhanh" trên
 * Server điền sẵn domain 1-click (không bắt phụ huynh gõ tay). Máy chạy code
 * cũ không gửi field này → nhận giá trị mặc định chuỗi rỗng (lenient).</p>
 *
 * @param activeWindowTitle tiêu đề cửa sổ đang active/focus (rỗng nếu không xác định)
 * @param activeProcessName tên tiến trình của cửa sổ active, ví dụ "chrome.exe"
 * @param activeUrlOrDomain domain/URL bóc tách từ thanh địa chỉ trình duyệt
 *                          (rỗng nếu không phải trình duyệt hoặc không lấy được)
 * @param topProcesses      tối đa 5 tiến trình ứng dụng tiêu biểu đang chạy
 * @param timestamp         thời điểm thu thập (epoch millis)
 */
public record ActivityReportPayload(
        String activeWindowTitle,
        String activeProcessName,
        String activeUrlOrDomain,
        List<String> topProcesses,
        long timestamp) {

    /** Chu kỳ gửi mặc định 10 giây (đồng bộ {@code ClientConfig.activityIntervalMs}). */
    public static final long DEFAULT_INTERVAL_MS = 10_000;

    /** Số tiến trình tối đa trong danh sách {@code topProcesses}. */
    public static final int MAX_TOP_PROCESSES = 5;

    /** Constructor tiện ích 4 tham số (tương thích code cũ) — URL rỗng. */
    public ActivityReportPayload(String activeWindowTitle, String activeProcessName,
                                 List<String> topProcesses, long timestamp) {
        this(activeWindowTitle, activeProcessName, "", topProcesses, timestamp);
    }

    /**
     * Normalization: CHỈ field MỚI {@code activeUrlOrDomain} được chuẩn hóa
     * null → chuỗi rỗng (field này server luôn đọc trực tiếp); các field cũ
     * giữ nguyên hành vi lenient (null khi thiếu) cho tương thích test/legacy.
     */
    public ActivityReportPayload {
        activeUrlOrDomain = activeUrlOrDomain == null ? "" : activeUrlOrDomain;
    }
}
