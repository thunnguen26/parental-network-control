package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test {@link ProcessBlockerManager} (Mở rộng 10 — chặn app .exe
 * 1-click): chuẩn hóa tên, persist danh sách JSON, taskkill lệnh đúng,
 * idempotent, defensive file hỏng, non-Windows no-op. Chạy với OS=false
 * (không spawn taskkill thật) + bắt lệnh qua subclass override.
 */
class ProcessBlockerManagerTest {

    @TempDir
    Path tempDir;

    /** Bắt lệnh taskkill thay vì chạy thật. */
    private static final class RecordingBlocker extends ProcessBlockerManager {
        final List<String> killed = new CopyOnWriteArrayList<>();

        RecordingBlocker(Path file, boolean windowsOs) {
            super(file, windowsOs);
        }

        @Override
        void killProcessNow(String normalizedProcessName) {
            killed.add(normalizedProcessName);
        }
    }

    private ProcessBlockerManager newManager(boolean windowsOs) {
        return new ProcessBlockerManager(
                tempDir.resolve("blocked-" + System.nanoTime() + ".json"), windowsOs);
    }

    @Test
    @DisplayName("normalizeProcessName: thêm .exe, lowercase, từ chối rỗng/ký tự lạ/..")
    void normalizeProcessName() {
        assertEquals("somegame.exe", ProcessBlockerManager.normalizeProcessName("SomeGame"));
        assertEquals("somegame.exe", ProcessBlockerManager.normalizeProcessName("  SOMEGAME.EXE  "));
        assertNull(ProcessBlockerManager.normalizeProcessName(null));
        assertNull(ProcessBlockerManager.normalizeProcessName("   "));
        assertNull(ProcessBlockerManager.normalizeProcessName(".exe"));
        assertNull(ProcessBlockerManager.normalizeProcessName("bad name!.exe"));
        assertNull(ProcessBlockerManager.normalizeProcessName("bad..name"));
    }

    @Test
    @DisplayName("blockProcess: lưu danh sách persist + kill ngay; block lặp idempotent không nhân bản")
    void blockProcessPersistsAndKills() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("blocked-a.json"), true);

        assertTrue(blocker.blockProcess("SomeGame"), "Hợp lệ → true");
        assertTrue(blocker.blockProcess("somegame.exe"), "Block lặp → vẫn true (idempotent)");
        assertTrue(blocker.blockProcess("anotherapp"));

        assertEquals(List.of("somegame.exe", "somegame.exe", "anotherapp.exe"),
                blocker.killed, "Mỗi lần block đều kill lại instance đang chạy");
        assertEquals(List.of("somegame.exe", "anotherapp.exe"), blocker.getBlockedProcesses(),
                "Danh sách không nhân bản (3 lần block 2 app)");
    }

    @Test
    @DisplayName("blockProcess: danh sách persist qua instance MỚI (restart giả lập)")
    void blockedListSurvivesRestart() throws Exception {
        Path file = tempDir.resolve("blocked-restart.json");
        RecordingBlocker first = new RecordingBlocker(file, true);
        first.blockProcess("eldenring");
        first.blockProcess("robloxplayerbeta");

        // Instance mới đọc lại cùng file — như client khởi động lại
        ProcessBlockerManager second = new ProcessBlockerManager(file, false);
        assertEquals(List.of("eldenring.exe", "robloxplayerbeta.exe"),
                second.getBlockedProcesses(),
                "Danh sách phải persist qua restart (blocked_processes.json)");
    }

    @Test
    @DisplayName("blockProcess: đầu vào hỏng → false, không ghi file, không kill")
    void invalidInputRejected() {
        RecordingBlocker blocker = new RecordingBlocker(
                tempDir.resolve("blocked-b.json"), true);

        assertFalse(blocker.blockProcess(null));
        assertFalse(blocker.blockProcess("   "));
        assertFalse(blocker.blockProcess("bad name!"));
        assertTrue(blocker.killed.isEmpty(), "Input hỏng không được kill gì cả");
        assertTrue(blocker.getBlockedProcesses().isEmpty());
    }

    @Test
    @DisplayName("File JSON hỏng → defensive coi như rỗng, không ném")
    void corruptedFileTreatedAsEmpty() throws Exception {
        Path file = tempDir.resolve("blocked-corrupt.json");
        Files.writeString(file, "{không phải json array");

        ProcessBlockerManager blocker = new ProcessBlockerManager(file, false);
        assertTrue(blocker.getBlockedProcesses().isEmpty(), "File hỏng → danh sách rỗng");
        assertTrue(blocker.blockProcess("newapp.exe"), "Vẫn block được — ghi đè file hỏng");
        assertEquals(List.of("newapp.exe"), blocker.getBlockedProcesses());
    }

    @Test
    @DisplayName("clearAll: xóa sạch danh sách + file (dùng khi unpair)")
    void clearAllRemovesEverything() throws Exception {
        Path file = tempDir.resolve("blocked-clear.json");
        RecordingBlocker blocker = new RecordingBlocker(file, true);
        blocker.blockProcess("somegame");

        assertTrue(blocker.clearAll());
        assertTrue(blocker.getBlockedProcesses().isEmpty());
        assertFalse(Files.exists(file), "File danh sách phải bị xóa");
        assertTrue(blocker.clearAll(), "clearAll lần 2 vẫn true (không còn gì)");
    }

    @Test
    @DisplayName("Non-Windows: blockProcess vẫn persist danh sách nhưng không spawn taskkill")
    void nonWindowsPersistsWithoutKill() {
        ProcessBlockerManager blocker = newManager(false);

        assertTrue(blocker.blockProcess("somegame"));
        assertEquals(List.of("somegame.exe"), blocker.getBlockedProcesses(),
                "Danh sách vẫn persist trên Linux/macOS (no-op taskkill)");
    }

    @Test
    @DisplayName("Hằng số theo spec: quét lại 60s, taskkill timeout 5s")
    void constantsMatchSpec() {
        assertEquals(60, ProcessBlockerManager.RESCAN_INTERVAL_SECONDS);
        assertEquals(5, ProcessBlockerManager.TASKKILL_TIMEOUT_SECONDS);
        assertEquals("blocked_processes.json", ProcessBlockerManager.BLOCKED_PROCESSES_FILE);
    }
}
