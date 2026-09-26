package com.parental.client.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Unit test cho {@link OshiNetworkMetricsCollector} — dùng {@code computeFrom}
 * (package-private, được tách từ OSHI) với dữ liệu bytes tăng dần để kiểm tra
 * chính xác thuật toán delta: tổng tích lũy, tốc độ tức thời, guard counter
 * reset, session duration và lần đo đầu tiên.
 */
class OshiNetworkMetricsCollectorTest {

    /** Nano giây = 10 giây → sessionDuration và deltaTime dễ tính tay. */
    private static final long TEN_SECONDS_NANOS = 10_000_000_000L;

    private OshiNetworkMetricsCollector newCollector() {
        return new OshiNetworkMetricsCollector((oshi.hardware.HardwareAbstractionLayer) null);
    }

    @Test
    @DisplayName("Lần đo đầu tiên: chỉ ghi mốc nền, payload toàn 0")
    void firstSampleIsBaselineWithZeroDeltas() {
        OshiNetworkMetricsCollector collector = newCollector();

        MetricsPayload metrics = collector.computeFrom(1_000, 500, TEN_SECONDS_NANOS, 0);

        assertNotNull(metrics);
        assertEquals(0, metrics.downloadBytes(), "Chưa có delta → tổng tải = 0");
        assertEquals(0, metrics.uploadBytes(), "Chưa có delta → tổng gửi = 0");
        assertEquals(0, metrics.downloadSpeedBps());
        assertEquals(0, metrics.uploadSpeedBps());
        assertEquals(10, metrics.sessionDurationSeconds(), "sessionDuration tính từ mốc start");
        assertEquals("ACTIVE", metrics.networkStatus());
    }

    @Test
    @DisplayName("Delta calculation: tổng tích lũy + tốc độ = delta / deltaTime")
    void deltaAccumulationAndSpeed() {
        OshiNetworkMetricsCollector collector = newCollector();
        collector.computeFrom(1_000, 500, TEN_SECONDS_NANOS, 0); // baseline

        // Sau 10s: recv tăng 6_000B, sent tăng 3_000B → 600 B/s và 300 B/s
        MetricsPayload m1 = collector.computeFrom(7_000, 3_500, 2 * TEN_SECONDS_NANOS, 0);

        assertEquals(6_000, m1.downloadBytes());
        assertEquals(3_000, m1.uploadBytes());
        assertEquals(600, m1.downloadSpeedBps());
        assertEquals(300, m1.uploadSpeedBps());
        assertEquals(20, m1.sessionDurationSeconds());

        // Chu kỳ kế (10s nữa): recv +1_200, sent +600 → tổng 7_200 / 3_600, tốc độ 120/60
        MetricsPayload m2 = collector.computeFrom(8_200, 4_100, 3 * TEN_SECONDS_NANOS, 0);

        assertEquals(7_200, m2.downloadBytes(), "Tổng tích lũy = tổng các delta");
        assertEquals(3_600, m2.uploadBytes());
        assertEquals(120, m2.downloadSpeedBps());
        assertEquals(60, m2.uploadSpeedBps());
    }

    @Test
    @DisplayName("Counter reset (current < previous) → delta = 0, không âm")
    void counterResetProducesZeroDeltaNotNegative() {
        OshiNetworkMetricsCollector collector = newCollector();
        collector.computeFrom(10_000, 5_000, TEN_SECONDS_NANOS, 0); // baseline
        MetricsPayload m1 = collector.computeFrom(12_000, 6_000, 2 * TEN_SECONDS_NANOS, 0);
        assertEquals(2_000, m1.downloadBytes());

        // Giả lập NIC counter reset: recv tụt từ 12_000 xuống 3_000, sent tăng 500
        MetricsPayload m2 = collector.computeFrom(3_000, 6_500, 3 * TEN_SECONDS_NANOS, 0);

        assertEquals(2_000, m2.downloadBytes(), "Delta âm phải bị guard về 0");
        assertEquals(1_500, m2.uploadBytes(), "Sent tăng 500 → tổng 1.500");
        assertEquals(0, m2.downloadSpeedBps(), "Speed không được âm");
        assertTrue(m2.uploadSpeedBps() > 0);
    }

    @Test
    @DisplayName("Không có traffic (bytes giữ nguyên) → speed = 0, tổng không đổi")
    void idleCycleHasZeroSpeed() {
        OshiNetworkMetricsCollector collector = newCollector();
        collector.computeFrom(1_000, 1_000, TEN_SECONDS_NANOS, 0);

        MetricsPayload metrics = collector.computeFrom(1_000, 1_000, 2 * TEN_SECONDS_NANOS, 0);

        assertEquals(0, metrics.downloadBytes());
        assertEquals(0, metrics.uploadBytes());
        assertEquals(0, metrics.downloadSpeedBps());
        assertEquals(0, metrics.uploadSpeedBps());
    }

    @Test
    @DisplayName("sessionDurationSeconds tăng theo thời gian thực truyền vào")
    void sessionDurationGrowsWithProvidedTime() {
        OshiNetworkMetricsCollector collector = newCollector();
        MetricsPayload early = collector.computeFrom(0, 0, TEN_SECONDS_NANOS, 0);
        assertEquals(10, early.sessionDurationSeconds());

        MetricsPayload later = collector.computeFrom(100, 100, 95 * TEN_SECONDS_NANOS, 0);
        assertEquals(950, later.sessionDurationSeconds(), "950s = 95 chu kỳ × 10s");
    }

    @Test
    @DisplayName("OshiNetworkMetricsCollector thật (OSHI trên máy) không quăng exception và trả payload hợp lệ")
    void realOshiCollectNeverThrows() {
        OshiNetworkMetricsCollector collector = new OshiNetworkMetricsCollector();

        MetricsPayload first = collector.collect(); // baseline trên NIC thật
        assertNotNull(first);
        assertEquals("ACTIVE", first.networkStatus());
        assertTrue(first.sessionDurationSeconds() >= 0);

        MetricsPayload second = collector.collect(); // delta thật trên máy đang chạy test
        assertNotNull(second);
        assertEquals("ACTIVE", second.networkStatus());
        assertTrue(second.downloadBytes() >= 0);
        assertTrue(second.uploadBytes() >= 0);
        assertTrue(second.downloadSpeedBps() >= 0);
        assertTrue(second.uploadSpeedBps() >= 0);
    }
}
