package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test luồng chặn → BỎ CHẶN tiến trình qua CONFIG_SYNC (Mở rộng 11 —
 * {@link ProcessBlockerManager#replaceBlockedProcesses}): REPLACE-ALL, gỡ app
 * khỏi danh sách = ngừng kill, idempotent, defensive. Kill được bắt qua
 * subclass override (không spawn taskkill thật).
 */
class ProcessUnblockFlowTest {

    @TempDir
    Path tempDir;

    /** Bắt lệnh kill thay vì chạy thật. */
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

    private RecordingBlocker newBlocker() {
        return new RecordingBlocker(
                tempDir.resolve("blocked-" + System.nanoTime() + ".json"), true);
    }

    @Test
    @DisplayName("Chặn → bỏ chặn qua CONFIG_SYNC: app gỡ khỏi danh sách NGỪNG bị kill, app khác giữ nguyên")
    void unblockStopsKillingRemovedApp() {
        RecordingBlocker blocker = newBlocker();

        // Phase 1: server đồng bộ 2 app bị chặn
        // (client giữ THỨ TỰ INSERTION từ LinkedHashSet — khác server sort theo tên)
        assertTrue(blocker.replaceBlockedProcesses(List.of("somegame.exe", "browser.exe")));
        assertEquals(List.of("somegame.exe", "browser.exe"), blocker.getBlockedProcesses());
        assertEquals(2, blocker.killed.size(), "Kill ngay cả app trong lần đồng bộ đầu");

        // Phase 2: phụ huynh BỎ CHẶN somegame — danh sách mới chỉ còn browser
        blocker.killed.clear();
        assertTrue(blocker.replaceBlockedProcesses(List.of("browser.exe")));

        assertEquals(List.of("browser.exe"), blocker.getBlockedProcesses(),
                "somegame đã bị GỠ khỏi danh sách = bỏ chặn");
        assertEquals(List.of("browser.exe"), blocker.killed,
                "Chỉ kill app còn trong danh sách — somegame KHÔNG bị kill nữa");
    }

    @Test
    @DisplayName("Bỏ chặn TẤT CẢ (danh sách rỗng) → dọn sạch, không kill gì")
    void emptyListClearsEverything() {
        RecordingBlocker blocker = newBlocker();
        blocker.replaceBlockedProcesses(List.of("somegame.exe"));
        blocker.killed.clear();

        assertTrue(blocker.replaceBlockedProcesses(List.of()));

        assertTrue(blocker.getBlockedProcesses().isEmpty(),
                "Danh sách rỗng = dọn sạch mọi chặn tiến trình");
        assertTrue(blocker.killed.isEmpty(), "Không còn app nào bị kill");
    }

    @Test
    @DisplayName("REPLACE-ALL idempotent: đồng bộ 2 lần cùng danh sách → file không đổi, không nhân bản")
    void replaceIsIdempotent() {
        RecordingBlocker blocker = newBlocker();
        blocker.replaceBlockedProcesses(List.of("somegame.exe", "browser.exe"));
        List<String> first = blocker.getBlockedProcesses();

        blocker.replaceBlockedProcesses(java.util.Arrays.asList(
                "BROWSER.EXE", "somegame", " ", null));
        // So khớp không phụ thuộc thứ tự: client giữ insertion order theo từng lần sync,
        // idempotent nghĩa là CÙNG TẬP PHẦN TỬ (không nhân bản, không mất mát).
        assertEquals(first.stream().sorted().toList(),
                blocker.getBlockedProcesses().stream().sorted().toList(),
                "Danh sách normalize + khử trùng lặp — idempotent qua nhiều lần sync");
    }

    @Test
    @DisplayName("Persist qua instance mới: sau REPLACE, client restart vẫn đúng danh sách server")
    void replaceSurvivesRestart() throws Exception {
        Path file = tempDir.resolve("blocked-restart.json");
        RecordingBlocker blocker = new RecordingBlocker(file, true);
        blocker.replaceBlockedProcesses(List.of("keptapp.exe", "removedapp.exe"));
        blocker.replaceBlockedProcesses(List.of("keptapp.exe"));

        // Client khởi động lại — đọc lại file do lần sync CUỐI quyết định
        ProcessBlockerManager afterRestart = new ProcessBlockerManager(file, false);
        assertEquals(List.of("keptapp.exe"), afterRestart.getBlockedProcesses(),
                "REPLACE-ALL phải persist qua restart — removedapp không còn bị chặn");
    }

    @Test
    @DisplayName("BLOCK_PROCESS lẻ vẫn hoạt động song song: blockProcess thêm 1 app rồi REPLACE gỡ")
    void singleBlockThenReplaceUnblock() {
        RecordingBlocker blocker = newBlocker();

        assertTrue(blocker.blockProcess("somegame.exe")); // lệnh BLOCK_PROCESS lẻ
        assertEquals(List.of("somegame.exe"), blocker.getBlockedProcesses());

        // Server sync danh sách KHÔNG chứa somegame → bỏ chặn
        assertTrue(blocker.replaceBlockedProcesses(List.of()));
        assertTrue(blocker.getBlockedProcesses().isEmpty(),
                "REPLACE-ALL ghi đè cả app thêm bằng lệnh lẻ — bỏ chặn triệt để");
    }

    @Test
    @DisplayName("normalize danh sách sync: 'Browser' → browser.exe; phần tử hỏng bỏ qua")
    void replaceNormalizesEntries() {
        RecordingBlocker blocker = newBlocker();

        assertTrue(blocker.replaceBlockedProcesses(
                java.util.Arrays.asList("Browser", "SOMEGAME.EXE", "bad name!", "", null)));

        assertEquals(List.of("browser.exe", "somegame.exe"), blocker.getBlockedProcesses(),
                "Tên được chuẩn hóa lowercase + .exe; phần tử hỏng bị bỏ qua");
    }

    @Test
    @DisplayName("Non-Windows: replace vẫn persist (kill là no-op)")
    void nonWindowsReplacePersists() {
        ProcessBlockerManager blocker = new ProcessBlockerManager(
                tempDir.resolve("blocked-nw.json"), false);

        assertTrue(blocker.replaceBlockedProcesses(List.of("somegame.exe")));
        assertEquals(List.of("somegame.exe"), blocker.getBlockedProcesses());
    }

    @Test
    @DisplayName("File hỏng + REPLACE → ghi đè file hỏng, hoạt động bình thường")
    void corruptedFileRecoveredByReplace() throws Exception {
        Path file = tempDir.resolve("blocked-corrupt.json");
        Files.writeString(file, "{không phải json");

        RecordingBlocker blocker = new RecordingBlocker(file, true);
        assertTrue(blocker.replaceBlockedProcesses(List.of("somegame.exe")));
        assertEquals(List.of("somegame.exe"), blocker.getBlockedProcesses());
        assertFalse(Files.readString(file).contains("không phải json"),
                "File hỏng phải bị ghi đè bởi danh sách chuẩn");
    }
}
