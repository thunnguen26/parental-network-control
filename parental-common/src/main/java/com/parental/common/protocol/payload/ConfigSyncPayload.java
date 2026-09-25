package com.parental.common.protocol.payload;

import java.util.List;

/**
 * Payload của gói CONFIG_SYNC — Server gửi cấu hình chính sách cho Client
 * (khớp Mục 3.4 của {@code docs/PROTOCOL.md}).
 */
public record ConfigSyncPayload(
        List<String> blockedDomains,
        long dailyUsageLimitSeconds,
        long dailyTrafficQuotaBytes,
        String allowedStartTime,
        String allowedEndTime) {

    /** Phòng thủ: chuẩn hóa danh sách domain, không bao giờ null. */
    public ConfigSyncPayload {
        blockedDomains = (blockedDomains == null) ? List.of() : List.copyOf(blockedDomains);
    }
}
