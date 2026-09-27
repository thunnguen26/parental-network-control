package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên Windows KHÔNG cần thư viện ngoài:
 * gọi PowerShell (Get-Process | Where MainWindowTitle) qua {@link ProcessBuilder}
 * — thu tiêu đề + tiến trình cửa sổ đang focus và top tiến trình có cửa sổ.
 *
 * <p><b>Defensive (AGENTS.md)</b>:</p>
 * <ul>
 *   <li>Non-Windows → trả payload rỗng ngay (không spawn process).</li>
 *   <li>PowerShell timeout 3s + {@code destroyForcibly} khi treo.</li>
 *   <li>Mọi lỗi (IOException/InterruptedException/RuntimeException) nuốt →
 *       payload rỗng — scheduler của engine KHÔNG BAO GIỜ chết.</li>
 *   <li>Script không đọc đĩa/thư mục — chỉ liệt kê tiến trình (an toàn,
 *       không cần quyền Administrator).</li>
 * </ul>
 */
public class WindowsActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(WindowsActivityCollector.class);

    /** Timeout cho mỗi lần gọi PowerShell (giây). */
    static final int POWERSHELL_TIMEOUT_SECONDS = 3;

    /**
     * PowerShell: liệt kê tiến trình CÓ cửa sổ giao diện, lấy 5 tiến trình
     * tiêu biểu (bỏ system utilities); dòng 1 = active title, dòng 2+ =
     * "processName\ttitle". IsActive cờ đánh dấu cửa sổ đang focus.
     */
    static final String PS_SCRIPT =
            "$procs = Get-Process | Where-Object { $_.MainWindowTitle -ne '' } | "
            + "Sort-Object CPU -Descending | Select-Object -First 8; "
            + "$fg = $procs | Select-Object -First 1; "
            + "if ($fg) { Write-Output (\"ACTIVE`t\" + $fg.ProcessName + \"`t\" + $fg.MainWindowTitle) }; "
            + "$procs | ForEach-Object { Write-Output (\"ROW`t\" + $_.ProcessName + \"`t\" + $_.MainWindowTitle) }";

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
                    .redirectErrorStream(true)
                    .start();
            String output = readOutput(process);
            boolean finished = process.waitFor(POWERSHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("PowerShell activity probe timed out — returning empty payload");
                return emptyPayload();
            }
            return parseOutput(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyPayload();
        } catch (IOException | RuntimeException e) {
            log.debug("Activity collection failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return emptyPayload();
        }
    }

    /** Đọc stdout của PowerShell (đọc TRƯỚC waitFor — tránh deadlock pipe đầy). */
    private static String readOutput(Process process) throws IOException {
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

    /** Parse đầu ra PowerShell: dòng "ACTIVE\tname\ttitle" + các dòng "ROW\t...". */
    ActivityReportPayload parseOutput(String output) {
        String activeProcess = UNKNOWN;
        String activeTitle = UNKNOWN;
        List<String> topProcesses = new ArrayList<>();

        if (output != null) {
            for (String line : output.split("\\R")) {
                if (!line.startsWith("ROW\t") && !line.startsWith("ACTIVE\t")) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) {
                    continue;
                }
                String processName = parts[1].trim();
                String title = parts[2].trim();
                if (line.startsWith("ACTIVE\t") && activeProcess.isEmpty()) {
                    activeProcess = processName + ".exe";
                    activeTitle = title;
                }
                if (!processName.isEmpty() && !topProcesses.contains(processName)) {
                    topProcesses.add(processName);
                    if (topProcesses.size() >= ActivityReportPayload.MAX_TOP_PROCESSES) {
                        break;
                    }
                }
            }
        }
        // Không có window title nào (màn hình khóa/phiên trống) → trả rỗng chuẩn.
        if (activeProcess.isEmpty() && topProcesses.isEmpty()) {
            return emptyPayload();
        }
        // Fallback: không xác định được active → lấy tiến trình đầu tiên.
        if (activeProcess.isEmpty() && !topProcesses.isEmpty()) {
            activeProcess = topProcesses.get(0) + ".exe";
        }
        return new ActivityReportPayload(activeTitle, activeProcess,
                List.copyOf(topProcesses), System.currentTimeMillis());
    }

    /** Payload rỗng chuẩn (không null — contract của {@link ActivityCollector}). */
    static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }
}
