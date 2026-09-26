package com.parental.client.metrics;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Bộ thu thập chỉ số lưu lượng mạng (Task 3.1) — đóng gói
 * {@link MetricsPayload} khớp 100% Mục 3.2 của {@code docs/PROTOCOL.md}.
 *
 * <p>Tách interface để unit test có thể stub dữ liệu bytes tăng dần và để
 * sau này đổi nguồn đo (OSHI → nguồn khác) mà không sửa Engine.</p>
 */
@FunctionalInterface
public interface NetworkMetricsCollector {

    /**
     * Lấy snapshot metrics hiện tại (delta so với lần gọi trước + tốc độ
     * tức thời + tổng tích lũy trong phiên).
     *
     * <p>Phải an toàn: không bao giờ quăng exception ra ngoài (lỗi OSHI/hệ
     * thống được xử lý nội bộ, trả về metrics của lần đo gần nhất hoặc
     * payload toàn 0 khi chưa có dữ liệu).</p>
     *
     * @return payload METRICS_REPORT theo Mục 3.2 (networkStatus = "ACTIVE")
     */
    MetricsPayload collect();
}
