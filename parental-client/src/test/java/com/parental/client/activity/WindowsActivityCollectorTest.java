package com.parental.client.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 */
class WindowsActivityCollectorTest {

    private final WindowsActivityCollector collector = new WindowsActivityCollector(false);

    @Test
    @DisplayName("parseOutput: format 'process<TAB>title' — dòng đầu = ACTIVE, các dòng sau = top")
    void parsesProcessTitleLines() {
        String output = "chrome\tGiải bài tập Hóa 12 | VietJack - Google Chrome\n"
                + "notepad\tBài tập Toán - Notepad\n"
                + "explorer\tDocuments";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(), "Dòng đầu (CPU cao nhất) = active");
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", payload.activeWindowTitle());
        assertEquals(java.util.List.of("chrome", "notepad", "explorer"), payload.topProcesses());
        assertTrue(payload.timestamp() > 0, "timestamp epoch millis phải > 0");
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
    @DisplayName("parseOutput: title chứa TAB sâu (title có nhiều TAB) → giữ nguyên phần title")
    void titleWithTabsKeepsRest() {
        ActivityReportPayload payload = collector.parseOutput("chrome\tA\tB\tC");
        assertEquals("A\tB\tC", payload.activeWindowTitle(),
                "Phần sau TAB đầu tiên là title nguyên vẹn");
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
    @DisplayName("Windows collect() trên môi trường test (PowerShell thật có thể có) → không ném exception")
    void windowsCollectNeverThrows() {
        // Trên Windows thật: PowerShell chạy (hoặc timeout 3s) → vẫn phải trả
        // payload không null. Trên CI không Windows: không spawn gì.
        WindowsActivityCollector realCollector = new WindowsActivityCollector(true);
        ActivityReportPayload payload = realCollector.collect();
        assertNotNull(payload, "collect() không bao giờ trả null");
        assertNotNull(payload.topProcesses(), "topProcesses không bao giờ null");
        // Hotfix: payload KHÔNG BAO GIỜ chứa chuỗi lỗi PowerShell
        assertTrue(!payload.activeProcessName().contains("not recognized"),
                "Không được gửi chuỗi lỗi PowerShell lên Server");
    }
}
