package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên Windows KHÔNG cần thư viện ngoài:
 * gọi PowerShell qua {@link ProcessBuilder} — thu tiêu đề + tiến trình của
 * ứng dụng người dùng đang tương tác.
 *
 * <p><b>Script SIÊU NHẸ (tối ưu 2026-09-27)</b>: bỏ hoàn toàn Add-Type compile
 * C# (mỗi lần probe 10s phải compile lại → chậm, timeout, bị Windows Defender
 * giám sát) — chỉ dùng {@code Get-Process | Where MainWindowHandle -ne 0 &&
 * MainWindowTitle -ne '' | Sort CPU -Desc | Select -First 5}, xuất mỗi dòng
 * {@code process|title} (dấu | theo spec yêu cầu).</p>
 *
 * <p><b>Bộ lọc tiến trình người dùng (User App Filter)</b> áp trên phía Java
 * (dễ test headless): bỏ tiến trình hệ thống Windows (explorer, dwm,
 * svchost, powershell...), bỏ tiêu đề rác (≤ 2 ký tự, tên cửa sổ OS dạng
 * "Windows..."), ƯU TIÊN tiến trình đầu tiên thuộc nhóm người dùng tương tác
 * (trình duyệt/game/office/tools) làm cửa sổ active; không có → fallback phần
 * tử đầu còn lại.</p>
 *
 * <p><b>Hotfix giữ nguyên</b>: stdout/stderr tách RIÊNG (không
 * redirectErrorStream); {@code exitCode != 0} HOẶC stderr có nội dung →
 * fallback payload rỗng — chuỗi lỗi PowerShell TUYỆT ĐỐI không thành payload.</p>
 *
 * <p><b>Defensive (AGENTS.md)</b>: non-Windows → payload rỗng ngay; timeout 3s
 * + {@code destroyForcibly}; mọi lỗi nuốt → payload rỗng — scheduler không
 * bao giờ chết, không bao giờ gửi rác lên Server.</p>
 */
public class WindowsActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(WindowsActivityCollector.class);

    /** Timeout cho mỗi lần gọi PowerShell (giây). */
    static final int POWERSHELL_TIMEOUT_SECONDS = 3;

    /**
     * Script PowerShell SIÊU NHẸ (không Add-Type — không compile C# mỗi probe,
     * không trigger Windows Defender): tiến trình có cửa sổ giao diện thật,
     * sắp theo CPU giảm dần, lấy 5, xuất {@code process|title}.
     */
    static final String PS_SCRIPT =
            "$p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 "
            + "-and $_.MainWindowTitle -ne '' } | Sort-Object CPU -Descending | Select-Object -First 5; "
            + "if ($p) { foreach ($item in $p) { "
            + "Write-Output ($item.ProcessName + '|' + $item.MainWindowTitle) } }";

    /**
     * Tiến trình NỀN hệ thống Windows bị loại khỏi payload (không phải tương
     * tác của người dùng — theo danh sách yêu cầu).
     */
    static final Set<String> SYSTEM_PROCESSES = Set.of(
            "explorer", "shellexperiencehost", "searchhost", "startmenuexperiencehost",
            "dwm", "svchost", "taskmgr", "cmd", "conhost", "powershell");

    /**
     * Tiền tố tên tiến trình NHÓM NGƯỜI DÙNG TƯƠNG TÁC — được ƯU TIÊN chọn làm
     * cửa sổ active (browser/game/office/tools phổ biến; so khớp prefix
     * không phân biệt hoa/thường — vd coccoc-*, msedgewebview2 khớp luôn).
     */
    static final List<String> USER_APP_HINTS = List.of(
            "chrome", "msedge", "coccoc", "firefox", "brave", "opera", "vivaldi",
            "zalo", "discord", "steam", "epicgames", "garena",
            "winword", "excel", "powerpnt", "onenote", "notepad", "wordpad",
            "code", "devenv", "idea", "eclipse", "postman",
            "spotify", "vlc", "potplayer", "wmplayer", "telegram");

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

            String stdout = readStream(process);
            String stderr = readErrorStream(process);
            boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("PowerShell activity probe timed out — returning empty payload");
                return emptyPayload();
            }
            int exitCode = process.exitValue();
            // LỖI PowerShell → KHÔNG BAO GIỜ parse stdout lỗi làm payload (hotfix).
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

    // ------------------------------------------------------------------
    // User App Filter (thuần Java — unit test headless được)
    // ------------------------------------------------------------------

    /** 1 ứng dụng người dùng thu được từ stdout PowerShell. */
    record UserApp(String processName, String title) {
    }

    /** Có phải tiến trình nền hệ thống (theo {@link #SYSTEM_PROCESSES})? */
    static boolean isSystemProcess(String processName) {
        return SYSTEM_PROCESSES.contains(processName.toLowerCase(Locale.ROOT));
    }

    /** Tiến trình thuộc nhóm người dùng tương tác (prefix {@link #USER_APP_HINTS})? */
    static boolean isUserApp(String processName) {
        String lower = processName.toLowerCase(Locale.ROOT);
        for (String hint : USER_APP_HINTS) {
            if (lower.startsWith(hint)) {
                return true;
            }
        }
        return false;
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
     * Parse đầu ra PowerShell (dòng {@code process|title}) + áp User App
     * Filter: bỏ tiến trình hệ thống, bỏ tiêu đề rác; ACTIVE = tiến trình
     * NGƯỜI DÙNG đầu tiên, fallback phần tử hợp lệ đầu tiên.
     */
    ActivityReportPayload parseOutput(String output) {
        List<UserApp> validApps = new java.util.ArrayList<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (line == null || line.isBlank() || !line.contains("|")) {
                    continue; // dòng lỗi/nội dung lạ — bỏ qua, KHÔNG đưa vào payload
                }
                String[] parts = line.split("\\|", 2);
                String processName = parts[0].trim();
                String title = parts.length > 1 ? parts[1].trim() : "";
                // Chỉ nhận tên tiến trình hợp lệ (lỗi PowerShell lọt qua bị loại)
                if (processName.isEmpty() || !processName.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                // User App Filter: bỏ tiến trình hệ thống + tiêu đề rác/rỗng
                if (isSystemProcess(processName) || !isMeaningfulTitle(title)) {
                    continue;
                }
                validApps.add(new UserApp(processName, title));
            }
        }
        if (validApps.isEmpty()) {
            // Chỉ có app hệ thống/không gì (màn hình khóa) → payload rỗng chuẩn
            return emptyPayload();
        }
        // ACTIVE: ưu tiên nhóm người dùng tương tác; fallback phần tử đầu hợp lệ
        UserApp active = validApps.stream()
                .filter(app -> isUserApp(app.processName()))
                .findFirst()
                .orElse(validApps.get(0));

        List<String> topProcesses = validApps.stream()
                .map(UserApp::processName)
                .distinct()
                .limit(ActivityReportPayload.MAX_TOP_PROCESSES)
                .toList();
        return new ActivityReportPayload(active.title(), active.processName() + ".exe",
                topProcesses, System.currentTimeMillis());
    }

    /** Payload rỗng chuẩn (không null — contract của {@link ActivityCollector}). */
    static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }
}
