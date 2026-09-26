package com.parental.client.metrics;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Stub {@link NetworkMetricsCollector} public cho integration test (package
 * network dùng chéo) — ghi nhận status được sync, trả payload cố định.
 */
public class StubMetricsCollectorAccess implements NetworkMetricsCollector {

    /** Status hiện hành — ActionDispatcher sync qua setNetworkStatus. */
    public volatile String status = STATUS_ACTIVE;
    /** Giá trị downloadBytes trả về khi collect. */
    public volatile long downloadBytes = 0;
    /** Giá trị uploadBytes trả về khi collect. */
    public volatile long uploadBytes = 0;
    /** Số lần collect được gọi. */
    public final java.util.concurrent.atomic.AtomicInteger collectCount =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public MetricsPayload collect() {
        collectCount.incrementAndGet();
        return new MetricsPayload(downloadBytes, uploadBytes, 0, 0, 1, status);
    }

    @Override
    public void setNetworkStatus(String status) {
        this.status = status;
    }
}
