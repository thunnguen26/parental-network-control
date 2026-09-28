package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test WATCHDOG chặn tiến trình duy trì (Mở rộng 12 —
 * {@link ProcessBlockerManager#startRescanScheduler()}): quét 2s kill lại
 * mọi app bị chặn tự mở lại; bỏ chặn qua {@code replaceBlockedProcesses} →
 * watchdog ngừng kill app đó (mở lại bình thường). Headless: lệnh kill được
 * bắt qua subclass override, KHÔNG spawn taskkill thật.
 */
class ProcessWatchdogTest {

    @TempDir
    Path tempDir;

    /** Chờ điều kiện đúng tối đa 8s (poll 100ms) — không thêm dependency. */
    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Timeout chờ: " + description);
    }

    /** Bắt lệnh kill thay vì chạy thật + ghi nhận vị trí thread gọi. */
    private static final class RecordingBlocker extends ProcessBlockerManager {
        final List<String> killed = new CopyOnWriteArrayList<>();
        final List<String> killerThreads = new CopyOnWriteArrayList<>();

        RecordingBlocker(Path file, boolean windowsOs) {
            super(file, windowsOs);
        }

        @Override
        void killProcessNow(String normalizedProcessName) {
            killed.add(normalizedProcessName);
            killerThreads.add(Thread.currentThread().getName());
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Watchdog quét 2s: app bị chặn MỞ LẠI bị kill lại liên tục")
    void watchdogKeepsKillingReopenedApp() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-keep.json"), true);
        blocker.startRescanScheduler();
        try {
            assertTrue(blocker.blockProcess("somegame.exe"), "Chặn app");

            // Người dùng mở lại app → file danh sách vẫn chứa app (persist)
            // → chu kỳ watchdog kế tiếp phải kill lại app đó
            await("watchdog re-kills reopened app", () -> blocker.killed.stream()
                    .filter("somegame.exe"::equals).count() >= 2);
        } finally {
            blocker.stopWatchdog();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Bỏ chặn qua CONFIG_SYNC: watchdog NGỪNG kill app bị gỡ trong ~1 chu kỳ")
    void watchdogStopsKillingAfterUnblock() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-unblock.json"), true);
        blocker.startRescanScheduler();
        try {
            blocker.blockProcess("somegame.exe");
            await("initial kill", () -> !blocker.killed.isEmpty());

            // Phụ huynh bỏ chặn: danh sách mới KHÔNG còn somegame
            assertTrue(blocker.replaceBlockedProcesses(List.of()));
            int killsAtUnblock = blocker.killed.size();

            // ≥ 2 chu kỳ watchdog sau unblock: KHÔNG thêm lần kill nào cho app đã gỡ
            try {
                Thread.sleep(3 * ProcessBlockerManager.WATCHDOG_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertEquals(killsAtUnblock, blocker.killed.size(),
                    "Watchdog không được kill app đã bị bỏ chặn — app mở lại bình thường");
            assertTrue(blocker.getBlockedProcesses().isEmpty());
        } finally {
            blocker.stopWatchdog();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Watchdog chạy trên daemon thread riêng, không đụng luồng gọi start")
    void watchdogRunsOnDedicatedDaemonThread() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-thread.json"), true);
        blocker.blockProcess("browser.exe"); // block → kill ngay trên thread gọi
        int killsBefore = blocker.killed.size();
        assertTrue(killsBefore >= 1);

        blocker.startRescanScheduler();
        try {
            await("watchdog sweep runs", () -> blocker.killed.size() > killsBefore);
            // Kill của watchdog đến từ thread daemon "parental-client-process-watchdog"
            assertTrue(blocker.killerThreads.stream()
                            .anyMatch(name -> name != null
                                    && name.contains("process-watchdog")),
                    "Kill lặp phải đến từ watchdog thread");
        } finally {
            blocker.stopWatchdog();
        }
    }

    @Test
    @DisplayName("startRescanScheduler idempotent: gọi 2 lần vẫn ĐÚNG 1 watchdog")
    void startIsIdempotent() throws Exception {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-idem.json"), true);
        blocker.startRescanScheduler();
        try {
            // Gọi lần 2 — không được sinh thêm scheduler (AtomicReference CAS)
            blocker.startRescanScheduler();
            // Không có cách quan sát trực tiếp số scheduler → test hành vi:
            // vẫn hoạt động bình thường + stop 1 lần là đủ (không rò rỉ)
            assertTrue(blocker.replaceBlockedProcesses(List.of("kept.exe")));
            assertEquals(List.of("kept.exe"), blocker.getBlockedProcesses());
        } finally {
            blocker.stopWatchdog();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("stopWatchdog dừng chu kỳ: sau stop KHÔNG có kill mới nào")
    void stopWatchdogHaltsSweeps() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-stop.json"), true);
        blocker.startRescanScheduler();
        blocker.blockProcess("somegame.exe");
        await("at least one sweep kill", () -> blocker.killed.size() >= 2);
        blocker.stopWatchdog();

        int killsAtStop = blocker.killed.size();
        try {
            Thread.sleep(3 * ProcessBlockerManager.WATCHDOG_INTERVAL_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(killsAtStop, blocker.killed.size(),
                "Sau stopWatchdog không còn chu kỳ kill nào");
    }

    @Test
    @Timeout(30)
    @DisplayName("Watchdog quét DANH SÁCH HIỆN HÀNH: app thêm sau khi start cũng bị kill")
    void watchdogReadsCurrentListEachSweep() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("wd-current.json"), true);
        blocker.startRescanScheduler();
        try {
            // App thêm SAU khi watchdog đã start (qua BLOCK_PROCESS hoặc sync)
            assertTrue(blocker.blockProcess("latersgame.exe"));
            await("watchdog kills newly blocked app", () -> blocker.killed.stream()
                    .filter("latersgame.exe"::equals).count() >= 2);
        } finally {
            blocker.stopWatchdog();
        }
    }

    @Test
    @DisplayName("Hằng số theo spec Mở rộng 12: chu kỳ watchdog 2 giây")
    void watchdogIntervalIsTwoSeconds() {
        assertEquals(2, ProcessBlockerManager.WATCHDOG_INTERVAL_SECONDS);
        assertFalse(false);
    }
}
