package com.parental.client.enforcement;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock {@link NetworkEnforcer} public top-level — dùng chung cho unit test
 * {@code ActionDispatcherTest} và integration test
 * {@code TcpClientEngineIntegrationTest} (khác package). Ghi nhận số lần
 * pause/resume/cleanup, KHÔNG BAO GIỜ chạy netsh thật.
 */
public class MockNetworkEnforcer implements NetworkEnforcer {

    public final AtomicInteger pauseCount = new AtomicInteger();
    public final AtomicInteger resumeCount = new AtomicInteger();
    public final AtomicInteger cleanupCount = new AtomicInteger();
    public final AtomicInteger failNextCount = new AtomicInteger(0);
    volatile boolean paused = false;

    @Override
    public boolean pauseNetwork() {
        if (failNextCount.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
            return false;
        }
        pauseCount.incrementAndGet();
        paused = true;
        return true;
    }

    @Override
    public boolean resumeNetwork() {
        if (failNextCount.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
            return false;
        }
        resumeCount.incrementAndGet();
        paused = false;
        return true;
    }

    @Override
    public String getNetworkStatus() {
        return paused ? STATUS_PAUSED : STATUS_ACTIVE;
    }

    @Override
    public void cleanup() {
        cleanupCount.incrementAndGet();
        paused = false;
    }
}
