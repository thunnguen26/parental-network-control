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
        String networkStatus,
        long sessionStartTimestamp) {

    /**
     * Compatibility constructor for callers and clients predating the
     * session-start field. A zero timestamp asks the server to use legacy
     * duration accounting.
     */
    public MetricsPayload(long downloadBytes, long uploadBytes,
                          long downloadSpeedBps, long uploadSpeedBps,
                          long sessionDurationSeconds, String networkStatus) {
        this(downloadBytes, uploadBytes, downloadSpeedBps, uploadSpeedBps,
                sessionDurationSeconds, networkStatus, 0);
    }
}
