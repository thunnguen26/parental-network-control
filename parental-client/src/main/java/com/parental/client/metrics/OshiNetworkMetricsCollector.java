package com.parental.client.metrics;

import java.util.Collections;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.MetricsPayload;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

/**
 * Triển khai {@link NetworkMetricsCollector} bằng OSHI (Task 3.1).
 *
 * <p><b>Thuật toán Delta Calculation</b> (đo theo chu kỳ):
 * <ul>
 *   <li>{@code netIF.updateAttributes()} trước MỖI lần đọc để OSHI refresh
 *       counters (nếu không, luôn đọc được giá trị cache cũ → delta = 0).</li>
 *   <li>Cộng dồn các interface non-loopback đang UP ({@code isUp()}), bỏ qua
 *       NIC ảo ({@code isKnownVmMacAddr()}, {@code isVirtual()}); NIC không có
 *       địa chỉ IP hợp lệ cũng bỏ qua (không có traffic thực tế).</li>
 *   <li>{@code deltaDownload = currentRecv - previousRecv} (guard counter
 *       reset/quá tải long: nếu current &lt; previous coi delta = 0); tốc độ =
 *       {@code delta / deltaTimeSeconds}.</li>
 *   <li>Tổng tích lũy phiên: {@code downloadBytes += deltaDownload},
 *       {@code uploadBytes += deltaUpload}.</li>
 *   <li>Lần đo đầu tiên chỉ ghi mốc nền (baseline), trả payload toàn 0 —
 *       không có delta nào được tính vì chưa có mốc so sánh.</li>
 * </ul></p>
 *
 * <p>Thread-safety: {@code collect()} có thể được gọi từ scheduler của Engine
 * — toàn bộ trạng thái delta nằm trong {@code synchronized (lock)}. Lỗi
 * OSHI/hệ thống được nuốt an toàn (trả snapshot cũ), không bao giờ văng
 * exception vào scheduler (Defensive OS Interfacing — AGENTS.md).</p>
 */
public class OshiNetworkMetricsCollector implements NetworkMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(OshiNetworkMetricsCollector.class);
    private static final long MIN_DELTA_INTERVAL_MS = 1;

    private final HardwareAbstractionLayer hal;
    private final long sessionStartNanos;
    private final Object lock = new Object();

    // Trạng thái delta giữa 2 lần đo (được bảo vệ bởi lock)
    private long previousRecvBytes = -1;
    private long previousSentBytes = -1;
    private long previousTimestampNanos = -1;
    private long accumulatedDownloadBytes = 0;
    private long accumulatedUploadBytes = 0;
    private MetricsPayload lastMetrics =
            new MetricsPayload(0, 0, 0, 0, 0, MetricsPayloadDefaults.NETWORK_STATUS_ACTIVE);

    /** Hằng trạng thái mặc định (chuẩn bị cờ cắm cho Task 3.3 PAUSE_NETWORK). */
    static final class MetricsPayloadDefaults {
        static final String NETWORK_STATUS_ACTIVE = "ACTIVE";
    }

    /** Dùng SystemInfo mặc định của OSHI. */
    public OshiNetworkMetricsCollector() {
        this(new SystemInfo().getHardware());
    }

    /** Cho phép inject HAL — phục vụ test với dữ liệu giả lập (null hợp lệ khi chỉ test computeFrom). */
    OshiNetworkMetricsCollector(HardwareAbstractionLayer hal) {
        this.hal = hal;
        this.sessionStartNanos = System.nanoTime();
    }

    @Override
    public MetricsPayload collect() {
        synchronized (lock) {
            try {
                long now = System.nanoTime();
                long[] totals = readNetworkTotals();
                return computeFrom(totals[0], totals[1], now, sessionStartNanos);
            } catch (RuntimeException e) {
                // Defensive: OSHI lỗi (quyền/driver/hệ thống lạ) → trả snapshot cũ.
                log.warn("Failed to collect network metrics, returning last snapshot: {}", e.getMessage());
                return lastMetrics;
            }
        }
    }

    /**
     * Lõi thuật toán delta — tách riêng và package-private để unit test có
     * thể stub dữ liệu bytes tăng dần mà không cần OSHI thật.
     *
     * @param currentRecvBytes    tổng bytes nhận (tất cả NIC hợp lệ) tại thời điểm hiện tại
     * @param currentSentBytes    tổng bytes gửi (tất cả NIC hợp lệ) tại thời điểm hiện tại
     * @param nowNanos            mốc thời gian hiện tại (System.nanoTime)
     * @param sessionStartNanosN  mốc bắt đầu phiên (System.nanoTime)
     */
    MetricsPayload computeFrom(long currentRecvBytes, long currentSentBytes,
                               long nowNanos, long sessionStartNanosN) {
        long sessionDurationSeconds = Math.max(0, (nowNanos - sessionStartNanosN) / 1_000_000_000L);

        if (previousRecvBytes < 0) {
            // Lần đo đầu: chỉ ghi mốc nền, không tính delta (chưa có mốc so sánh).
            previousRecvBytes = currentRecvBytes;
            previousSentBytes = currentSentBytes;
            previousTimestampNanos = nowNanos;
            lastMetrics = new MetricsPayload(0, 0, 0, 0, sessionDurationSeconds,
                    MetricsPayloadDefaults.NETWORK_STATUS_ACTIVE);
            return lastMetrics;
        }

        long deltaTimeNanos = Math.max(MIN_DELTA_INTERVAL_MS * 1_000_000L, nowNanos - previousTimestampNanos);
        double deltaTimeSeconds = deltaTimeNanos / 1_000_000_000.0;

        long deltaDownload = Math.max(0, currentRecvBytes - previousRecvBytes); // guard counter reset
        long deltaUpload = Math.max(0, currentSentBytes - previousSentBytes);

        accumulatedDownloadBytes += deltaDownload;
        accumulatedUploadBytes += deltaUpload;

        long downloadSpeedBps = Math.round(deltaDownload / deltaTimeSeconds);
        long uploadSpeedBps = Math.round(deltaUpload / deltaTimeSeconds);

        previousRecvBytes = currentRecvBytes;
        previousSentBytes = currentSentBytes;
        previousTimestampNanos = nowNanos;

        lastMetrics = new MetricsPayload(
                accumulatedDownloadBytes,
                accumulatedUploadBytes,
                downloadSpeedBps,
                uploadSpeedBps,
                sessionDurationSeconds,
                MetricsPayloadDefaults.NETWORK_STATUS_ACTIVE);
        return lastMetrics;
    }

    /**
     * Duyệt NIC qua {@code hal.getNetworkIFs()}: refresh counters bằng
     * {@code updateAttributes()} rồi cộng dồn bytes recv/sent của các NIC
     * hợp lệ (non-loopback, UP, không VM, có IP). Trả về {@code [recv, sent]}.
     */
    private long[] readNetworkTotals() {
        long totalRecv = 0;
        long totalSent = 0;
        for (NetworkIF netIF : hal.getNetworkIFs(false)) {
            try {
                netIF.updateAttributes(); // bắt buộc: refresh counters cache
            } catch (RuntimeException e) {
                log.debug("Skip NIC {}: updateAttributes failed: {}", netIF.getName(), e.getMessage());
                continue;
            }
            if (!isReportableNic(netIF)) {
                continue;
            }
            totalRecv += Math.max(0, netIF.getBytesRecv());
            totalSent += Math.max(0, netIF.getBytesSent());
        }
        return new long[] {totalRecv, totalSent};
    }

    /**
     * NIC đủ điều kiện báo cáo: đang UP ({@code IfOperStatus.UP}), không
     * loopback, không VM (theo MAC), và có ít nhất một địa chỉ IP (IPv4/IPv6)
     * — đúng yêu cầu "cộng dồn các interface non-loopback có IP hợp lệ".
     *
     * <p>Lưu ý API OSHI 6.6.5: {@code NetworkIF} KHÔNG có {@code isUp()}/
     * {@code isLoopback()}/{@code isVirtual()} — trạng thái UP lấy qua
     * {@code getIfOperStatus()}, loopback/ảo kiểm tra qua
     * {@code queryNetworkInterface()}.</p>
     */
    private static boolean isReportableNic(NetworkIF netIF) {
        try {
            if (netIF.getIfOperStatus() != NetworkIF.IfOperStatus.UP || netIF.isKnownVmMacAddr()) {
                return false;
            }
            java.net.NetworkInterface javaNif = netIF.queryNetworkInterface();
            if (javaNif == null || javaNif.isLoopback()) {
                return false;
            }
            // updateAttributes() đã refresh: mảng IP có sẵn trong NetworkIF
            boolean hasIp = (netIF.getIPv4addr() != null && netIF.getIPv4addr().length > 0)
                    || (netIF.getIPv6addr() != null && netIF.getIPv6addr().length > 0);
            if (!hasIp) {
                // fallback: một số driver chỉ expose IP qua java.net.NetworkInterface
                hasIp = !Collections.list(javaNif.getInetAddresses()).isEmpty();
            }
            return hasIp;
        } catch (RuntimeException | java.net.SocketException e) {
            // Windows ném SocketException khi query NIC lạ — bỏ qua NIC đó
            return false;
        }
    }
}
