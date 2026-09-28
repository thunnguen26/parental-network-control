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
 * thật — headless an toàn), <b>Windowed App Filter</b> (bỏ tiến trình hệ
 * thống + tiêu đề rác; KHÔNG hardcode danh sách trình duyệt — mọi ứng dụng
 * người dùng tự động được nhận diện), defensive với output rỗng/hỏng/chuỗi
 * lỗi PowerShell, non-Windows → payload rỗng.
 */
class WindowsActivityCollectorTest {

    private final WindowsActivityCollector collector = new WindowsActivityCollector(false);

    @Test
    @DisplayName("parseOutput: format 'process|title' — dòng ĐẦU = ứng dụng đang tương tác (KHÔNG theo CPU)")
    void parsesPipeLines() {
        // browser.exe = Cốc Cốc — nhận diện TỰ ĐỘNG qua foreground/first-windowed,
        // không phụ thuộc danh sách tên trình duyệt
        String output = "browser|YouTube - Cốc Cốc\n"
                + "code|Visual Studio Code\n"
                + "notepad|Bài tập Toán - Notepad";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("browser.exe", payload.activeProcessName(),
                "Dòng đầu = ứng dụng đang tương tác — KHÔNG bị Code.exe CPU cao lấn át");
        assertEquals("YouTube - Cốc Cốc", payload.activeWindowTitle());
        assertEquals(java.util.List.of("browser", "code", "notepad"), payload.topProcesses());
        assertTrue(payload.timestamp() > 0, "timestamp epoch millis phải > 0");
    }

    @Test
    @DisplayName("Mọi ứng dụng bất kỳ (game, app lạ) đều được nhận diện tự động — không whitelist")
    void anyUserAppIsRecognized() {
        // Game/app không có trong bất kỳ danh sách nào — vẫn làm active vì đứng đầu
        String output = "somegame|Elden Ring\n"
                + "code|Visual Studio Code";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("somegame.exe", payload.activeProcessName(),
                "App lạ/game phải được nhận diện tự động — KHÔNG hardcode whitelist");
        assertEquals("Elden Ring", payload.activeWindowTitle());
    }

    @Test
    @DisplayName("Windowed App Filter: tiến trình hệ thống (explorer/dwm/powershell...) bị LOẠI khỏi payload")
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
    @DisplayName("Windowed App Filter: ApplicationFrameHost (UWP host) bị loại — probe thực tế trả app nền")
    void uwpHostProcessesAreFiltered() {
        String output = "ApplicationFrameHost|Cài đặt\n"
                + "browser|YouTube - Cốc Cốc";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("browser.exe", payload.activeProcessName(),
                "ApplicationFrameHost là host UWP nền — không phải tương tác của người dùng");
    }

    @Test
    @DisplayName("Windowed App Filter: tiêu đề rác (≤ 2 ký tự, tên cửa sổ OS) bị loại")
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
    @DisplayName("PS_SCRIPT: SIÊU NHẸ đúng spec — Get-Process | Where MainWindowHandle | Select -First 5")
    void psScriptMatchesSpec() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertTrue(script.contains("Get-Process"), "Dùng Get-Process cmdlet có sẵn");
        assertTrue(script.contains("MainWindowHandle -ne 0"),
                "Chỉ tiến trình có cửa sổ giao diện thật");
        assertTrue(script.contains("MainWindowTitle -ne ''"),
                "Tiêu đề không rỗng — bỏ cửa sổ nền desktop");
        assertTrue(script.contains("Select-Object -First 5"),
                "Chọn First 5 (dòng đầu = đang tương tác)");
        assertTrue(script.contains(
                        "-notmatch '^(explorer|dwm|taskhostw|conhost|svchost)$'"),
                "Loại tiến trình nền hệ thống theo spec");
        assertTrue(script.contains("'|'"), "Phân tách dấu | theo spec");
    }

    @Test
    @DisplayName("PS_SCRIPT: KHÔNG Add-Type / C# / csc.exe — không trigger Antivirus quét compiler")
    void psScriptHasNoAddTypeOrCSharp() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertFalse(script.contains("Add-Type"), "KHÔNG dùng Add-Type (cả -MemberDefinition)");
        assertFalse(script.contains("DllImport"), "KHÔNG có P/Invoke DllImport C#");
        assertFalse(script.contains("user32"), "KHÔNG đụng user32.dll qua C# inline");
        assertFalse(script.contains("@\"") && script.contains("\"@"),
                "KHÔNG dùng here-string C# multi-line");
        assertFalse(script.toLowerCase().contains("namespace"), "KHÔNG khai báo namespace C#");
    }

    @Test
    @DisplayName("PS_SCRIPT: KHÔNG hardcode danh sách trình duyệt — nhận diện tự động mọi app")
    void psScriptHasNoBrowserWhitelist() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        // Không nhắc tên trình duyệt/app cụ thể trong script
        for (String banned : new String[] {"chrome", "msedge", "coccoc", "firefox",
                "brave", "opera", "browser", "arc"}) {
            assertFalse(script.toLowerCase().contains(banned),
                    "Script không được hardcode tên app: " + banned);
        }
        // Java filter cũng không ưu tiên theo nhóm app (không USER_APP_HINTS)
        assertFalse(WindowsActivityCollector.SYSTEM_PROCESSES.contains("chrome"),
                "SYSTEM_PROCESSES chỉ chứa tiến trình NỀN, không chứa app người dùng");
    }

    @Test
    @DisplayName("Timeout PowerShell = 2s theo spec (lỗi → payload rỗng, không treo)")
    void timeoutIsTwoSeconds() {
        assertEquals(2, WindowsActivityCollector.POWERSHELL_TIMEOUT_SECONDS,
                "Spec: timeout 2s, nếu lỗi fallback trả về rỗng");
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
