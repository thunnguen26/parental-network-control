package com.parental.client.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertEquals("", payload.activeUrlOrDomain(), "Không có URL → payload URL rỗng");
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
    @DisplayName("parseOutput: title chứa nhiều dấu | KHÔNG giống URL → title nguyên vẹn, URL rỗng")
    void titleWithPipesKeepsRest() {
        ActivityReportPayload payload = collector.parseOutput("chrome|A|B|C");
        assertEquals("A|B|C", payload.activeWindowTitle(),
                "Phần sau dấu | đầu tiên là title nguyên vẹn (A|B|C không giống URL)");
        assertEquals("", payload.activeUrlOrDomain(),
                "Trường cuối không giống URL → KHÔNG được nhầm thành domain");
    }

    @Test
    @DisplayName("parseOutput: title có | VÀ trường cuối là domain thật → tách đúng 2 phần")
    void titleWithPipesAndRealDomain() {
        ActivityReportPayload payload = collector.parseOutput(
                "chrome|Bài Hóa | VietJack - Chrome|vietjack.com");

        assertEquals("Bài Hóa | VietJack - Chrome", payload.activeWindowTitle(),
                "Title chứa | giữ nguyên vẹn");
        assertEquals("vietjack.com", payload.activeUrlOrDomain(),
                "Trường cuối giống domain → được nhận là URL");
    }

    @Test
    @DisplayName("parseOutput format 3 TRƯỜNG (Mở rộng 10): process|title|url → payload có domain")
    void parsesExtendedThreeFieldFormat() {
        String output = "chrome|Nhập câu lệnh cho Gemini - Google Chrome|gemini.google.com"
                + "\nnotepad|Ghi chú";

        ActivityReportPayload payload = collector.parseOutput(output);

        assertEquals("chrome.exe", payload.activeProcessName());
        assertEquals("Nhập câu lệnh cho Gemini - Google Chrome", payload.activeWindowTitle());
        assertEquals("gemini.google.com", payload.activeUrlOrDomain(),
                "URL bóc tách từ address bar phải vào payload — 1-click chặn được");
    }

    @Test
    @DisplayName("parseOutput: URL rỗng (trường 3 trống) → activeUrlOrDomain rỗng, title nguyên vẹn")
    void emptyUrlFieldIsSafe() {
        ActivityReportPayload payload = collector.parseOutput("browser|Trang chủ - Cốc Cốc|");

        assertEquals("", payload.activeUrlOrDomain(), "URL trống → chuỗi rỗng");
        assertEquals("Trang chủ - Cốc Cốc", payload.activeWindowTitle(),
                "Title giữ nguyên khi trường URL trống");
    }

    @Test
    @DisplayName("parseOutput: format 2 trường (fallback không phải trình duyệt) → URL rỗng")
    void twoFieldFormatStillWorks() {
        ActivityReportPayload payload = collector.parseOutput("somegame|Elden Ring");

        assertEquals("somegame.exe", payload.activeProcessName());
        assertEquals("", payload.activeUrlOrDomain(),
                "Game/app không có URL → payload URL rỗng (tương thích ngược)");
    }

    @Test
    @DisplayName("URL đầy đủ (https + path) giữ nguyên trong payload — server bóc domain trước khi chặn")
    void fullUrlKeptInPayload() {
        ActivityReportPayload payload = collector.parseOutput(
                "chrome|YouTube - Google Chrome|https://www.youtube.com/watch?v=abc");

        assertEquals("https://www.youtube.com/watch?v=abc", payload.activeUrlOrDomain(),
                "URL nguyên bản giữ trong payload (payload là data thô)");
    }

    @Test
    @DisplayName("isBrowserProcess: chrome/msedge/browser(Cốc Cốc)/firefox/brave/opera/vivaldi/arc")
    void browserProcessDetection() {
        assertTrue(WindowsActivityCollector.isBrowserProcess("chrome"));
        assertTrue(WindowsActivityCollector.isBrowserProcess("MSedge"));
        assertTrue(WindowsActivityCollector.isBrowserProcess("browser"), "Cốc Cốc = browser.exe");
        assertTrue(WindowsActivityCollector.isBrowserProcess("firefox"));
        assertTrue(WindowsActivityCollector.isBrowserProcess("vivaldi"));
        assertFalse(WindowsActivityCollector.isBrowserProcess("somegame"));
        assertFalse(WindowsActivityCollector.isBrowserProcess(null));
        assertFalse(WindowsActivityCollector.isBrowserProcess(""));
    }

    @Test
    @DisplayName("PS_SCRIPT: tìm Edit control + ValuePattern đọc URL khi là trình duyệt")
    void psScriptReadsAddressBarViaUia() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertTrue(script.contains("ControlType]::Edit"),
                "Tìm Edit control = thanh địa chỉ");
        assertTrue(script.contains("ValuePattern"),
                "Đọc URL qua ValuePattern.Value");
        assertTrue(script.contains("FindFirst"), "Duyệt tìm control trong cây UIA");
        assertTrue(script.contains("$isBrowser"), "Chỉ đục address bar khi là trình duyệt");
        assertTrue(script.contains("$pn + '|' + $name + '|' + $url"),
                "Xuất 3 trường khi có URL");
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
    @DisplayName("PS_SCRIPT: UIA FocusedElement — foreground/tab đang tương tác thật, KHÔNG theo thứ tự quét")
    void psScriptUsesUiaFocusedElement() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertTrue(script.contains("AutomationElement]::FocusedElement"),
                "Bắt buộc UIA FocusedElement — Select -First 1 chỉ lấy tiến trình đầu theo thứ tự quét");
        assertTrue(script.contains("Add-Type -AssemblyName UIAutomationClient"),
                "Chỉ NẠP assembly UIA có sẵn (không biên dịch C#)");
        assertTrue(script.contains("TreeWalker"),
                "Đi ngược cây cha khi tên phần tử focus rỗng");
        assertTrue(script.contains("exit 0"),
                "UIA thành công → xuất đúng 1 dòng rồi exit (không chạy fallback)");
        assertTrue(script.contains("MainWindowHandle -ne 0")
                && script.contains("MainWindowTitle -ne ''"),
                "Fallback Get-Process: chỉ tiến trình có cửa sổ + tiêu đề thật");
        assertTrue(script.contains("Select-Object -First 1"),
                "Fallback Get-Process First-1 theo spec");
        assertTrue(script.contains("'|'"), "Phân tách dấu | theo spec");
    }

    @Test
    @DisplayName("PS_SCRIPT: KHÔNG biên dịch C# / csc.exe — chỉ Add-Type -AssemblyName")
    void psScriptHasNoCSharpCompilation() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertFalse(script.contains("-MemberDefinition"), "KHÔNG dùng Add-Type -MemberDefinition (compile)");
        assertFalse(script.contains("DllImport"), "KHÔNG có P/Invoke DllImport C#");
        assertFalse(script.contains("user32"), "KHÔNG đụng user32.dll qua C# inline");
        assertFalse(script.contains("@\"") && script.contains("\"@"),
                "KHÔNG dùng here-string C# multi-line");
        assertFalse(script.toLowerCase().contains("namespace"), "KHÔNG khai báo namespace C#");
    }

    @Test
    @DisplayName("PS_SCRIPT: UTF-8 tường minh — OutputEncoding đặt đầu script, đọc bên Java bằng UTF_8")
    void psScriptAndReaderUseUtf8() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        assertTrue(script.startsWith("try { [Console]::OutputEncoding = "
                + "[System.Text.Encoding]::UTF8 }"),
                "OutputEncoding UTF8 phải là lệnh ĐẦU TIÊN của script");
        assertTrue(script.contains("exit 0"), "(tiền đề) UIA thành công exit sớm sau khi xuất");
    }

    @Test
    @DisplayName("readAllUtf8: tiêu đề tiếng Việt có dấu giữ nguyên 100% — hết lỗi font '?'")
    void utf8VietnameseTitleSurvivesRoundTrip() throws Exception {
        byte[] utf8Bytes = ("browser|Đăng nhập vào trang | VKU's Elearning - Cốc Cốc\n"
                + "chrome|Giải bài tập Hóa 12 — ếệảơườ đặt đúng dấu").getBytes(StandardCharsets.UTF_8);

        String decoded = WindowsActivityCollector.readAllUtf8(
                new java.io.ByteArrayInputStream(utf8Bytes), 16_000);
        ActivityReportPayload payload = collector.parseOutput(decoded);

        assertEquals("browser.exe", payload.activeProcessName());
        assertEquals("Đăng nhập vào trang | VKU's Elearning - Cốc Cốc",
                payload.activeWindowTitle(),
                "Tiêu đề tiếng Việt có dấu phải giữ nguyên — không '?' hay ký tự rác");
        assertTrue(payload.topProcesses().contains("chrome"));
    }

    @Test
    @DisplayName("readAllUtf8: byte lỗi/đơn giản ASCII vẫn đọc được, không ném")
    void readAllUtf8HandlesPlainBytes() throws Exception {
        assertEquals("chrome|Plain Title\n", WindowsActivityCollector.readAllUtf8(
                new java.io.ByteArrayInputStream("chrome|Plain Title\n".getBytes(StandardCharsets.UTF_8)),
                16_000));
        assertEquals("", WindowsActivityCollector.readAllUtf8(
                new java.io.ByteArrayInputStream(new byte[0]), 16_000), "Stream rỗng → chuỗi rỗng");
    }

    @Test
    @DisplayName("PS_SCRIPT: danh sách trình duyệt CHỈ để đục address bar — detection vẫn tự động")
    void browserListOnlyForAddressBarExtraction() {
        String script = WindowsActivityCollector.PS_SCRIPT;

        // Mở rộng 10: danh sách browser dùng để quyết định CÓ tìm address bar,
        // KHÔNG dùng để chọn active (active luôn là FocusedElement UIA)
        assertTrue(script.contains("$browsers ="),
                "Danh sách browser chỉ phục vụ đục URL thanh địa chỉ");
        assertTrue(script.contains("$isBrowser"),
                "Điều kiện isBrowser chỉ gated việc tìm Edit control");
        assertTrue(script.contains("FocusedElement"),
                "Detection foreground vẫn qua UIA FocusedElement — KHÔNG phụ thuộc danh sách");
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
