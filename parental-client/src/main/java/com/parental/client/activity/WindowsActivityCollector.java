package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên Windows KHÔNG cần thư viện ngoài:
 * gọi PowerShell qua {@link ProcessBuilder} — thu <b>cửa sổ Foreground thật</b>
 * (Win32 P/Invoke {@code GetForegroundWindow}) + danh sách tiến trình có cửa
 * sổ giao diện.
 *
 * <p><b>Cửa sổ Foreground thật (không còn "—")</b>: script khai báo WIN32 API
 * bằng {@code Add-Type -MemberDefinition} (P/Invoke trực tiếp trong PowerShell
 * — không dùng chuỗi C# multi-line vốn dễ vỡ cú pháp escape qua ProcessBuilder,
 * nguyên nhân hotfix trước): {@code GetForegroundWindow} → {@code GetWindowText}
 * (tiêu đề) + {@code GetWindowThreadProcessId} → {@code Process.GetProcessById}
 * (tên tiến trình). Xuất dòng ACTIVE dạng {@code process<TAB>title}.</p>
 *
 * <p><b>Bộ lọc tiến trình (theo yêu cầu)</b>: danh sách top chỉ ghi nhận ứng
 * dụng có tiêu đề cửa sổ THỰC ({@code MainWindowTitle -ne ''}) — tiến trình
 * nền hệ thống (svchost, services, csrss...) không có cửa sổ tương tác bị loại
 * sẵn tại script, không bao giờ xuất hiện trong payload.</p>
 *
 * <p><b>Hotfix giữ nguyên (không tái diễn)</b>: stdout/stderr tách RIÊNG
 * (không redirectErrorStream); {@code exitCode != 0} HOẶC stderr có nội dung →
 * fallback payload rỗng — chuỗi lỗi PowerShell TUYỆT ĐỐI không thành payload.</p>
 *
 * <p><b>Defensive (AGENTS.md)</b>: non-Windows → payload rỗng ngay; timeout 3s
 * + {@code destroyForcibly}; mọi lỗi (IOException/InterruptedException/
 * RuntimeException) nuốt → payload rỗng — scheduler của engine không bao giờ
 * chết, không bao giờ gửi rác lên Server.</p>
 */
public class WindowsActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(WindowsActivityCollector.class);

    /** Timeout cho mỗi lần gọi PowerShell (giây). */
    static final int POWERSHELL_TIMEOUT_SECONDS = 3;

    /**
     * Script PowerShell thu thập hoạt động — 2 phần:
     * <ol>
     *   <li><b>Foreground window thật</b> qua Win32 P/Invoke (Add-Type
     *       -MemberDefinition, single-line syntax-safe qua ProcessBuilder — KHÔNG
     *       dùng chuỗi C# multi-line với @"..."@ dễ vỡ escape): GetForegroundWindow →
     *       GetWindowText (tiêu đề) + GetWindowThreadProcessId → tên tiến trình;
     *       xuất {@code process<TAB>title}. Lấy foreground không phân biệt PID
     *       — cửa sổ đang focus luôn là tương tác người dùng thực.</li>
     *   <li><b>Top tiến trình có cửa sổ</b>: {@code MainWindowHandle -ne 0} +
     *       {@code MainWindowTitle -ne ''} (bộ lọc loại tiến trình nền) — sắp
     *       theo CPU giảm dần, tối đa 8, dòng {@code ROW<TAB>process<TAB>title}.</li>
     * </ol>
     * Phân tách TAB (không gặp lỗi escape dấu backtick của phiên bản cũ);
     * title có thể chứa nhiều TAB — parse split("\t", 2) giữ nguyên phần sau.
     */
    static final String PS_SCRIPT =
            "Add-Type -MemberDefinition '\n"
            + "[DllImport(\"user32.dll\")] public static extern IntPtr GetForegroundWindow();\n"
            + "[DllImport(\"user32.dll\")] public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder text, int count);\n"
            + "[DllImport(\"user32.dll\")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);\n"
            + "' -Name WinApi -Namespace Parental | Out-Null\n"
            + "$h = [Parental.WinApi]::GetForegroundWindow()\n"
            + "if ($h -ne [IntPtr]::Zero) {\n"
            + "  $sb = New-Object System.Text.StringBuilder 256\n"
            + "  if ([Parental.WinApi]::GetWindowText($h, $sb, 256) -gt 0) {\n"
            + "    $procId = 0\n"
            + "    [void][Parental.WinApi]::GetWindowThreadProcessId($h, [ref]$procId)\n"
            + "    try { $p = Get-Process -Id $procId -ErrorAction Stop } catch { $p = $null }\n"
            + "    if ($p) { Write-Output ($p.ProcessName + \"`t\" + $sb.ToString()) }\n"
            + "  }\n"
            + "}\n"
            + "$p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 "
            + "-and $_.MainWindowTitle -ne '' } | Sort-Object CPU -Descending | Select-Object -First 8; "
            + "if ($p) { $p | ForEach-Object { Write-Output ($_.ProcessName + \"`t\" + $_.MainWindowTitle) } }";

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
            return os.toLowerCase().contains("win");
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

            String stdout = readStream(process);
            String stderr = readErrorStream(process);
            boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("PowerShell activity probe timed out — returning empty payload");
                return emptyPayload();
            }
            int exitCode = process.exitValue();
            // LỖI PowerShell → KHÔNG BAO GIỜ parse stdout lỗi làm payload;
            // fallback rỗng thay vì gửi chuỗi lỗi lên Server (hotfix giữ nguyên).
            if (exitCode != 0 || (stderr != null && !stderr.isBlank())) {
                log.warn("PowerShell activity probe failed (exitCode={}, stderr={}) "
                        + "— returning empty payload, error is NEVER sent to server",
                        exitCode, summarize(stderr));
                return emptyPayload();
            }
            return parseOutput(stdout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyPayload();
        } catch (IOException | RuntimeException e) {
            log.debug("Activity collection failed ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return emptyPayload();
        }
    }

    /** Đọc stdout (đọc TRƯỚC waitFor — tránh deadlock pipe đầy). */
    private static String readStream(Process process) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, read);
                if (sb.length() > 16_000) {
                    break; // đủ dữ liệu — tránh pipe bị đầy khi chờ process
                }
            }
        }
        return sb.toString();
    }

    /** Đọc stderr RIÊNG (không trộn với stdout — hotfix sai biệt). */
    private static String readErrorStream(Process process) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, read);
                if (sb.length() > 4_000) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Rút gọn stderr cho log (tránh phình log file). */
    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    /**
     * Parse đầu ra PowerShell: các dòng {@code process<TAB>title}. Dòng
     * <b>đầu tiên là Foreground Window thật</b> (Win32 API — đang focus);
     * nếu script lỗi phần Foreground (return riêng) thì dòng đầu tiên trong
     * danh sách ROW vẫn là fallback hợp lệ (CPU cao nhất).
     */
    ActivityReportPayload parseOutput(String output) {
        String activeProcess = UNKNOWN;
        String activeTitle = UNKNOWN;
        Set<String> topProcesses = new LinkedHashSet<>();
        List<String> orderedTitles = new ArrayList<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line == null || line.isBlank() || !line.contains("\t")) {
                    continue; // dòng lỗi/nội dung lạ — bỏ qua, KHÔNG đưa vào payload
                }
                String[] parts = line.split("\t", 2);
                String processName = parts[0].trim();
                String title = parts.length > 1 ? parts[1].trim() : "";
                // Nhận diện nền: chỉ nhận tên tiến trình hợp lệ (chữ/số/dấu -_),
                // mọi dòng khác (lỗi PowerShell lọt qua) bị loại.
                if (processName.isEmpty() || !processName.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                // Sentinel Foreground rỗng (màn hình khóa / không có window text):
                // dòng 'none<TAB>' phải bị bỏ — hoạt động luôn kèm tiêu đề thực.
                if (title.isEmpty()) {
                    continue;
                }
                if (topProcesses.add(processName)) {
                    orderedTitles.add(title);
                    if (topProcesses.size() >= ActivityReportPayload.MAX_TOP_PROCESSES) {
                        break;
                    }
                }
            }
        }
        // Không có window nào (màn hình khóa/phiên trống) → trả rỗng chuẩn.
        if (topProcesses.isEmpty()) {
            return emptyPayload();
        }
        // Foreground thật = dòng đầu tiên (Win32 API); fallback = CPU cao nhất.
        activeProcess = topProcesses.iterator().next() + ".exe";
        activeTitle = orderedTitles.get(0);
        return new ActivityReportPayload(activeTitle, activeProcess,
                List.copyOf(topProcesses), System.currentTimeMillis());
    }

    /** Payload rỗng chuẩn (không null — contract của {@link ActivityCollector}). */
    static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }
}
