package com.parental.client.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Unit test {@link WindowsActivityCollector} (Activity Monitoring — Mục 3.9):
 * parse đầu ra PowerShell (không spawn process thật — chạy headless an toàn),
 * defensive với output rỗng/hỏng/chuỗi lỗi PowerShell (hotfix: lỗi tuyệt đối
 * không được parse thành payload), giới hạn 5 tiến trình, non-Windows →
 * payload rỗng không ném exception.
 *
 * <p><b>Mở rộng (Foreground Window thật)</b>: dòng ĐẦU TIÊN của stdout là
 * Foreground Window từ Win32 API (GetForegroundWindow) — luôn là active
 * window; các dòng sau là top processes (fallback). Script P/Invoke được
 * test theo contract cấu trúc (xem {@code psScriptUsesWin32ForegroundApi}).</p>
 */
class WindowsActivityCollectorTest {

    private final WindowsActivityCollector collector = new WindowsActivityCollector(false);

    @Test
    @DisplayName("parseOutput: format 'process<TAB>title' — dòng ĐẦU = Foreground/Active (Win32 API)")
    void parsesProcessTitleLines() {
        String output = "chrome\tGiải bài tập Hóa 12 | VietJack - Google Chrome\n"
                + "notepad\tBài tập Toán - Notepad\n"
                + "explorer\tDocuments";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(),
                "Dòng đầu (Foreground Window từ Win32 API) = active");
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", payload.activeWindowTitle());
        assertEquals(java.util.List.of("chrome", "notepad", "explorer"), payload.topProcesses());
        assertTrue(payload.timestamp() > 0, "timestamp epoch millis phải > 0");
    }

    @Test
    @DisplayName("parseOutput: Foreground KHÔNG lấy được (stdout chỉ có ROW) → dòng CPU cao nhất là active (fallback)")
    void foregroundFallbackToTopProcess() {
        // Script Foreground lỗi (return rỗng) → stdout chỉ còn danh sách Get-Process
        ActivityReportPayload payload = collector.parseOutput(
                "msedge\tMicrosoft Edge – Trang chủ\nexplorer\tDocuments");

        assertEquals("msedge.exe", payload.activeProcessName(), "Fallback: tiến trình đầu = active");
        assertEquals("Microsoft Edge – Trang chủ", payload.activeWindowTitle());
    }

    @Test
    @DisplayName("parseOutput: chỉ 1 dòng → active = tiến trình đó, top 1 phần tử")
    void singleLineParses() {
        ActivityReportPayload payload = collector.parseOutput("garena\tLMHT - Garena");
        assertEquals("garena.exe", payload.activeProcessName());
        assertEquals("LMHT - Garena", payload.activeWindowTitle());
        assertEquals(1, payload.topProcesses().size());
    }

    @Test
    @DisplayName("parseOutput: dòng lỗi PowerShell ('The term ... is not recognized') bị LOẠI, không vào payload (hotfix)")
    void powerShellErrorLinesAreRejected() {
        // Mô phỏng stdout chứa chuỗi lỗi PowerShell lọt qua (hotfix regression)
        String output = ": The term 'ACTIVE.exe — ' is not recognized as the name of a cmdlet, "
                + "function, script file, or operable program.\n"
                + "chrome\tChrome Window";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(),
                "Chỉ dòng hợp lệ 'chrome<TAB>title' được nhận");
        assertEquals(java.util.List.of("chrome"), payload.topProcesses());
        assertTrue(payload.activeWindowTitle().contains("Chrome Window"));
        assertTrue(!payload.activeProcessName().contains("The term"),
                "Chuỗi lỗi KHÔNG được trở thành tên tiến trình");
    }

    @Test
    @DisplayName("parseOutput: Foreground trả 'NoWindow'/'none' (màn hình khóa) → BỎ dòng đó, fallback danh sách")
    void foregroundPlaceholderLineIsSkipped() {
        // Foreground lấy được handle nhưng không có window text → sentinel,
        // phải bị bỏ; active = tiến trình đầu trong danh sách ROW.
        ActivityReportPayload payload = collector.parseOutput(
                "none\t\nmsedge\tMicrosoft Edge – Trang chủ");

        assertEquals("msedge.exe", payload.activeProcessName(), "Sentinel/Foreground rỗng bị bỏ qua");
        assertEquals("Microsoft Edge – Trang chủ", payload.activeWindowTitle());
    }

    @Test
    @DisplayName("parseOutput: output rỗng/null/hỏng → payload rỗng (không ném)")
    void emptyOrBrokenOutputReturnsEmptyPayload() {
        ActivityReportPayload empty = collector.parseOutput("");
        assertEquals("", empty.activeProcessName());
        assertEquals("", empty.activeWindowTitle());
        assertTrue(empty.topProcesses().isEmpty());

        assertNotNull(collector.parseOutput(null));
        assertNotNull(collector.parseOutput("random garbage text"));
        assertNotNull(collector.parseOutput("no-tab-line\nanother-no-tab"));
    }

    @Test
    @DisplayName("parseOutput: tối đa 5 tiến trình (MAX_TOP_PROCESSES), khử trùng lặp")
    void capsTopProcessesAtFive() {
        StringBuilder output = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            output.append("app").append(i).append("\tTitle ").append(i).append('\n');
        }
        output.append("app1\tDuplicate\n"); // trùng — bị bỏ

        ActivityReportPayload payload = collector.parseOutput(output.toString());

        assertEquals(5, payload.topProcesses().size(),
                "Tối đa " + ActivityReportPayload.MAX_TOP_PROCESSES + " tiến trình");
        assertEquals("app1.exe", payload.activeProcessName());
    }

    @Test
    @DisplayName("Non-Windows collect() → payload rỗng an toàn, không spawn process, không ném")
    void nonWindowsCollectReturnsEmptyPayload() {
        ActivityReportPayload payload = collector.collect();

        assertNotNull(payload);
        assertEquals("", payload.activeProcessName());
        assertEquals("", payload.activeWindowTitle());
        assertTrue(payload.topProcesses().isEmpty());
    }

    @Test
    @DisplayName("PS_SCRIPT: dùng Win32 P/Invoke GetForegroundWindow (không Add-Type C# multi-line dễ vỡ)")
    void psScriptUsesWin32ForegroundApi() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertTrue(script.contains("GetForegroundWindow"), "Phải P/Invoke GetForegroundWindow");
        assertTrue(script.contains("GetWindowText"), "Phải đọc tiêu đề cửa sổ");
        assertTrue(script.contains("GetWindowThreadProcessId"), "Phải suy PID cửa sổ foreground");
        assertTrue(script.contains("MainWindowTitle -ne ''"),
                "Bộ lọc top processes: chỉ ứng dụng CÓ tiêu đề cửa sổ thực (loại tiến trình nền)");
        assertTrue(script.contains("MainWindowHandle -ne 0"),
                "Chỉ nhận tiến trình có cửa sổ thật (bỏ tiến trình nền hệ thống)");
        assertFalse(script.contains("@\""),
                "KHÔNG dùng here-string C# multi-line (nguyên nhân lỗi escape của hotfix trước)");
        assertTrue(script.contains("-MemberDefinition"),
                "P/Invoke trực tiếp trong PS (Add-Type -MemberDefinition) — syntax-safe qua ProcessBuilder");
    }

    @Test
    @DisplayName("parseOutput: title chứa TAB sâu (title có nhiều TAB) → giữ nguyên phần title")
    void titleWithTabsKeepsRest() {
        ActivityReportPayload payload = collector.parseOutput("chrome\tA\tB\tC");
        assertEquals("A\tB\tC", payload.activeWindowTitle(),
                "Phần sau TAB đầu tiên là title nguyên vẹn");
    }
}
