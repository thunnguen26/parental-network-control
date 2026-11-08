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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quản lý danh sách TIẾN TRÌNH bị chặn trên máy con (Mở rộng 10/12):
 * server gửi {@code ActionType.BLOCK_PROCESS} với {@code reason} = tên tiến
 * trình; dispatcher lưu vào {@code blocked_processes.json} (persist qua
 * restart) và:
 * <ol>
 *   <li><b>Tức thì</b>: {@code taskkill /F /IM <tên>} mọi instance đang chạy
 *       (thử cả tên có/không đuôi {@code .exe});</li>
 *   <li><b>Watchdog 2s (Mở rộng 12)</b>: scheduler daemon quét + kết thúc lại
 *       LIÊN TỤC bất cứ instance nào trong danh sách đang chạy — người dùng
 *       nhấp mở lại app cũng bị tắt ngay trong vòng 2 giây, không kịp dùng.
 *       Bỏ chặn (gỡ khỏi danh sách qua {@code replaceBlockedProcesses}) →
 *       watchdog không còn kill app đó nữa → mở lại bình thường.</li>
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

    /**
     * Chu kỳ quét watchdog (giây) — Mở rộng 12: mỗi 2 giây quét MỘT LẦN toàn
     * bộ danh sách và kill mọi instance bị chặn đang chạy. Đủ dày để người
     * dùng "nhấp mở lại app cũng bị tắt ngay", đủ thưa để không đập CPU.
     */
    public static final int WATCHDOG_INTERVAL_SECONDS = 2;

    /** Timeout mỗi lệnh taskkill (giây). */
    static final int TASKKILL_TIMEOUT_SECONDS = 5;

    private final Path listFilePath;
    private final boolean windowsOs;

    /** Watchdog daemon — null khi chưa start; tắt qua {@link #stopWatchdog()}. */
    private final AtomicReference<ScheduledExecutorService> watchdogRef = new AtomicReference<>();

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
        return blockProcessDetailed(processName).persisted();
    }

    /** Kết quả kill tức thì — tách khỏi persist để không ACK giả khi app elevated. */
    public enum KillResult {
        KILLED, NOT_RUNNING, ACCESS_DENIED, ERROR, SKIPPED
    }

    /**
     * @param persisted policy đã ghi bền vững (watchdog sẽ tiếp tục kill)
     * @param killResult kết quả taskkill NGAY (ACCESS_DENIED = app vẫn đang chạy)
     */
    public record BlockResult(boolean persisted, KillResult killResult) {
        /** Chỉ coi là thành công khi policy persist VÀ không bị từ chối quyền. */
        public boolean accepted() {
            return persisted && killResult != KillResult.ACCESS_DENIED
                    && killResult != KillResult.ERROR;
        }
    }

    /**
     * Chặn + trả cả kết quả kill. Dùng bởi ActionDispatcher: taskkill bị Access
     * Denied (app chạy elevated) → không ACK, dù policy đã lưu cho watchdog.
     */
    public synchronized BlockResult blockProcessDetailed(String processName) {
        String normalized = normalizeProcessName(processName);
        if (normalized == null) {
            log.warn("Cannot block invalid process name: {}", processName);
            return new BlockResult(false, KillResult.ERROR);
        }
        Set<String> blocked = loadBlockedProcesses();
        if (blocked.add(normalized)) {
            if (!saveBlockedProcesses(blocked)) {
                log.warn("Process '{}' NOT blocked: could not persist list", normalized);
                return new BlockResult(false, KillResult.ERROR);
            }
            log.info("Process '{}' added to blocked list ({} total)", normalized, blocked.size());
        } else {
            log.info("Process '{}' already blocked — idempotent, re-killing instances", normalized);
        }
        KillResult killResult = killProcessWithResult(normalized);
        if (killResult == KillResult.ACCESS_DENIED) {
            log.warn("Process '{}' persisted but taskkill was ACCESS DENIED — app có thể đang chạy "
                    + "elevated; KHÔNG ACK thành công", normalized);
        }
        return new BlockResult(true, killResult);
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
     * danh sách. App bị GỠ khỏi danh sách = bỏ chặn: watchdog quét 2s và mọi
     * luồng kill sau đó không đụng tới nữa — người dùng mở lại bình thường.
     * App còn/vừa có trong danh sách mới → kill ngay để trạng thái ăn khớp.
     *
     * @param processNames danh sách tên tiến trình (có/không đuôi .exe,
     *                     null/rỗng = dọn sạch mọi chặn)
     * @return {@code true} nếu ghi thành công
     */
    public synchronized boolean replaceBlockedProcesses(java.util.List<String> processNames) {
        return replaceBlockedProcessesDetailed(processNames).persisted();
    }

    /** @param anyAccessDenied có app đang chạy elevated nên taskkill bị từ chối */
    public record ReplaceResult(boolean persisted, boolean anyAccessDenied) {
        /** Policy đã lưu nhưng app elevated vẫn chạy → không coi là áp dụng đủ. */
        public boolean applied() {
            return persisted && !anyAccessDenied;
        }
    }

    /** REPLACE-ALL kèm kết quả kill thật (CONFIG_SYNC status không báo APPLIED giả). */
    public synchronized ReplaceResult replaceBlockedProcessesDetailed(
            java.util.List<String> processNames) {
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
        if (!saveBlockedProcesses(normalized)) {
            log.warn("Blocked processes REPLACE failed — giữ nguyên danh sách cũ, không kill theo cấu hình mới");
            return new ReplaceResult(false, false);
        }
        log.info("Blocked processes REPLACED from server config ({} -> {} apps)",
                previous.size(), normalized.size());
        // Kill ngay các app vẫn/vừa nằm trong danh sách — đồng bộ trạng thái
        boolean denied = false;
        for (String name : normalized) {
            killProcessNow(name);
            denied |= lastKillResult == KillResult.ACCESS_DENIED;
        }
        return new ReplaceResult(true, denied);
    }

    /**
     * Bắt đầu WATCHDOG quét 2s (Mở rộng 12 — thay cho quét 60s cũ): mỗi chu
     * kỳ đọc lại danh sách hiện hành và kill mọi instance bị chặn đang chạy —
     * người dùng mở lại app bị chặn sẽ bị tắt ngay trong vòng 2 giây. Gọi
     * nhiều lần chỉ có 1 watchdog (idempotent); {@link #stopWatchdog()} tắt.
     */
    public void startRescanScheduler() {
        if (watchdogRef.get() != null) {
            return; // idempotent — đã chạy
        }
        ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "parental-client-process-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });
        if (!watchdogRef.compareAndSet(null, scheduler)) {
            // Thread khác đã thắng — đóng scheduler thừa, giữ watchdog cũ
            scheduler.shutdownNow();
            return;
        }
        scheduler.scheduleWithFixedDelay(this::watchdogSweep,
                WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Process watchdog started (every {}s — blocked apps are killed on sight)",
                WATCHDOG_INTERVAL_SECONDS);
    }

    /** Tắt watchdog (dọn dẹp khi engine/unpair dừng). */
    public void stopWatchdog() {
        ScheduledExecutorService scheduler = watchdogRef.getAndSet(null);
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("Process watchdog stopped");
        }
    }

    /** MỘT chu kỳ watchdog: quét danh sách hiện hành + kill mọi app bị chặn. */
    void watchdogSweep() {
        try {
            for (String processName : loadBlockedProcesses()) {
                killProcessNow(processName);
            }
        } catch (RuntimeException e) {
            log.debug("Process watchdog sweep failed: {}", e.getMessage());
        }
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

    /** Kết quả lần kill gần nhất — test override {@link #killProcessNow} giữ SKIPPED. */
    private volatile KillResult lastKillResult = KillResult.SKIPPED;

    /** Taskkill MỌI instance đang chạy — best-effort, không ném exception. */
    void killProcessNow(String normalizedProcessName) {
        if (!windowsOs) {
            log.debug("Non-Windows — taskkill skipped for {}", normalizedProcessName);
            lastKillResult = KillResult.SKIPPED;
            return;
        }
        // Tên file Windows tối đa 260 ký tự — taskkill IM không nhận quá dài
        if (normalizedProcessName.length() > 260) {
            log.warn("Process name too long for taskkill: {}", normalizedProcessName);
            lastKillResult = KillResult.ERROR;
            return;
        }
        lastKillResult = executeTaskkill(normalizedProcessName);
    }

    /** Gọi kill và trả kết quả của lần gọi này (dùng seam killProcessNow cho test). */
    KillResult killProcessWithResult(String normalizedProcessName) {
        killProcessNow(normalizedProcessName);
        return lastKillResult != null ? lastKillResult : KillResult.SKIPPED;
    }

    /** Chạy {@code taskkill /F /IM <name>} với đầy đủ phòng thủ (5s timeout). */
    private KillResult executeTaskkill(String processName) {
        Process process = null;
        try {
            process = new ProcessBuilder("taskkill", "/F", "/IM", processName)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("taskkill timed out for {}", processName);
                return KillResult.ERROR;
            }
            // taskkill exit 0 = đã kill; exit 128 = không tìm thấy process (bình
            // thường — app chưa chạy); exit 5 = Access Denied (app elevated).
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("Blocked process killed: {}", processName);
                return KillResult.KILLED;
            }
            if (exitCode == 128) {
                return KillResult.NOT_RUNNING;
            }
            if (exitCode == 5) {
                log.warn("taskkill ACCESS DENIED for {} — app elevated/chạy quyền cao hơn Client",
                        processName);
                return KillResult.ACCESS_DENIED;
            }
            log.debug("taskkill exit {} for {} (không xác định)", exitCode, processName);
            return KillResult.ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return KillResult.ERROR;
        } catch (IOException | RuntimeException e) {
            log.warn("taskkill failed for {}: {}", processName, e.getMessage());
            return KillResult.ERROR;
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

    private boolean saveBlockedProcesses(Set<String> blocked) {
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
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to save blocked processes ({}): {}", listFilePath, e.getMessage());
            return false;
        }
    }
}
