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
 * defensive với output rỗng/hỏng, giới hạn 5 tiến trình, non-Windows → payload
 * rỗng không ném exception.
 */
class WindowsActivityCollectorTest {

    private final WindowsActivityCollector collector = new WindowsActivityCollector(false);

    @Test
    @DisplayName("parseOutput: dòng ACTIVE + ROW → title/process/topProcesses đúng")
    void parsesActiveAndRows() {
        String output = """
                ACTIVE\tchrome\tGiải bài tập Hóa 12 | VietJack - Google Chrome
                ROW\tchrome\tGiải bài tập Hóa 12 | VietJack - Google Chrome
                ROW\tnotepad\tBài tập Toán - Notepad
                ROW\texplorer\tDocuments
                """;

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName());
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", payload.activeWindowTitle());
        assertEquals(3, payload.topProcesses().size());
        assertEquals(java.util.List.of("chrome", "notepad", "explorer"), payload.topProcesses());
        assertTrue(payload.timestamp() > 0, "timestamp epoch millis phải > 0");
    }

    @Test
    @DisplayName("parseOutput: không dòng ACTIVE → fallback tiến trình ROW đầu tiên")
    void fallsBackToFirstRowWhenNoActive() {
        String output = """
                ROW\tchrome\tChrome Window
                ROW\tnotepad\tNotepad Window
                """;

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName(), "Fallback = ROW đầu tiên");
        assertEquals(2, payload.topProcesses().size());
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
        assertNotNull(collector.parseOutput("ACTIVE\tincomplete"));
    }

    @Test
    @DisplayName("parseOutput: tối đa 5 tiến trình (MAX_TOP_PROCESSES), khử trùng lặp")
    void capsTopProcessesAtFive() {
        StringBuilder output = new StringBuilder("ACTIVE\tapp1\tTitle 1\n");
        for (int i = 1; i <= 10; i++) {
            output.append("ROW\tapp").append(i).append("\tTitle ").append(i).append('\n');
        }
        output.append("ROW\tapp1\tDuplicate\n"); // trùng — bị bỏ

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
    }
}
