package com.parental.client.metrics;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Bộ thu thập chỉ số lưu lượng mạng (Task 3.1) — đóng gói
 * {@link MetricsPayload} khớp 100% Mục 3.2 của {@code docs/PROTOCOL.md}.
 *
 * <p>Tách interface để unit test có thể stub dữ liệu bytes tăng dần và để
 * sau này đổi nguồn đo (OSHI → nguồn khác) mà không sửa Engine.</p>
 *
 * <p>Task 3.3: thêm {@link #setNetworkStatus(String)} — ActionDispatcher cập
 * nhật "PAUSED"/"ACTIVE" khi nhận COMMAND, gói METRICS_REPORT chu kỳ sau
 * phản ánh đúng trạng thái.</p>
 */
@FunctionalInterface
public interface NetworkMetricsCollector {

    /** Trạng thái mạng chuẩn theo PROTOCOL.md Mục 3.2. */
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_PAUSED = "PAUSED";

    /**
     * Lấy snapshot metrics hiện tại (delta so với lần gọi trước + tốc độ
     * tức thời + tổng tích lũy trong phiên).
     *
     * <p>Phải an toàn: không bao giờ quăng exception ra ngoài (lỗi OSHI/hệ
     * thống được xử lý nội bộ, trả về metrics của lần đo gần nhất hoặc
     * payload toàn 0 khi chưa có dữ liệu).</p>
     *
     * @return payload METRICS_REPORT theo Mục 3.2
     */
    MetricsPayload collect();

    /**
     * Cập nhật trạng thái mạng cho các lần {@code collect()} kế tiếp
     * ("ACTIVE" / "PAUSED" — Task 3.3). Default no-op để stub/lambda test
     * không bắt buộc triển khai.
     */
    default void setNetworkStatus(String status) {
    }
}
