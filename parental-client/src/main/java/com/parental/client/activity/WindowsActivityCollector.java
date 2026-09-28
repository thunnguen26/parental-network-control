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
 * gọi PowerShell siêu nhẹ qua {@link ProcessBuilder} — liệt kê các tiến trình
 * đang có cửa sổ giao diện, dòng ĐẦU được coi là cửa sổ người dùng đang
 * tương tác.
 *
 * <p><b>Script SIÊU NHẸ — KHÔNG Add-Type, KHÔNG biên dịch C#, KHÔNG csc.exe</b>
 * (quyết định 2026-09-27): `Add-Type -MemberDefinition` P/Invoke tuy chính xác
 * nhưng mỗi lần spawn đều biên dịch C# qua {@code csc.exe} — Antivirus hay
 * giám sát/quét csc.exe gây chậm hoặc timeout. Script chỉ là MỘT pipeline
 * {@code Get-Process | Where-Object {...} | Select-Object -First N} — toàn bộ
 * cmdlet có sẵn, chạy ~0.3s.</p>
 *
 * <p><b>KHÔNG hardcode danh sách trình duyệt</b>: hệ thống tự nhận diện MỌI
 * ứng dụng người dùng đang có tương tác (Cốc Cốc browser.exe, Chrome, Edge,
 * Arc, game, VS Code...) — lọc loại chỉ dựa trên danh sách tiến trình NỀN hệ
 * thống (explorer/dwm/svchost...) và tiêu đề rác, mọi tiến trình còn lại đều
 * được lấy {@code ProcessName} + {@code MainWindowTitle} trả về.</p>
 *
 * <p><b>Hotfix giữ nguyên</b>: stdout/stderr tách RIÊNG (không
 * redirectErrorStream); {@code exitCode != 0} HOẶC stderr có nội dung →
 * fallback payload rỗng — chuỗi lỗi PowerShell TUYỆT ĐỐI không thành payload;
 * lỗi script → trả về rỗng (không bao giờ ném exception ra ngoài).</p>
 */
public class WindowsActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(WindowsActivityCollector.class);

    /** Timeout cho mỗi lần gọi PowerShell (giây) — 2s theo spec, lỗi → rỗng. */
    static final int POWERSHELL_TIMEOUT_SECONDS = 2;

    /**
     * Script PowerShell SIÊU NHẸ (1 pipeline, không Add-Type/csc.exe):
     * <ul>
     *   <li>Chỉ lấy tiến trình CÓ cửa sổ giao diện thật
     *       ({@code MainWindowHandle -ne 0}) + tiêu đề không rỗng;</li>
     *   <li>Loại tiến trình nền hệ thống qua regex ProcessName
     *       (explorer/dwm/svchost/conhost/taskhostw);</li>
     *   <li>Dòng ĐẦU stdout = ứng dụng người dùng đang tương tác, sau đó là
     *       các cửa sổ còn lại (tối đa {@link #MAX_WINDOWED_PROCESSES});</li>
     *   <li>Mỗi dòng xuất {@code process|title}.</li>
     * </ul>
     * Không lấy được gì (màn hình khóa, không cửa sổ) → không xuất dòng nào
     * → payload rỗng.
     */
    static final String PS_SCRIPT =
            "$h = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 "
            + "-and $_.MainWindowTitle -ne '' "
            + "-and $_.ProcessName -notmatch '^(explorer|dwm|taskhostw|conhost|svchost)$' } "
            + "| Select-Object -First 5; "
            + "if ($h) { foreach ($item in $h) { "
            + "Write-Output ($item.ProcessName + '|' + $item.MainWindowTitle) } }";

    /** Số tiến trình có cửa sổ tối đa script trả về (dòng đầu = đang tương tác). */
    static final int MAX_WINDOWED_PROCESSES = 5;

    /**
     * Tiến trình NỀN hệ thống Windows bị loại khỏi payload (không phải tương
     * tác của người dùng) — so khớp THỨ 2 phía Java (script đã lọc trước).
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
            } catch (IOException e) {
                // Stream đóng đột ngột (timeout/destroy) — chấp nhận, defensive.
            }
        }, "parental-client-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
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

    /** 1 ứng dụng người dùng thu được từ stdout PowerShell. */
    record UserApp(String processName, String title) {
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
     * Parse đầu ra PowerShell (dòng {@code process|title}):
     * <ul>
     *   <li>Dòng ĐẦU TIÊN hợp lệ = <b>ứng dụng đang tương tác</b> (script chỉ
     *       trả tiến trình có cửa sổ + tiêu đề thật, đã loại tiến trình nền
     *       hệ thống) — KHÔNG đoán theo CPU, KHÔNG hardcode tên trình duyệt;</li>
     *   <li>Áp Windowed App Filter phía Java (phòng thủ 2 lớp): bỏ tiến trình
     *       hệ thống + tiêu đề rác/rỗng;</li>
     *   <li>Mọi ứng dụng khác (trình duyệt/game/IDE bất kỳ) đều được nhận diện
     *       tự động qua ProcessName — không phụ thuộc danh sách nào.</li>
     * </ul>
     */
    ActivityReportPayload parseOutput(String output) {
        List<UserApp> validApps = new ArrayList<>();

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
                // Windowed App Filter: bỏ tiến trình hệ thống + tiêu đề rác/rỗng
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
        // Active = dòng đầu hợp lệ (ứng dụng người dùng đang tương tác theo
        // script); KHÔNG ưu tiên theo nhóm app — không hardcode tên trình duyệt.
        UserApp active = validApps.get(0);

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
