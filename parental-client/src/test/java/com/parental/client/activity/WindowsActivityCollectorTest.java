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
 * parse đầu ra PowerShell định dạng {@code process|title} (không spawn process
 * thật — headless an toàn), <b>User App Filter</b> (bỏ tiến trình hệ thống +
 * tiêu đề rác, ưu tiên app người dùng tương tác), defensive với output
 * rỗng/hỏng/chuỗi lỗi PowerShell, non-Windows → payload rỗng.
 */
class WindowsActivityCollectorTest {

    private final WindowsActivityCollector collector = new WindowsActivityCollector(false);

    @Test
    @DisplayName("parseOutput: format 'process|title' — app người dùng đầu tiên = active")
    void parsesPipeLines() {
        String output = "chrome|Giải bài tập Hóa 12 | VietJack - Google Chrome\n"
                + "notepad|Bài tập Toán - Notepad\n"
                + "firefox|Trang chủ";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName());
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", payload.activeWindowTitle());
        assertEquals(java.util.List.of("chrome", "notepad", "firefox"), payload.topProcesses());
        assertTrue(payload.timestamp() > 0, "timestamp epoch millis phải > 0");
    }

    @Test
    @DisplayName("User App Filter: ƯU TIÊN app người dùng tương tác làm active (bỏ qua app khác đứng trước)")
    void userAppPrioritizedAsActive() {
        // Dòng đầu (CPU cao nhất) là app lạ — chrome đứng sau vẫn được ưu tiên
        String output = "java|Apache Maven Wrapper\n"
                + "chrome|YouTube - Google Chrome\n"
                + "notepad|Ghi chú bài học";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(),
                "App nhóm người dùng tương tác (browser) phải được ưu tiên làm active");
        assertEquals("YouTube - Google Chrome", payload.activeWindowTitle());
    }

    @Test
    @DisplayName("User App Filter: tiến trình hệ thống (explorer/dwm/powershell...) bị LOẠI khỏi payload")
    void systemProcessesAreFiltered() {
        String output = "explorer|Program Manager\n"
                + "dwm|DWM Window\n"
                + "powershell|Windows PowerShell\n"
                + "chrome|Google Chrome";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(), "Chỉ còn app người dùng");
        assertEquals(java.util.List.of("chrome"), payload.topProcesses(),
                "explorer/dwm/powershell không được xuất hiện trong topProcesses");
    }

    @Test
    @DisplayName("User App Filter: tiêu đề rác (≤ 2 ký tự, tên cửa sổ OS) bị loại")
    void meaninglessTitlesRejected() {
        String output = "someapp|ab\n"             // ≤ 2 ký tự
                + "otherapp|Windows Security\n"    // cửa sổ hệ điều hành
                + "thirdapp|Settings\n"            // cửa sổ OS
                + "chrome|Bài tập Toán";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals(java.util.List.of("chrome"), payload.topProcesses(),
                "Chỉ tiêu đề CÓ Ý NGHĨA (> 2 ký tự, không phải tên OS) được giữ");
        assertEquals("Bài tập Toán", payload.activeWindowTitle());
    }

    @Test
    @DisplayName("Chỉ toàn app hệ thống/tiêu đề rác → payload rỗng chuẩn (không rác gửi lên Server)")
    void onlySystemAppsReturnEmptyPayload() {
        ActivityReportPayload payload = collector.parseOutput(
                "explorer|Program Manager\ndwm|DWM\nsvchost|ab");

        assertEquals("", payload.activeProcessName());
        assertEquals("", payload.activeWindowTitle());
        assertTrue(payload.topProcesses().isEmpty());
    }

    @Test
    @DisplayName("parseOutput: dòng lỗi PowerShell ('is not recognized') bị LOẠI, không vào payload (regression)")
    void powerShellErrorLinesAreRejected() {
        String output = ": The term 'ACTIVE.exe — ' is not recognized as the name of a cmdlet\n"
                + "chrome|Chrome Window";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName());
        assertTrue(!payload.activeProcessName().contains("The term"),
                "Chuỗi lỗi KHÔNG được trở thành tên tiến trình");
    }

    @Test
    @DisplayName("parseOutput: title chứa nhiều dấu | → giữ nguyên phần title sau dấu | đầu")
    void titleWithPipesKeepsRest() {
        ActivityReportPayload payload = collector.parseOutput("chrome|A|B|C");
        assertEquals("A|B|C", payload.activeWindowTitle(),
                "Phần sau dấu | đầu tiên là title nguyên vẹn");
    }

    @Test
    @DisplayName("parseOutput: tối đa 5 tiến trình (MAX_TOP_PROCESSES), khử trùng lặp")
    void capsTopProcessesAtFive() {
        StringBuilder output = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            output.append("app").append(i).append("|Title ").append(i).append('\n');
        }
        output.append("app1|Duplicate\n"); // trùng — distinct khử

        ActivityReportPayload payload = collector.parseOutput(output.toString());

        assertEquals(5, payload.topProcesses().size(),
                "Tối đa " + ActivityReportPayload.MAX_TOP_PROCESSES + " tiến trình");
        assertEquals("app1.exe", payload.activeProcessName(), "Fallback: phần tử đầu hợp lệ");
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
        assertNotNull(collector.parseOutput("no-pipe-line\nanother-no-pipe"));
    }

    @Test
    @DisplayName("PS_SCRIPT: SIÊU NHẸ — không Add-Type (tránh compile C# + Defender), dùng dấu |, lọc cửa sổ thật")
    void psScriptIsLightweight() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertFalse(script.contains("Add-Type"),
                "KHÔNG dùng Add-Type — compile C# mỗi 10s gây chậm/timeout/Defender giám sát");
        assertTrue(script.contains("MainWindowHandle -ne 0"),
                "Chỉ tiến trình có cửa sổ thật");
        assertTrue(script.contains("MainWindowTitle -ne ''"),
                "Chỉ ứng dụng CÓ tiêu đề cửa sổ thực (loại tiến trình nền)");
        assertTrue(script.contains("Select-Object -First 5"), "Lấy tối đa 5 tiến trình");
        assertTrue(script.contains("'|'"), "Phân tách dấu | theo spec");
        assertFalse(script.contains("@\""), "KHÔNG dùng here-string dễ vỡ escape");
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
    @DisplayName("Windows collect() thật (PowerShell siêu nhẹ) → không ném, không chứa chuỗi lỗi")
    void windowsCollectNeverThrows() {
        WindowsActivityCollector realCollector = new WindowsActivityCollector(true);
        ActivityReportPayload payload = realCollector.collect();
        assertNotNull(payload, "collect() không bao giờ trả null");
        assertNotNull(payload.topProcesses(), "topProcesses không bao giờ null");
        assertTrue(!payload.activeProcessName().contains("not recognized"),
                "Không được gửi chuỗi lỗi PowerShell lên Server");
    }
}
