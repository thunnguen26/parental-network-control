package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên Windows KHÔNG cần thư viện ngoài:
 * gọi PowerShell qua {@link ProcessBuilder} — bắt <b>CHÍNH XÁC cửa sổ/tab
 * người dùng đang tương tác</b> qua Windows UI Automation
 * ({@code AutomationElement::FocusedElement}), có fallback {@code Get-Process}
 * khi UIA không xác định được.
 *
 * <p><b>UIA FocusedElement (cải tiến 2026-09-28)</b>: phiên bản trước dùng
 * {@code Get-Process | Select -First 1} — chỉ lấy tiến trình đầu tiên theo
 * thứ tự quét, KHÔNG phải cửa sổ đang focus (người dùng chuyển sang Facebook
 * nhưng hệ thống vẫn ghi YouTube cũ). UIA trả về đúng phần tử đang nhận bàn
 * phím/chuột tại thời điểm probe: mở Facebook nhận Facebook, mở Zalo nhận
 * Zalo, mở Word/Game nhận ngay — KHÔNG cần whitelist tên app. Nếu tên phần tử
 * focus rỗng (control con không có title), đi ngược cây cha tối đa 6 cấp đến
 * khi có tên. UIA chỉ {@code Add-Type -AssemblyName} (NẠP assembly có sẵn của
 * .NET) — KHÔNG biên dịch C#, KHÔNG csc.exe.</p>
 *
 * <p><b>Encoding UTF-8</b>: script đặt
 * {@code [Console]::OutputEncoding = [System.Text.Encoding]::UTF8} ngay đầu,
 * phía Java đọc stdout bằng {@code InputStreamReader(..., UTF_8)} qua
 * {@link #readAllUtf8} — tiêu đề tiếng Việt có dấu (ế/ệ/ơ...) giữ nguyên
 * 100%, hết lỗi font '?/'.</p>
 *
 * <p><b>Fallback an toàn</b>: UIA lỗi/app đặc thù → pipeline
 * {@code Get-Process} First-1 (tiến trình có cửa sổ, loại nền hệ thống);
 * cả hai thất bại → không xuất dòng nào → payload rỗng. Chuỗi lỗi PowerShell
 * TUYỆT ĐỐI không thành payload (stdout/stderr tách riêng, exitCode≠0 hoặc
 * stderr có nội dung → trả rỗng).</p>
 */
public class WindowsActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(WindowsActivityCollector.class);

    /** Timeout cho mỗi lần gọi PowerShell (giây) — lỗi/treo → payload rỗng. */
    static final int POWERSHELL_TIMEOUT_SECONDS = 2;

    /**
     * Tên tiến trình TRÌNH DUYỆT (lowercase) — khi cửa sổ active thuộc nhóm
     * này, script tìm Edit control thanh địa chỉ để bóc tách URL/domain.
     * So khớp prefix — không dùng để ưu tiên/nhận diện app (detection vẫn
     * tự động qua UIA), chỉ quyết định CÓ đục address bar hay không.
     */
    static final List<String> BROWSER_PROCESS_HINTS = List.of(
            "chrome", "msedge", "browser", "firefox", "brave", "opera", "vivaldi", "arc");

    /** Có phải tiến trình trình duyệt (so khớp prefix {@link #BROWSER_PROCESS_HINTS})? */
    static boolean isBrowserProcess(String processName) {
        if (processName == null || processName.isBlank()) {
            return false;
        }
        String lower = processName.toLowerCase(Locale.ROOT);
        for (String hint : BROWSER_PROCESS_HINTS) {
            if (lower.startsWith(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Script PowerShell — 4 phần:
     * <ol>
     *   <li><b>UTF-8</b>: {@code [Console]::OutputEncoding = UTF8} — stdout
     *       xuất tiếng Việt có dấu đúng chuẩn (Java đọc bằng UTF-8).</li>
     *   <li><b>UIA Foreground thật</b>: {@code FocusedElement} → tên + PID
     *       (tên rỗng → đi ngược cây cha ≤ 6 cấp).</li>
     *   <li><b>Address Bar (Mở rộng 10)</b>: nếu tiến trình là trình duyệt,
     *       tìm Edit control của thanh địa chỉ (đi ngược lên window gốc rồi
     *       {@code FindFirst(TreeScope.Descendants, ControlType.Edit)}) — đọc
     *       URL qua {@code ValuePattern.Value}. Xuất 3 trường
     *       {@code process|title|urlOrDomain} khi có URL; 2 trường
     *       {@code process|title} khi không.</li>
     *   <li><b>Fallback Get-Process</b>: UIA lỗi/không xác định được → tiến
     *       trình đầu tiên có cửa sổ thật (loại nền hệ thống), xuất
     *       {@code process|title}.</li>
     * </ol>
     * Không lấy được gì (màn hình khóa...) → không xuất dòng nào → payload rỗng.
     */
    static final String PS_SCRIPT =
            "try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }\n"
            + "try {\n"
            + "  Add-Type -AssemblyName UIAutomationClient\n"
            + "  $f = [System.Windows.Automation.AutomationElement]::FocusedElement\n"
            + "  if ($f) {\n"
            + "    $name = $f.Current.Name\n"
            + "    $procId = $f.Current.ProcessId\n"
            + "    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker\n"
            + "    $node = $f\n"
            + "    $depth = 0\n"
            + "    while ([string]::IsNullOrEmpty($name) -and $depth -lt 6) {\n"
            + "      $node = $walker.GetParent($node)\n"
            + "      if ($null -eq $node) { break }\n"
            + "      $name = $node.Current.Name\n"
            + "      $procId = $node.Current.ProcessId\n"
            + "      $depth++\n"
            + "    }\n"
            + "    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue\n"
            + "    if ($p -and -not [string]::IsNullOrEmpty($name)) {\n"
            + "      $pn = $p.ProcessName\n"
            + "      $browsers = 'chrome','msedge','browser','firefox','brave','opera','vivaldi','arc'\n"
            + "      $isBrowser = $false\n"
            + "      foreach ($b in $browsers) { if ($pn.ToLower().StartsWith($b)) { $isBrowser = $true; break } }\n"
            + "      $url = ''\n"
            + "      if ($isBrowser) {\n"
            + "        try {\n"
            + "          $root = $f\n"
            + "          $depth2 = 0\n"
            + "          while ($depth2 -lt 6) {\n"
            + "            $parent = $walker.GetParent($root)\n"
            + "            if ($null -eq $parent) { break }\n"
            + "            $root = $parent\n"
            + "            $depth2++\n"
            + "          }\n"
            + "          $cond = New-Object System.Windows.Automation.PropertyCondition(" 
            + "[System.Windows.Automation.AutomationElement]::ControlTypeProperty, " 
            + "[System.Windows.Automation.ControlType]::Edit)\n"
            + "          $edit = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)\n"
            + "          if ($edit) {\n"
            + "            $vp = $null\n"
            + "            if ($edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$vp)) {\n"
            + "              $url = $vp.Value\n"
            + "            }\n"
            + "          }\n"
            + "        } catch { }\n"
            + "      }\n"
            + "      if (-not [string]::IsNullOrEmpty($url)) {\n"
            + "        Write-Output ($pn + '|' + $name + '|' + $url)\n"
            + "      } else {\n"
            + "        Write-Output ($pn + '|' + $name)\n"
            + "      }\n"
            + "      exit 0\n"
            + "    }\n"
            + "  }\n"
            + "} catch { }\n"
            + "$w = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 "
            + "-and $_.MainWindowTitle -ne '' "
            + "-and $_.ProcessName -notmatch '^(explorer|dwm|taskhostw|conhost|svchost)$' } "
            + "| Select-Object -First 1\n"
            + "if ($w) { Write-Output ($w.ProcessName + '|' + $w.MainWindowTitle) }";

    /**
     * Tiến trình NỀN hệ thống Windows bị loại khỏi payload (không phải tương
     * tác của người dùng) — so khớp lớp 2 phía Java (fallback script đã lọc
     * trước một phần).
     */
    static final Set<String> SYSTEM_PROCESSES = Set.of(
            "explorer", "shellexperiencehost", "searchhost", "startmenuexperiencehost",
            "dwm", "svchost", "taskmgr", "cmd", "conhost", "powershell", "taskhostw",
            "applicationframehost", "textinputhost", "runtimebroker", "widgetservice");

    private final boolean windowsOs;

    public WindowsActivityCollector() {
        this(detectWindows());
    }

    /** Constructor test: cho phép giả lập hệ điều hành. */
    WindowsActivityCollector(boolean windowsOs) {
        this.windowsOs = windowsOs;
    }

    private static boolean detectWindows() {
        try {
            String os = System.getProperty("os.name", "");
            return os.toLowerCase(Locale.ROOT).contains("win");
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ActivityReportPayload collect() {
        if (!windowsOs) {
            // Headless/test/hệ khác — payload rỗng an toàn, không spawn process.
            return emptyPayload();
        }
        try {
            Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", PS_SCRIPT)
                    .start(); // KHÔNG redirectErrorStream — stdout/stderr tách riêng

            // ĐỌC SONG SONG 2 stream bằng thread daemon — nếu đọc TUẦN TỰ
            // (chờ EOF stdout trước rồi mới stderr) thì khi PowerShell chậm/
            // treo hoặc pipe stderr đầy, read() block VĨNH VIỄN và timeout
            // waitFor không bao giờ được chạm tới (nguyên nhân build bị treo).
            StringBuilder stdout = new StringBuilder(512);
            StringBuilder stderr = new StringBuilder(256);
            Thread stdoutReader = pump("ps-stdout", process.getInputStream(), stdout, 16_000);
            Thread stderrReader = pump("ps-stderr", process.getErrorStream(), stderr, 4_000);

            boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("PowerShell activity probe timed out — returning empty payload");
                return emptyPayload(); // thread reader là daemon — không giữ JVM
            }
            // Process đã exit → 2 stream đóng EOF ngay; join ngắn để lấy dữ liệu.
            stdoutReader.join(1_000);
            stderrReader.join(1_000);

            int exitCode = process.exitValue();
            // LỖI PowerShell → KHÔNG BAO GIỜ parse stdout lỗi làm payload (hotfix).
            String stderrText = stderr.toString();
            if (exitCode != 0 || !stderrText.isBlank()) {
                log.warn("PowerShell activity probe failed (exitCode={}, stderr={}) "
                        + "— returning empty payload, error is NEVER sent to server",
                        exitCode, summarize(stderrText));
                return emptyPayload();
            }
            return parseOutput(stdout.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyPayload();
        } catch (IOException | RuntimeException e) {
            log.debug("Activity collection failed ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return emptyPayload();
        }
    }

    /**
     * Thread daemon bơm 1 stream vào StringBuilder (đọc đến EOF, cap dung
     * lượng chống pipe đầy) — stdout/stderr mỗi cái một thread KHÔNG bao giờ
     * block lẫn nhau; thread chết cùng JVM (daemon) nên không treo test.
     */
    private static Thread pump(String name, java.io.InputStream stream,
                               StringBuilder sink, int maxLength) {
        Thread thread = new Thread(() -> {
            try {
                sink.append(readAllUtf8(stream, maxLength));
            } catch (IOException e) {
                // Stream đóng đột ngột (timeout/destroy) — chấp nhận, defensive.
            }
        }, "parental-client-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Đọc TOÀN BỘ stream với bảng mã <b>UTF-8 tường minh</b> — bắt buộc để
     * tiếng Việt có dấu (tiêu đề cửa sổ trình duyệt/tiện ích) không vỡ font
     * thành '?'. Phối hợp với {@code [Console]::OutputEncoding = UTF8} phía
     * script: byte xuất ra chuẩn UTF-8, decode UTF-8 → giữ nguyên 100%.
     *
     * @param stream    stream cần đọc (stdout/stderr của process)
     * @param maxLength cap ký tự tối đa (chống pipe đầy block vô hạn)
     * @return nội dung UTF-8 đã decode (rỗng nếu stream rỗng)
     */
    static String readAllUtf8(java.io.InputStream stream, int maxLength) throws IOException {
        StringBuilder sink = new StringBuilder(512);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sink.append(buffer, 0, read);
                if (sink.length() > maxLength) {
                    break; // đủ dữ liệu — chặn pipe đầy
                }
            }
        }
        return sink.toString();
    }

    /** Rút gọn stderr cho log (tránh phình log file). */
    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    // ------------------------------------------------------------------
    // Windowed App Filter (thuần Java — unit test headless được)
    // ------------------------------------------------------------------

    /** 1 ứng dụng người dùng thu được từ stdout PowerShell (url rỗng nếu không có). */
    record UserApp(String processName, String title, String url) {
    }

    /** Có phải tiến trình nền hệ thống (theo {@link #SYSTEM_PROCESSES})? */
    static boolean isSystemProcess(String processName) {
        return SYSTEM_PROCESSES.contains(processName.toLowerCase(Locale.ROOT));
    }

    /** Tiêu đề CÓ Ý NGHĨA: > 2 ký tự và không phải tên cửa sổ hệ điều hành. */
    static boolean isMeaningfulTitle(String title) {
        if (title == null || title.length() <= 2) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        // Tên cửa sổ OS/cảnh báo hệ điều hành — không phản ánh hoạt động học tập
        return !lower.startsWith("windows") && !lower.startsWith("settings")
                && !lower.startsWith("program manager") && !lower.startsWith("task view");
    }

    /**
     * Parse đầu ra PowerShell — dòng {@code process|title} hoặc
     * {@code process|title|urlOrDomain} (Mở rộng 10: trình duyệt có URL từ
     * thanh địa chỉ):
     * <ul>
     *   <li>Dòng ĐẦU TIÊN hợp lệ = <b>Foreground UIA</b> (cửa sổ/tab người
     *       dùng đang tương tác; UIA thành công script chỉ xuất ĐÚNG 1 dòng
     *       rồi exit — fallback First-1 khi UIA trượt); KHÔNG đoán theo CPU;</li>
     *   <li>Áp Windowed App Filter phía Java (phòng thủ 2 lớp): bỏ tiến trình
     *       hệ thống + tiêu đề rác/rỗng;</li>
     *   <li>URL/domain (trường 3) giữ nguyên — server bóc tách domain trước
     *       khi chặn; URL rỗng/không có trường 3 → chuỗi rỗng.</li>
     * </ul>
     */
    ActivityReportPayload parseOutput(String output) {
        List<UserApp> validApps = new ArrayList<>();
        UserApp active = null;

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line == null || line.isBlank() || !line.contains("|")) {
                    continue; // dòng lỗi/nội dung lạ — bỏ qua, KHÔNG đưa vào payload
                }
                // Trường 3 (URL) RỖNG ở cuối dòng (browser|Title|) → strip để
                // không nuốt dấu | vào title; script chỉ xuất 3 trường khi URL
                // KHÁC rỗng, dòng có trailing pipe rỗng là bất thường — an toàn.
                String normalizedLine = line.replaceAll("\\|+\\s*$", "").trim();
                if (!normalizedLine.contains("|")) {
                    continue;
                }
                String[] parts = normalizedLine.split("\\|", -1);
                String processName = parts[0].trim();
                // Trường CUỐI chỉ được coi là URL/domain nếu nó TRÔNG GIỐNG một
                // URL (chứa "://") hoặc domain — tránh nhầm title chứa dấu "|"
                // (vd "Bài Hóa | VietJack - Chrome") thành trường URL.
                String url = "";
                String title;
                if (parts.length >= 3) {
                    String candidate = parts[parts.length - 1].trim();
                    if (looksLikeUrlOrDomain(candidate)) {
                        url = candidate;
                        title = String.join("|",
                                java.util.Arrays.copyOfRange(parts, 1, parts.length - 1)).trim();
                    } else {
                        title = String.join("|",
                                java.util.Arrays.copyOfRange(parts, 1, parts.length)).trim();
                    }
                } else {
                    title = parts.length > 1 ? parts[1].trim() : "";
                }
                // Chỉ nhận tên tiến trình hợp lệ (lỗi PowerShell lọt qua bị loại)
                if (processName.isEmpty() || !processName.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                // Windowed App Filter: bỏ tiến trình hệ thống + tiêu đề rác/rỗng
                if (isSystemProcess(processName) || !isMeaningfulTitle(title)) {
                    continue;
                }
                UserApp app = new UserApp(processName, title, url);
                if (active == null) {
                    active = app; // dòng đầu hợp lệ = foreground
                }
                validApps.add(app);
            }
        }
        if (active == null) {
            // Chỉ có app hệ thống/không gì (màn hình khóa) → payload rỗng chuẩn
            return emptyPayload();
        }

        List<String> topProcesses = validApps.stream()
                .map(UserApp::processName)
                .distinct()
                .limit(ActivityReportPayload.MAX_TOP_PROCESSES)
                .toList();
        return new ActivityReportPayload(active.title(), active.processName() + ".exe",
                active.url(), topProcesses, System.currentTimeMillis());
    }

    /**
     * Chuỗi có TRÔNG GIỐNG một URL (chứa {@code ://}) hoặc domain
     * ({@code a.b.c} — không khoảng trắng, có TLD ≥ 2 chữ cái)?
     */
    static boolean looksLikeUrlOrDomain(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.contains(" ")) {
            return false;
        }
        if (candidate.contains("://")) {
            return true;
        }
        return candidate.matches("(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?:/.*)?");
    }

    /** Payload rỗng chuẩn (không null — contract của {@link ActivityCollector}). */
    static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }
}
