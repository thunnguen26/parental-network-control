package com.parental.client.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Bộ thu thập lưu lượng mạng trên <b>Linux</b> — đọc trực tiếp
 * {@code /proc/net/dev} (không cần OSHI/native lib, chạy được cả khi OSHI
 * không nhìn thấy NIC qua JNI).
 *
 * <p><b>Định dạng {@code /proc/net/dev}</b> (mỗi dòng một interface):
 * <pre>
 * Inter-|   Receive                                                |  Transmit
 *  face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets ...
 *     lo: 1234567    9876    0    0    0     0          0         0  1234567    9876 ...
 *   eth0: 987654321 123456    0    0    0     0          0         0  12345678  98765 ...
 * </pre>
 * Cột 1 = receive bytes, cột 9 = transmit bytes (sau dấu ':' tên interface).</p>
 *
 * <p><b>Thuật toán</b> (giữ nguyên hợp đồng của {@link OshiNetworkMetricsCollector}):
 * <ul>
 *   <li>Cộng dồn rx/tx của TẤT CẢ interface TRỪ {@code lo} (loopback) —
 *       yêu cầu nhiệm vụ: "bỏ qua interface lo".</li>
 *   <li>Lần đo đầu: chỉ ghi mốc nền, trả payload toàn 0 (chưa có delta).</li>
 *   <li>Các lần sau: {@code delta = max(0, current - previous)} (guard counter
 *       reset), tốc độ = delta / khoảng thời gian (B/s — khớp Mục 3.2).</li>
 *   <li>Tổng tích lũy phiên = cộng dồn delta qua các chu kỳ METRICS.</li>
 * </ul></p>
 *
 * <p><b>Defensive</b>: file thiếu/không đọc được (container chặn /proc...) →
 * trả snapshot cũ, KHÔNG BAO GIỜ ném exception vào scheduler (AGENTS.md).
 * Thread-safety: toàn bộ trạng thái delta nằm trong {@code synchronized}.</p>
 */
public class ProcNetMetricsCollector implements NetworkMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(ProcNetMetricsCollector.class);

    /** Đường dẫn kernel chuẩn của thống kê mạng trên Linux. */
    public static final String PROC_NET_DEV = "/proc/net/dev";

    /** Interface loopback — luôn bỏ qua khi cộng tổng. */
    static final String LOOPBACK_IFACE = "lo";

    private static final long MIN_DELTA_INTERVAL_MS = 1;

    private final Object lock = new Object();
    /** Nguồn nội dung /proc/net/dev — inject được để unit test JVM trên Windows. */
    private final Supplier<String> procNetDevSupplier;
    private final long sessionStartNanos;
    private final long sessionStartTimestamp;

    // Trạng thái delta giữa 2 lần đo (bảo vệ bởi lock)
    private long previousRecvBytes = -1;
    private long previousSentBytes = -1;
    private long previousTimestampNanos = -1;
    private long accumulatedDownloadBytes = 0;
    private long accumulatedUploadBytes = 0;
    private String networkStatus = STATUS_ACTIVE;
    private MetricsPayload lastMetrics =
            new MetricsPayload(0, 0, 0, 0, 0, STATUS_ACTIVE);

    /** Dùng thật {@code /proc/net/dev}. */
    public ProcNetMetricsCollector() {
        this(() -> readProcNetDev(Path.of(PROC_NET_DEV)));
    }

    /** Cho test inject nội dung file giả lập (không đụng filesystem Linux thật). */
    ProcNetMetricsCollector(Supplier<String> procNetDevSupplier) {
        this.procNetDevSupplier = procNetDevSupplier;
        this.sessionStartNanos = System.nanoTime();
        this.sessionStartTimestamp = System.currentTimeMillis() / 1000;
    }

    @Override
    public void setNetworkStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.networkStatus = status;
        }
    }

    @Override
    public MetricsPayload collect() {
        synchronized (lock) {
            try {
                long now = System.nanoTime();
                long[] totals = readTotals();
                return computeFrom(totals[0], totals[1], now, sessionStartNanos);
            } catch (RuntimeException e) {
                // Defensive: /proc không đọc được → trả snapshot cũ, không chết scheduler.
                log.warn("Failed to read {}: returning last snapshot ({})",
                        PROC_NET_DEV, e.getMessage());
                return lastMetrics;
            }
        }
    }

    /**
     * Lõi delta — tách riêng (package-private) để unit test gọi trực tiếp
     * với dữ liệu tăng dần, khớp cách test {@link OshiNetworkMetricsCollector}.
     */
    MetricsPayload computeFrom(long currentRecvBytes, long currentSentBytes,
                               long nowNanos, long sessionStartNanosN) {
        long sessionDurationSeconds = Math.max(0, (nowNanos - sessionStartNanosN) / 1_000_000_000L);

        if (previousRecvBytes < 0) {
            // Lần đo đầu: ghi mốc nền, không tính delta.
            previousRecvBytes = currentRecvBytes;
            previousSentBytes = currentSentBytes;
            previousTimestampNanos = nowNanos;
            lastMetrics = new MetricsPayload(0, 0, 0, 0, sessionDurationSeconds,
                    networkStatus, sessionStartTimestamp);
            return lastMetrics;
        }

        long deltaTimeNanos = Math.max(MIN_DELTA_INTERVAL_MS * 1_000_000L, nowNanos - previousTimestampNanos);
        double deltaTimeSeconds = deltaTimeNanos / 1_000_000_000.0;

        long deltaDownload = Math.max(0, currentRecvBytes - previousRecvBytes); // guard reset
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
                networkStatus,
                sessionStartTimestamp);
        return lastMetrics;
    }

    /**
     * Đọc {@code /proc/net/dev}: cộng rx/tx mọi interface TRỪ {@code lo}.
     * Dòng không parse được (header...) bị bỏ qua im lặng.
     *
     * @return {@code [totalRecv, totalSent]}
     */
    long[] readTotals() {
        String content = procNetDevSupplier.get();
        if (content == null || content.isBlank()) {
            // File rỗng/không đọc được → 0-0; computeFrom vẫn ghi mốc nền chuẩn.
            log.warn("{} is empty or unreadable — reporting zero totals", PROC_NET_DEV);
            return new long[] {0L, 0L};
        }
        return parseTotals(content);
    }

    /** Parse nội dung — static + package-private để test thuần không cần file. */
    static long[] parseTotals(String content) {
        if (content == null || content.isBlank()) {
            return new long[] {0L, 0L}; // defensive: null/rỗng → tổng 0, không ném
        }
        long totalRecv = 0;
        long totalSent = 0;
        for (String line : content.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue; // 2 dòng header — bỏ qua
            }
            String iface = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (iface.equals(LOOPBACK_IFACE)) {
                continue; // yêu cầu nhiệm vụ: bỏ qua interface lo
            }
            String[] fields = line.substring(colon + 1).trim().split("\\s+");
            if (fields.length < 9) {
                continue; // dòng dị dạng — bỏ qua, không chết collector
            }
            try {
                totalRecv += Long.parseLong(fields[0]); // cột 1: receive bytes
                totalSent += Long.parseLong(fields[8]); // cột 9: transmit bytes
            } catch (NumberFormatException e) {
                log.debug("Skipping malformed {} row for iface '{}': {}", PROC_NET_DEV, iface, e.getMessage());
            }
        }
        return new long[] {totalRecv, totalSent};
    }

    /** Đọc toàn bộ file (null khi lỗi IO — caller xử lý defensive). */
    static String readProcNetDev(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", path, e.getMessage());
            return null;
        }
    }
}
