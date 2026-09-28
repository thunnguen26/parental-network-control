package com.parental.client.enforcement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quản lý danh sách TIẾN TRÌNH bị chặn trên máy con (Mở rộng 10 — "Chặn app
 * .exe 1-click"): server gửi {@code ActionType.BLOCK_PROCESS} với
 * {@code reason} = tên tiến trình; dispatcher lưu vào
 * {@code blocked_processes.json} (persist qua restart) và:
 * <ol>
 *   <li><b>Tức thì</b>: {@code taskkill /F /IM <tên>} mọi instance đang chạy
 *       (thử cả tên có/không đuôi {@code .exe});</li>
 *   <li><b>Lặp 60s</b>: daemon scheduler quét + kết thúc lại nếu ứng dụng tự
 *       mở lại — chặn "trốn" bằng cách mở lại app.</li>
 * </ol>
 *
 * <p><b>Defensive OS Interfacing</b> (AGENTS.md): taskkill timeout 5s +
 * destroyForcibly; nuốt mọi exception — lỗi kill/log chỉ WARN, KHÔNG BAO GIỜ
 * làm crash Client hay ngắt Socket. Non-Windows: lưu danh sách nhưng bỏ qua
 * taskkill (no-op an toàn cho test/CI).</p>
 *
 * <p>File danh sách lưu cùng thư mục làm việc (giống {@code client_identity.json})
 * — atomic write, defensive với file hỏng (hỏng → log WARN, coi như rỗng).</p>
 */
public class ProcessBlockerManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessBlockerManager.class);

    /** Tên file lưu danh sách tiến trình bị chặn (thư mục làm việc). */
    public static final String BLOCKED_PROCESSES_FILE = "blocked_processes.json";

    /** Chu kỳ quét + kill lại tiến trình bị chặn (giây) — chống mở lại app. */
    public static final int RESCAN_INTERVAL_SECONDS = 60;

    /** Timeout mỗi lệnh taskkill (giây). */
    static final int TASKKILL_TIMEOUT_SECONDS = 5;

    private final Path listFilePath;
    private final boolean windowsOs;

    public ProcessBlockerManager() {
        this(Path.of(BLOCKED_PROCESSES_FILE), isWindows());
    }

    /** Constructor test: truyền temp file + OS flag (không đụng file thật). */
    ProcessBlockerManager(Path listFilePath, boolean windowsOs) {
        this.listFilePath = listFilePath;
        this.windowsOs = windowsOs;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public Path getListFilePath() {
        return listFilePath;
    }

    // ------------------------------------------------------------------
    // API chính (gọi từ ActionDispatcher khi nhận BLOCK_PROCESS)
    // ------------------------------------------------------------------

    /**
     * Chặn 1 tiến trình: lưu danh sách (persist) + taskkill tức thì mọi
     * instance đang chạy.
     *
     * @param processName tên tiến trình ({@code "somegame"} hoặc
     *                    {@code "somegame.exe"} — chuẩn hóa tự động)
     * @return {@code true} nếu hợp lệ và đã lưu (kill là best-effort)
     */
    public synchronized boolean blockProcess(String processName) {
        String normalized = normalizeProcessName(processName);
        if (normalized == null) {
            log.warn("Cannot block invalid process name: {}", processName);
            return false;
        }
        Set<String> blocked = loadBlockedProcesses();
        if (blocked.add(normalized)) {
            saveBlockedProcesses(blocked);
            log.info("Process '{}' added to blocked list ({} total)", normalized, blocked.size());
        } else {
            log.info("Process '{}' already blocked — idempotent, re-killing instances", normalized);
        }
        killProcessNow(normalized);
        return true;
    }

    /** Danh sách tiến trình đang bị chặn (đã chuẩn hóa, có đuôi .exe). */
    public synchronized List<String> getBlockedProcesses() {
        return List.copyOf(loadBlockedProcesses());
    }

    /** Xóa toàn bộ danh sách chặn (dùng khi unpair/cleanup). */
    public synchronized boolean clearAll() {
        try {
            Files.deleteIfExists(listFilePath);
            log.info("Blocked processes list cleared");
            return true;
        } catch (IOException e) {
            log.warn("Failed to clear blocked processes list: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Đồng bộ TOÀN BỘ danh sách chặn tiến trình từ Server (Mở rộng 11 —
     * REPLACE-ALL semantics của CONFIG_SYNC.blockedProcesses): ghi đè file
     * danh sách. App bị GỠ khỏi danh sách = bỏ chặn: daemon quét 60s và mọi
     * luồng kill sau đó không đụng tới nữa — người dùng mở lại bình thường.
     * App còn/vừa có trong danh sách mới → kill ngay để trạng thái ăn khớp.
     *
     * @param processNames danh sách tên tiến trình (có/không đuôi .exe,
     *                     null/rỗng = dọn sạch mọi chặn)
     * @return {@code true} nếu ghi thành công
     */
    public synchronized boolean replaceBlockedProcesses(java.util.List<String> processNames) {
        Set<String> normalized = new LinkedHashSet<>();
        if (processNames != null) {
            for (String name : processNames) {
                String clean = normalizeProcessName(name);
                if (clean != null) {
                    normalized.add(clean);
                }
            }
        }
        Set<String> previous = loadBlockedProcesses();
        saveBlockedProcesses(normalized);
        log.info("Blocked processes REPLACED from server config ({} -> {} apps)",
                previous.size(), normalized.size());
        // Kill ngay các app vẫn/vừa nằm trong danh sách — đồng bộ trạng thái
        for (String name : normalized) {
            killProcessNow(name);
        }
        return true;
    }

    /** Bắt đầu daemon quét 60s — kill lại tiến trình bị chặn nếu tự mở lại. */
    public void startRescanScheduler() {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "parental-client-process-rescan");
                    thread.setDaemon(true);
                    return thread;
                });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (String processName : loadBlockedProcesses()) {
                    killProcessNow(processName);
                }
            } catch (RuntimeException e) {
                log.debug("Process rescan failed: {}", e.getMessage());
            }
        }, RESCAN_INTERVAL_SECONDS, RESCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Process rescan scheduler started (every {}s)", RESCAN_INTERVAL_SECONDS);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Chuẩn hóa tên tiến trình: trim + lowercase + bảo đảm đuôi {@code .exe};
     * chỉ nhận ký tự hợp lệ tên file Windows ({@code [a-z0-9._-]}).
     *
     * @return tên chuẩn hóa, hoặc {@code null} nếu không hợp lệ
     */
    static String normalizeProcessName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        if (!name.endsWith(".exe")) {
            name = name + ".exe";
        }
        if (!name.matches("[a-z0-9._-]+\\.exe") || name.equals(".exe") || name.contains("..")) {
            return null;
        }
        return name;
    }

    /** Taskkill MỌI instance đang chạy — best-effort, không ném exception. */
    void killProcessNow(String normalizedProcessName) {
        if (!windowsOs) {
            log.debug("Non-Windows — taskkill skipped for {}", normalizedProcessName);
            return;
        }
        // Tên file Windows tối đa 260 ký tự — taskkill IM không nhận quá dài
        if (normalizedProcessName.length() > 260) {
            log.warn("Process name too long for taskkill: {}", normalizedProcessName);
            return;
        }
        executeTaskkill(normalizedProcessName);
    }

    /** Chạy {@code taskkill /F /IM <name>} với đầy đủ phòng thủ (5s timeout). */
    private void executeTaskkill(String processName) {
        Process process = null;
        try {
            process = new ProcessBuilder("taskkill", "/F", "/IM", processName)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("taskkill timed out for {}", processName);
                return;
            }
            // taskkill exit 0 = đã kill; exit 128 = không tìm thấy process (bình
            // thường — app chưa chạy); khác → log WARN rút gọn.
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("Blocked process killed: {}", processName);
            } else if (exitCode != 128) {
                log.debug("taskkill exit {} for {} (có thể chưa chạy/thiếu quyền)",
                        exitCode, processName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("taskkill failed for {}: {}", processName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Persist (blocked_processes.json — plain JSON array, không cần Jackson
    // file-mapping phức tạp; atomic write giống HostsFileManager)
    // ------------------------------------------------------------------

    private Set<String> loadBlockedProcesses() {
        Set<String> blocked = new LinkedHashSet<>();
        if (!Files.exists(listFilePath)) {
            return blocked;
        }
        try {
            String content = Files.readString(listFilePath, StandardCharsets.UTF_8).trim();
            // Format: ["a.exe","b.exe"] — parse thủ công tối giản, defensive
            if (content.startsWith("[") && content.endsWith("]")) {
                String inner = content.substring(1, content.length() - 1);
                for (String item : inner.split(",")) {
                    String cleaned = item.trim().replace("\"", "");
                    String normalized = normalizeProcessName(cleaned);
                    if (normalized != null) {
                        blocked.add(normalized);
                    }
                }
            } else {
                log.warn("Blocked processes file malformed — treating as empty: {}", listFilePath);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read blocked processes ({}): {} — treating as empty",
                    listFilePath, e.getMessage());
        }
        return blocked;
    }

    private void saveBlockedProcesses(Set<String> blocked) {
        try {
            if (listFilePath.getParent() != null) {
                Files.createDirectories(listFilePath.getParent());
            }
            List<String> quoted = new ArrayList<>();
            for (String name : blocked) {
                quoted.add("\"" + name + "\"");
            }
            String content = "[" + String.join(",", quoted) + "]";
            Path temp = Files.createTempFile(
                    listFilePath.getParent(), "blocked-proc-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, listFilePath, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, listFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to save blocked processes ({}): {}", listFilePath, e.getMessage());
        }
    }
}
