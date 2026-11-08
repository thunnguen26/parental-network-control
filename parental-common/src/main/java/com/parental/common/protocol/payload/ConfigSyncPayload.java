package com.parental.common.protocol.payload;

import java.util.List;

/**
 * Payload của gói CONFIG_SYNC — Server gửi cấu hình chính sách cho Client
 * (khớp Mục 3.4 của {@code docs/PROTOCOL.md}).
 *
 * <p><b>Mở rộng 11 — chặn tiến trình qua CONFIG_SYNC</b>: field
 * {@code blockedProcesses} mang TOÀN BỘ trạng thái chặn tiến trình của client
 * (REPLACE-ALL): client đồng bộ đè {@code blocked_processes.json}; gỡ app
 * khỏi danh sách = bỏ chặn (ngừng kill, app mở lại bình thường). Client cũ
 * không đọc field này → bỏ qua an toàn; server cũ không gửi → client nhận
 * rỗng = không đổi hành vi kill lặp (chỉ kill theo lệnh BLOCK_PROCESS lẻ).</p>
 */
public record ConfigSyncPayload(
        List<String> blockedDomains,
        long dailyUsageLimitSeconds,
        long dailyTrafficQuotaBytes,
        String allowedStartTime,
        String allowedEndTime,
        List<String> blockedProcesses,
        long configRevision) {

    /** Phòng thủ: chuẩn hóa danh sách, không bao giờ null; revision âm → 0. */
    public ConfigSyncPayload {
        blockedDomains = (blockedDomains == null) ? List.of() : List.copyOf(blockedDomains);
        blockedProcesses = (blockedProcesses == null) ? List.of() : List.copyOf(blockedProcesses);
        configRevision = Math.max(0, configRevision);
    }

    /** Constructor 6 tham số (tương thích code cũ) — không kèm revision. */
    public ConfigSyncPayload(List<String> blockedDomains, long dailyUsageLimitSeconds,
                             long dailyTrafficQuotaBytes, String allowedStartTime,
                             String allowedEndTime, List<String> blockedProcesses) {
        this(blockedDomains, dailyUsageLimitSeconds, dailyTrafficQuotaBytes,
                allowedStartTime, allowedEndTime, blockedProcesses, 0);
    }

    /** Constructor 5 tham số (tương thích code cũ) — không kèm danh sách tiến trình. */
    public ConfigSyncPayload(List<String> blockedDomains, long dailyUsageLimitSeconds,
                             long dailyTrafficQuotaBytes, String allowedStartTime,
                             String allowedEndTime) {
        this(blockedDomains, dailyUsageLimitSeconds, dailyTrafficQuotaBytes,
                allowedStartTime, allowedEndTime, List.of(), 0);
    }
}
