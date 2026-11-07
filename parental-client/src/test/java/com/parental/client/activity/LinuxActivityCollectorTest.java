package com.parental.client.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Unit test JVM cho {@link LinuxActivityCollector} — stub CommandRunner
 * (không cần Linux thật / xdotool thật), xác minh 2 lớp thu thập + contract
 * payload rỗng an toàn.
 */
class LinuxActivityCollectorTest {

    /** Runner trả kết quả lập sẵn theo lệnh (command → output). */
    private static LinuxActivityCollector collectorWith(
            java.util.function.Function<String, LinuxActivityCollector.CommandResult> behavior) {
        return new LinuxActivityCollector(command -> behavior.apply(String.join(" ", command)));
    }

    private static final LinuxActivityCollector.CommandResult OK_PID =
            new LinuxActivityCollector.CommandResult(0, "12345\n", "");
    private static final LinuxActivityCollector.CommandResult OK_TITLE =
            new LinuxActivityCollector.CommandResult(0, "Giải bài tập Hóa 12 - Mozilla Firefox\n", "");
    private static final LinuxActivityCollector.CommandResult OK_PS =
            new LinuxActivityCollector.CommandResult(0, """
                    COMMAND   PID
                    firefox  1234
                    vlc      2222
                    bash     3333
                    """, "");
    private static final LinuxActivityCollector.CommandResult FAIL =
            new LinuxActivityCollector.CommandResult(1, "", "command not found");

    @Test
    @DisplayName("Lớp 1 xdotool: active = cửa sổ focus thật (PID → comm, title giữ nguyên)")
    void xdotoolActiveWindowWins() {
        LinuxActivityCollector collector = collectorWith(cmd -> {
            if (cmd.equals("xdotool getactivewindow getwindowpid")) return OK_PID;
            if (cmd.equals("xdotool getactivewindow getwindowname")) return OK_TITLE;
            if (cmd.startsWith("ps -p ")) return new LinuxActivityCollector.CommandResult(0, "firefox\n", "");
            if (cmd.startsWith("ps -eo")) return OK_PS;
            return FAIL;
        });
        ActivityReportPayload payload = collector.collect();
        assertEquals("firefox", payload.activeProcessName());
        assertEquals("Giải bài tập Hóa 12 - Mozilla Firefox", payload.activeWindowTitle());
        assertTrue(payload.topProcesses().contains("firefox"));
    }

    @Test
    @DisplayName("xdotool thất bại (chưa cài/Wayland) → fallback ps ưu tiên app đồ họa")
    void fallsBackToPsWhenXdotoolMissing() {
        LinuxActivityCollector collector = collectorWith(cmd ->
                cmd.startsWith("xdotool") ? FAIL : (cmd.startsWith("ps -eo") ? OK_PS : FAIL));
        ActivityReportPayload payload = collector.collect();
        assertEquals("firefox", payload.activeProcessName(), "app đồ họa CPU cao nhất được ưu tiên");
        assertTrue(payload.topProcesses().contains("vlc"));
        assertEquals(2, payload.topProcesses().size(), "firefox + vlc (lọc bash)");
    }

    @Test
    @DisplayName("Không có app đồ họa → fallback chọn tiến trình CPU cao nhất")
    void fallbackWithoutGraphicalAppsTakesTopCpu() {
        String noGui = "COMMAND  PID\nbash     1\npython3  2\n";
        LinuxActivityCollector collector = collectorWith(cmd ->
                cmd.startsWith("xdotool") ? FAIL
                        : new LinuxActivityCollector.CommandResult(0, noGui, ""));
        ActivityReportPayload payload = collector.collect();
        assertEquals("python3", payload.activeProcessName());
    }

    @Test
    @DisplayName("Cả xdotool lẫn ps lỗi → payload RỖNG, không null, không ném")
    void everythingFailsReturnsEmptyPayload() {
        LinuxActivityCollector collector = collectorWith(cmd -> FAIL);
        ActivityReportPayload payload = collector.collect();
        assertEquals("", payload.activeProcessName());
        assertTrue(payload.topProcesses().isEmpty());
    }

    @Test
    @DisplayName("Runner ném RuntimeException → collect() nuốt, trả payload rỗng")
    void runnerThrowingIsSwallowed() {
        LinuxActivityCollector collector = new LinuxActivityCollector(cmd -> {
            throw new IllegalStateException("boom");
        });
        ActivityReportPayload payload = collector.collect();
        assertEquals("", payload.activeProcessName());
        assertTrue(payload.topProcesses().isEmpty());
    }

    @Test
    @DisplayName("parsePsCommLines: lowercase, khử trùng lặp, lọc tiến trình hệ thống")
    void parsePsLinesNormalizesAndFilters() {
        List<String> parsed = LinuxActivityCollector.parsePsCommLines(
                "COMMAND  PID\nFIREFOX  1\nfirefox  2\nbash     3\nchrome   4\n\n");
        assertEquals(List.of("firefox", "chrome"), parsed, "khử trùng lặp + lọc bash + lowercase");
    }

    @Test
    @DisplayName("isSystemProcess/isGraphicalApp phân loại đúng")
    void classificationHelpers() {
        assertTrue(LinuxActivityCollector.isSystemProcess("kworker/0:1"));
        assertTrue(LinuxActivityCollector.isSystemProcess("gnome-shell"));
        assertTrue(LinuxActivityCollector.isGraphicalApp("chromium-browser"));
        assertTrue(LinuxActivityCollector.isGraphicalApp("firefox-esr"));
        assertTrue(!LinuxActivityCollector.isGraphicalApp("bash"));
    }
}
