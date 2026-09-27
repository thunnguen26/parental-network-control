package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên Windows KHÔNG cần thư viện ngoài:
 * gọi PowerShell qua {@link ProcessBuilder} — thu tiêu đề + tiến trình cửa sổ
 * đang focus và danh sách tiến trình có cửa sổ giao diện.
 *
 * <p><b>Hotfix lỗi gửi chuỗi lỗi PowerShell lên Server</b>: phiên bản cũ dùng
 * {@code redirectErrorStream(true)} — nội dung lỗi (stderr) bị trộn vào stdout
 * rồi parse thành "tiến trình" gửi về Server (vd chuỗi
 * {@code "The term '...' is not recognized..."}). Phiên bản mới:</p>
 * <ul>
 *   <li>Script đơn giản chuẩn hóa: 1 dòng ACTIVE {@code name<TAB>title} +
 *       các dòng ROW, dùng {@code MainWindowHandle -ne 0} để chỉ lấy cửa sổ
 *       thật (giống gợi ý yêu cầu);</li>
 *   <li><b>TÁCH RIÊNG stdout và stderr</b> — KHÔNG redirectErrorStream;</li>
 *   <li>{@code exitCode != 0} HOẶC stderr có nội dung → fallback trả payload
 *       rỗng. <b>Tuyệt đối không đưa nội dung lỗi vào payload.</b></li>
 * </ul>
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
     * Script PowerShell thu thập hoạt động (đã sửa cú pháp escape):
     * <ul>
     *   <li>Chỉ lấy tiến trình có cửa sổ thật: {@code MainWindowHandle -ne 0}
     *       và {@code MainWindowTitle -ne ''};</li>
     *   <li>Sắp xếp theo CPU giảm dần, lấy tối đa 8;</li>
     *   <li>Dòng đầu: {@code ACTIVE<TAB>process<TAB>title} (tiến trình CPU cao
     *       nhất = khả năng cao đang focus);</li>
     *   <li>Các dòng sau: {@code ROW<TAB>process<TAB>title}.</li>
     * </ul>
     * Dùng TAB làm phân tách — không gặp lỗi escape dấu backtick của phiên bản cũ.
     */
    static final String PS_SCRIPT =
            "$p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 "
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
            // fallback rỗng thay vì gửi chuỗi lỗi lên Server (hotfix).
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
     * Parse đầu ra PowerShell: mỗi dòng {@code process<TAB>title}; dòng đầu
     * (CPU cao nhất) là cửa sổ ACTIVE.
     */
    ActivityReportPayload parseOutput(String output) {
        String activeProcess = UNKNOWN;
        String activeTitle = UNKNOWN;
        Set<String> topProcesses = new LinkedHashSet<>();

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
                if (activeProcess.isEmpty()) {
                    activeProcess = processName + ".exe";
                    activeTitle = title;
                }
                if (topProcesses.add(processName)
                        && topProcesses.size() >= ActivityReportPayload.MAX_TOP_PROCESSES) {
                    break;
                }
            }
        }
        // Không có window nào (màn hình khóa/phiên trống) → trả rỗng chuẩn.
        if (topProcesses.isEmpty()) {
            return emptyPayload();
        }
        // Fallback: không xác định được active → lấy tiến trình đầu tiên.
        if (activeProcess.isEmpty()) {
            activeProcess = topProcesses.iterator().next() + ".exe";
        }
        return new ActivityReportPayload(activeTitle, activeProcess,
                List.copyOf(topProcesses), System.currentTimeMillis());
    }

    /** Payload rỗng chuẩn (không null — contract của {@link ActivityCollector}). */
    static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }
}
