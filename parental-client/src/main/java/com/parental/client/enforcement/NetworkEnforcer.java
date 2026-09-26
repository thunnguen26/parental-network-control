package com.parental.client.enforcement;

/**
 * Network Enforcement Engine (Task 3.3): điều khiển trạng thái mạng của máy
 * con khi nhận COMMAND từ Server.
 *
 * <p>Triển khai chính: {@link WindowsNetshEnforcer} (Windows Firewall rule qua
 * netsh); test dùng Mock — KHÔNG BAO GIỜ chạy netsh thật trong test suite.</p>
 */
public interface NetworkEnforcer {

    /** Trạng thái mạng chuẩn theo PROTOCOL.md Mục 3.2. */
    String STATUS_ACTIVE = "ACTIVE";
    String STATUS_PAUSED = "PAUSED";

    /**
     * Ngắt kết nối Internet (block outbound HTTP/HTTPS qua firewall rule).
     *
     * @return {@code true} nếu thành công; {@code false} nếu thất bại
     *         (thiếu quyền/treo tiến trình — đã log, không ném exception)
     */
    boolean pauseNetwork();

    /**
     * Khôi phục kết nối Internet (xóa firewall rule).
     *
     * @return {@code true} nếu thành công; {@code false} nếu thất bại
     */
    boolean resumeNetwork();

    /**
     * Trạng thái mạng hiện hành theo quan điểm của enforcer.
     *
     * @return {@link #STATUS_ACTIVE} hoặc {@link #STATUS_PAUSED}
     */
    String getNetworkStatus();

    /**
     * Dọn dẹp khi tắt ứng dụng: luôn cố gắng khôi phục mạng (xóa firewall
     * rule) để không để lại máy trong trạng thái mất mạng vĩnh viễn.
     * Không ném exception.
     */
    void cleanup();
}
