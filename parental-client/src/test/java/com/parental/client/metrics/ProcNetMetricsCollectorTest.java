package com.parental.client.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Unit test JVM cho {@link ProcNetMetricsCollector} — parse
 * {@code /proc/net/dev} và thuật toán delta KHÔNG cần Linux thật (inject
 * nội dung file qua supplier / gọi parse trực tiếp).
 */
class ProcNetMetricsCollectorTest {

    /** Nội dung /proc/net/dev mô phỏng (2 dòng header + lo + eth0). */
    private static final String PROC_SAMPLE = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 5000000    4000    0    0    0     0          0         0  5000000    4000    0    0    0     0       0          0
              eth0: 1000000    8000    0    0    0     0          0         0   500000    6000    0    0    0     0       0          0
            """;

    @Test
    @DisplayName("parseTotals: cộng rx/tx các interface, BỎ lo và 2 dòng header")
    void parseTotalsSkipsLoopbackAndHeaders() {
        long[] totals = ProcNetMetricsCollector.parseTotals(PROC_SAMPLE);
        assertEquals(1_000_000L, totals[0], "rx = chỉ eth0 (lo bị bỏ)");
        assertEquals(500_000L, totals[1], "tx = chỉ eth0 (lo bị bỏ)");
    }

    @Test
    @DisplayName("parseTotals: nhiều interface được cộng dồn")
    void parseTotalsSumsMultipleInterfaces() {
        String content = """
                Inter-|   Receive
                 face |bytes
                  lo: 99999    1    0    0    0     0          0         0  99999    1    0    0    0     0       0          0
                wlan0: 1000    10    0    0    0     0          0         0   2000    20    0    0    0     0       0          0
                 eth0: 3000    30    0    0    0     0          0         0   4000    40    0    0    0     0       0          0
                """;
        long[] totals = ProcNetMetricsCollector.parseTotals(content);
        assertEquals(4_000L, totals[0], "rx = wlan0 + eth0");
        assertEquals(6_000L, totals[1], "tx = wlan0 + eth0");
    }

    @Test
    @DisplayName("parseTotals: nội dung rỗng/chỉ header → 0-0, không ném")
    void parseTotalsEmptyIsSafe() {
        assertEquals(0L, ProcNetMetricsCollector.parseTotals("")[0]);
        assertEquals(0L, ProcNetMetricsCollector.parseTotals("Inter-|   Receive\\n face |bytes\\n")[1]);
        assertEquals(0L, ProcNetMetricsCollector.parseTotals(null)[0]);
    }

    @Test
    @DisplayName("readTotals: file null (không đọc được) → 0-0, không ném exception")
    void readTotalsNullContentIsSafe() {
        ProcNetMetricsCollector collector =
                new ProcNetMetricsCollector(() -> null);
        long[] totals = collector.readTotals();
        assertEquals(0L, totals[0]);
        assertEquals(0L, totals[1]);
    }

    @Test
    @DisplayName("computeFrom: lần đầu ghi mốc nền trả 0; lần sau tính delta + tốc độ + tích lũy")
    void deltaCalculationAcrossCycles() {
        ProcNetMetricsCollector collector =
                new ProcNetMetricsCollector(() -> PROC_SAMPLE);

        long t0 = 1_000_000_000L; // nanoTime giả lập
        MetricsPayload first = collector.computeFrom(1_000_000L, 500_000L, t0, t0);
        assertEquals(0L, first.downloadBytes(), "lần đầu: chưa có delta");
        assertEquals(0L, first.uploadSpeedBps());

        // +3 giây, rx +150_000 bytes, tx +30_000 bytes
        long t1 = t0 + 3_000_000_000L;
        MetricsPayload second = collector.computeFrom(1_150_000L, 530_000L, t1, t0);
        assertEquals(150_000L, second.downloadBytes(), "tổng tích lũy = delta chu kỳ này");
        assertEquals(30_000L, second.uploadBytes());
        assertEquals(50_000L, second.downloadSpeedBps(), "150000 bytes / 3s = 50000 B/s");
        assertEquals(10_000L, second.uploadSpeedBps());
        assertEquals(3L, second.sessionDurationSeconds());
        assertEquals("ACTIVE", second.networkStatus());

        // Chu kỳ kế: counter KHÔNG đổi (không traffic) → delta 0, tích lũy giữ nguyên
        long t2 = t1 + 3_000_000_000L;
        MetricsPayload third = collector.computeFrom(1_150_000L, 530_000L, t2, t0);
        assertEquals(0L, third.downloadSpeedBps());
        assertEquals(150_000L, third.downloadBytes(), "tích lũy không mất khi delta = 0");
    }

    @Test
    @DisplayName("computeFrom: counter reset (current < previous) → delta 0, không âm")
    void counterResetGuardedToZero() {
        ProcNetMetricsCollector collector =
                new ProcNetMetricsCollector(() -> PROC_SAMPLE);
        long t0 = 1_000_000_000L;
        collector.computeFrom(2_000_000L, 1_000_000L, t0, t0);
        MetricsPayload after = collector.computeFrom(1_000_000L, 500_000L, t0 + 1_000_000_000L, t0);
        assertEquals(0L, after.downloadBytes(), "counter reset → delta 0, không âm");
        assertEquals(0L, after.uploadBytes());
    }

    @Test
    @DisplayName("setNetworkStatus PAUSED → payload chu kỳ sau mang đúng trạng thái")
    void networkStatusPropagates() {
        ProcNetMetricsCollector collector =
                new ProcNetMetricsCollector(() -> PROC_SAMPLE);
        collector.setNetworkStatus("PAUSED");
        long t0 = 1_000_000_000L;
        collector.computeFrom(1_000L, 1_000L, t0, t0);
        MetricsPayload payload = collector.computeFrom(2_000L, 2_000L, t0 + 1_000_000_000L, t0);
        assertEquals("PAUSED", payload.networkStatus());
    }

    @Test
    @DisplayName("collect() full-flow qua supplier giả lập — không ném dù JVM là Windows")
    void collectFullFlowNeverThrows() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ProcNetMetricsCollector collector = new ProcNetMetricsCollector(() -> {
            calls.incrementAndGet();
            // Giả lập traffic tăng: lần 2 → rx/tx tăng 300_000/60_000
            String template = """
                     face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                        lo: 5000000    4000    0    0    0     0          0         0  5000000    4000    0    0    0     0       0          0
                      eth0: %d    8000    0    0    0     0          0         0   %d    6000    0    0    0     0       0          0
                    """;
            int n = calls.get();
            return template.formatted(1_000_000L + 300_000L * (n - 1),
                    500_000L + 60_000L * (n - 1));
        });
        MetricsPayload first = collector.collect();
        assertEquals(0L, first.downloadBytes(), "lần đầu baseline");
        MetricsPayload second = collector.collect();
        assertTrue(second.downloadBytes() > 0, "lần sau có delta > 0");
        assertTrue(second.downloadSpeedBps() > 0);
    }
}
