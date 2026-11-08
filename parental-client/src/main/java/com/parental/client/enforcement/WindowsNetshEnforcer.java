package com.parental.client.enforcement;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triển khai {@link NetworkEnforcer} trên Windows qua {@code netsh advfirewall}
 * (Task 3.3) — dùng <b>Firewall Rule</b> thay vì tắt card mạng: máy con vẫn
 * giữ kết nối LAN tới Server (rule chỉ chặn outbound HTTP/HTTPS), không mất
 * quyền điều khiển từ xa.
 *
 * <p>Câu lệnh:</p>
 * <ul>
 *   <li>Block TCP: {@code netsh advfirewall firewall add rule name="ParentalControlBlockTCP"
 *       dir=out action=block remoteport=80,443 protocol=TCP}</li>
 *   <li>Block UDP: {@code netsh advfirewall firewall add rule name="ParentalControlBlockUDP"
 *       dir=out action=block remoteport=80,443,53 protocol=UDP} — chặn QUIC/HTTP3
 *       (UDP 443) mà Chrome/Edge/Firefox dùng để lách rule chỉ chặn TCP, kèm cả
 *       DNS-over-UDP cổng 53 (phòng resolution nhưng vẫn giữ DNS qua TCP).</li>
 *   <li>Unblock: xóa CẢ HAI rule TCP + UDP khi khôi phục mạng.</li>
 * </ul>
 *
 * <p><b>Defensive OS Interfacing</b> (AGENTS.md):
 * {@code waitFor(5s)} chống treo tiến trình (treo → destroy + coi như thất
 * bại); kiểm tra {@code exitCode == 0}; bắt {@code IOException}/
 * {@code SecurityException} (thiếu quyền Run as Administrator) → log WARN,
 * KHÔNG BAO GIỜ ném exception làm sập Client. Trên hệ điều hành khác hoặc
 * khi {@code netsh} không tồn tại, mọi thao tác là no-op an toàn.</p>
 */
public class WindowsNetshEnforcer implements NetworkEnforcer {

    public static final String FIREWALL_RULE_NAME_TCP = "ParentalControlBlockTCP";
    public static final String FIREWALL_RULE_NAME_UDP = "ParentalControlBlockUDP";
    /** Thời gian tối đa chờ tiến trình netsh (theo yêu cầu Task 3.3). */
    public static final int PROCESS_TIMEOUT_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(WindowsNetshEnforcer.class);

    /**
     * Dead-man switch: tự RESUME sau tối đa 60 phút (cấu hình
     * {@code -Dparental.pause.maxMinutes=...}, 0 = tắt). Tránh máy bị kẹt
     * PAUSED vĩnh viễn nếu Client chết/mất kết nối trước khi Server gửi RESUME.
     */
    public static final int DEFAULT_AUTO_RESUME_MINUTES = 60;

    private final boolean windowsOs;
    /** Kiểm tra lại rule sau add/delete — test cũ tắt để không chạy netsh thật. */
    private final boolean verifyRules;
    private volatile boolean paused = false;
    private volatile Runnable autoResumeListener;
    private final ScheduledExecutorService autoResumeScheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "parental-client-auto-resume");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ScheduledFuture<?> autoResumeFuture;

    public WindowsNetshEnforcer() {
        this(isWindows(), true);
    }

    /** Cho test inject OS flag — không bao giờ chạy netsh thật trong test. */
    WindowsNetshEnforcer(boolean windowsOs) {
        this(windowsOs, false);
    }

    /**
     * @param verifyRules true = sau add phải thấy rule tồn tại, sau delete phải
     *                    thấy rule biến mất (không tin exit code đơn thuần)
     */
    WindowsNetshEnforcer(boolean windowsOs, boolean verifyRules) {
        this.windowsOs = windowsOs;
        this.verifyRules = verifyRules;
    }

    /** Callback khi timer tự RESUME (ActionDispatcher dùng để sync metrics). */
    @Override
    public void setAutoResumeListener(Runnable listener) {
        this.autoResumeListener = listener;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Override
    public boolean pauseNetwork() {
        if (paused) {
            log.debug("Network already paused — skipping duplicate PAUSE command");
            return true;
        }
        // TCP: chặn HTTP/HTTPS thường (80,443/TCP).
        boolean tcpOk = execute("advfirewall", "firewall", "add", "rule",
                "name=" + FIREWALL_RULE_NAME_TCP, "dir=out", "action=block",
                "remoteport=80,443", "protocol=TCP");
        // UDP: chặn QUIC/HTTP3 (UDP 443 — Chrome/Edge/Firefox dùng lách rule TCP)
        // và DNS-over-UDP cổng 53. LAN tới Server vẫn giữ (rule chỉ chặn outbound).
        boolean udpOk = execute("advfirewall", "firewall", "add", "rule",
                "name=" + FIREWALL_RULE_NAME_UDP, "dir=out", "action=block",
                "remoteport=80,443,53", "protocol=UDP");
        boolean verified = !verifyRules
                || (isRulePresent(FIREWALL_RULE_NAME_TCP)
                        && isRulePresent(FIREWALL_RULE_NAME_UDP));
        if (tcpOk && udpOk && verified) {
            paused = true;
            scheduleAutoResume();
            log.warn("Network PAUSED: outbound HTTP/HTTPS blocked by firewall rules '{}' (TCP) "
                    + "+ '{}' (UDP/QUIC), auto-resume in {} min",
                    FIREWALL_RULE_NAME_TCP, FIREWALL_RULE_NAME_UDP, autoResumeMinutes());
        } else {
            // Bán khóa: rollback rule đã thêm để không rơi vào trạng thái lửng
            // (chặn TCP nhưng UDP/QUIC vẫn thông = duyệt web vẫn hoạt động).
            log.warn("Network pause INCOMPLETE (tcpOk={}, udpOk={}, verified={}) — rolling back partial rules",
                    tcpOk, udpOk, verified);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
        }
        return tcpOk && udpOk && verified;
    }

    @Override
    public boolean resumeNetwork() {
        cancelAutoResume();
        if (!paused) {
            if (verifyRules) {
                // Không tin exit code: chỉ coi là đã resume khi TRUY VẤN thấy rule biến mất.
                boolean tcpPresent = isRulePresent(FIREWALL_RULE_NAME_TCP);
                boolean udpPresent = isRulePresent(FIREWALL_RULE_NAME_UDP);
                boolean tcpOk = !tcpPresent || execute("advfirewall", "firewall", "delete",
                        "rule", "name=" + FIREWALL_RULE_NAME_TCP);
                boolean udpOk = !udpPresent || execute("advfirewall", "firewall", "delete",
                        "rule", "name=" + FIREWALL_RULE_NAME_UDP);
                boolean gone = !isRulePresent(FIREWALL_RULE_NAME_TCP)
                        && !isRulePresent(FIREWALL_RULE_NAME_UDP);
                if (!(tcpOk && udpOk && gone)) {
                    log.warn("Resume (not paused) could not remove stale rules (tcpPresent={}, "
                            + "udpPresent={}, gone={}) — check Administrator privileges",
                            tcpPresent, udpPresent, gone);
                    return false;
                }
                paused = false;
                return true;
            }
            // Test/legacy: idempotent best-effort, không chạy netsh thật.
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
            return true;
        }
        // Đang paused: xóa cả hai, rồi xác minh rule đã biến mất.
        boolean tcpOk = execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
        boolean udpOk = execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
        boolean gone = !verifyRules || (!isRulePresent(FIREWALL_RULE_NAME_TCP)
                && !isRulePresent(FIREWALL_RULE_NAME_UDP));
        boolean ok = tcpOk && udpOk && gone;
        if (ok) {
            paused = false;
            log.info("Network RESUMED: firewall rules '{}' + '{}' removed (verified={})",
                    FIREWALL_RULE_NAME_TCP, FIREWALL_RULE_NAME_UDP, verifyRules);
        } else {
            log.warn("Network resume incomplete (tcpOk={}, udpOk={}, gone={}) — firewall rules may "
                    + "remain, check Administrator privileges", tcpOk, udpOk, gone);
        }
        return ok;
    }

    /** Truy vấn trạng thái rule thật (exit 0 = tồn tại, không log WARN khi vắng). */
    boolean isRulePresent(String ruleName) {
        return executeQuiet("advfirewall", "firewall", "show", "rule", "name=" + ruleName);
    }

    private static int autoResumeMinutes() {
        return Integer.getInteger("parental.pause.maxMinutes", DEFAULT_AUTO_RESUME_MINUTES);
    }

    private void scheduleAutoResume() {
        cancelAutoResume();
        int minutes = autoResumeMinutes();
        if (!windowsOs || minutes <= 0) {
            return;
        }
        autoResumeFuture = autoResumeScheduler.schedule(() -> {
            log.warn("Auto-resume timer fired after {} min — restoring network without Server RESUME",
                    minutes);
            boolean ok = resumeNetwork();
            Runnable listener = autoResumeListener;
            if (listener != null) {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.warn("Auto-resume listener threw: {}", e.getMessage());
                }
            }
            if (!ok) {
                log.error("Auto-resume FAILED — network may still be blocked; check Administrator privileges");
            }
        }, minutes, TimeUnit.MINUTES);
    }

    private void cancelAutoResume() {
        ScheduledFuture<?> future = autoResumeFuture;
        if (future != null) {
            future.cancel(false);
            autoResumeFuture = null;
        }
    }

    @Override
    public String getNetworkStatus() {
        return paused ? STATUS_PAUSED : STATUS_ACTIVE;
    }

    /**
     * Dọn rule firewall SÓT từ lần chạy trước bị kill cứng (Bug B) — chạy
     * MỘT LẦN lúc Client khởi động, TRƯỚC khi áp chính sách mới.
     *
     * <p><b>Phạm vi tuyệt đối:</b> chỉ 3 tên rule do project tạo, truyền nguyên
     * văn qua {@code name=...} (KHÔNG dùng wildcard, KHÔNG quét rule khác):</p>
     * <ul>
     *   <li>{@value #FIREWALL_RULE_NAME_TCP} — pause HTTP/HTTPS TCP</li>
     *   <li>{@value #FIREWALL_RULE_NAME_UDP} — pause QUIC/DNS UDP</li>
     *   <li>{@link BrowserBypassEnforcer#FIREWALL_RULE_NAME_QUIC} — chặn QUIC của hosts bypass</li>
     * </ul>
     *
     * <p>Mỗi rule được kiểm tra tồn tại bằng {@code netsh ... show rule} trước
     * khi xóa — rule không tồn tại thì bỏ qua (không log WARN giả). Thiếu quyền
     * Administrator/timeout/lỗi netsh chỉ log, KHÔNG ném exception, KHÔNG chặn
     * Client khởi động. Non-Windows no-op.</p>
     *
     * <p><b>Giả định:</b> một máy chỉ chạy một Client. Nếu một Client khác đang
     * thực sự PAUSE, lần khởi động này sẽ gỡ rule của nó (không thể phân biệt
     * rule sót do crash với rule đang hoạt động).</p>
     */
    public void cleanupStaleRulesOnStartup() {
        if (!windowsOs) {
            log.debug("Non-Windows OS — startup firewall cleanup skipped");
            return;
        }
        List<String> projectRules = List.of(
                FIREWALL_RULE_NAME_TCP,
                FIREWALL_RULE_NAME_UDP,
                BrowserBypassEnforcer.FIREWALL_RULE_NAME_QUIC);
        int removed = 0;
        for (String ruleName : projectRules) {
            // show rule: exit 0 = đang tồn tại. Không dùng execute() thường để
            // tránh log WARN "exit code 1" cho trường hợp bình thường (không sót).
            if (!executeQuiet("advfirewall", "firewall", "show", "rule", "name=" + ruleName)) {
                log.debug("Startup firewall cleanup: rule '{}' not present", ruleName);
                continue;
            }
            log.warn("Startup firewall cleanup: found stale rule '{}' from a previous run — deleting it",
                    ruleName);
            if (execute("advfirewall", "firewall", "delete", "rule", "name=" + ruleName)) {
                removed++;
            } else {
                log.warn("Startup firewall cleanup: could not delete '{}' "
                        + "(run Client as Administrator to clean it up)", ruleName);
            }
        }
        paused = false;
        log.info("Startup firewall cleanup finished: {} stale project rule(s) removed", removed);
    }

    /** Cleanup on exit: luôn khôi phục mạng khi tắt ứng dụng. Không ném exception. */
    @Override
    public void cleanup() {
        cancelAutoResume();
        try {
            if (paused) {
                resumeNetwork();
            } else {
                // Phòng trường hợp rule còn sót từ phiên trước (crash đột ngột)
                execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
                execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
            }
        } catch (RuntimeException e) {
            log.warn("Cleanup failed: {}", e.getMessage());
        } finally {
            autoResumeScheduler.shutdownNow();
        }
    }

    /**
     * Thực thi netsh với đầy đủ phòng thủ: kiểm tra OS, timeout 5s (destroy
     * khi treo), kiểm tra exit code, nuốt IOException/SecurityException.
     * Phương thức package-private để test override (không chạy netsh thật).
     */
    boolean execute(String... netshArgs) {
        return runNetsh(false, netshArgs);
    }

    /**
     * Như {@link #execute(String...)} nhưng không log WARN khi exit code != 0 —
     * dùng cho truy vấn {@code show rule} (rule không tồn tại là bình thường).
     */
    boolean executeQuiet(String... netshArgs) {
        return runNetsh(true, netshArgs);
    }

    private boolean runNetsh(boolean quiet, String... netshArgs) {
        if (!windowsOs) {
            log.debug("Non-Windows OS — netsh enforcer is a no-op");
            return true; // coi như thành công trên môi trường không phải Windows (CI/Linux)
        }
        Process process = null;
        try {
            String[] commandLine = new String[netshArgs.length + 1];
            commandLine[0] = "netsh";
            System.arraycopy(netshArgs, 0, commandLine, 1, netshArgs.length);
            ProcessBuilder pb = new ProcessBuilder(commandLine);
            pb.redirectErrorStream(true);
            pb.directory(new File(System.getProperty("java.io.tmpdir")));
            process = pb.start();

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("netsh command timed out after {}s: {}",
                        PROCESS_TIMEOUT_SECONDS, String.join(" ", netshArgs));
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                if (quiet) {
                    log.debug("netsh exited with code {} (quiet query): {}",
                            exitCode, String.join(" ", netshArgs));
                    return false;
                }
                // WARN rõ ràng cho lập trình viên: gần như luôn do thiếu quyền
                // Administrator (netsh advfirewall yêu cầu elevated process).
                log.warn("netsh exited with code {} — Firewall rule FAILED. Most likely the client "
                        + "is NOT running as Administrator (netsh advfirewall requires elevation). "
                        + "Command: {}", exitCode, String.join(" ", netshArgs));
                return false;
            }
            return true;
        } catch (java.io.IOException e) {
            log.warn("Failed to start netsh ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (SecurityException e) {
            log.warn("SecurityManager blocked netsh execution: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("netsh execution interrupted: {}", String.join(" ", netshArgs));
            return false;
        } catch (RuntimeException e) {
            log.warn("Unexpected error executing netsh: {}", e.getMessage());
            return false;
        }
    }
}
