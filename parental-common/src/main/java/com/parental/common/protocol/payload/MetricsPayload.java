package com.parental.common.protocol.payload;

/**
 * Payload của gói METRICS_REPORT — Client gửi định kỳ báo cáo lưu lượng và
 * thời gian (khớp Mục 3.2 của {@code docs/PROTOCOL.md}).
 */
public record MetricsPayload(
        long downloadBytes,
        long uploadBytes,
        long downloadSpeedBps,
        long uploadSpeedBps,
        long sessionDurationSeconds,
        String networkStatus) {
}
