package com.parental.client.enforcement;

import java.io.File;
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

    private final boolean windowsOs;
    private volatile boolean paused = false;

    public WindowsNetshEnforcer() {
        this(isWindows());
    }

    /** Cho test inject OS flag — không bao giờ chạy netsh thật trong test. */
    WindowsNetshEnforcer(boolean windowsOs) {
        this.windowsOs = windowsOs;
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
        if (tcpOk && udpOk) {
            paused = true;
            log.warn("Network PAUSED: outbound HTTP/HTTPS blocked by firewall rules '{}' (TCP) "
                    + "+ '{}' (UDP/QUIC)", FIREWALL_RULE_NAME_TCP, FIREWALL_RULE_NAME_UDP);
        } else {
            // Bán khóa: rollback rule đã thêm để không rơi vào trạng thái lửng
            // (chặn TCP nhưng UDP/QUIC vẫn thông = duyệt web vẫn hoạt động).
            log.warn("Network pause INCOMPLETE (tcpOk={}, udpOk={}) — rolling back partial rules",
                    tcpOk, udpOk);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
        }
        return tcpOk && udpOk;
    }

    @Override
    public boolean resumeNetwork() {
        if (!paused) {
            // Idempotent best-effort: xóa rule sót (phòng crash phiên trước).
            // Rule không tồn tại → netsh exit 1 — KHÔNG coi là thất bại vì không
            // có gì để gỡ (giữ semantic cũ: resume khi chưa pause luôn true).
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
            return true;
        }
        // Đang paused: cả hai rule đáng lẽ tồn tại → xóa CẢ HAI, thành công khi
        // cả hai lệnh netsh đều exit 0.
        boolean tcpOk = execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_TCP);
        boolean udpOk = execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME_UDP);
        boolean ok = tcpOk && udpOk;
        if (ok) {
            paused = false;
            log.info("Network RESUMED: firewall rules '{}' + '{}' removed",
                    FIREWALL_RULE_NAME_TCP, FIREWALL_RULE_NAME_UDP);
        } else {
            log.warn("Network resume incomplete (tcpOk={}, udpOk={}) — firewall rules may remain, "
                    + "check Administrator privileges", tcpOk, udpOk);
        }
        return ok;
    }

    @Override
    public String getNetworkStatus() {
        return paused ? STATUS_PAUSED : STATUS_ACTIVE;
    }

    /** Cleanup on exit: luôn khôi phục mạng khi tắt ứng dụng. Không ném exception. */
    @Override
    public void cleanup() {
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
        }
    }

    /**
     * Thực thi netsh với đầy đủ phòng thủ: kiểm tra OS, timeout 5s (destroy
     * khi treo), kiểm tra exit code, nuốt IOException/SecurityException.
     * Phương thức package-private để test override (không chạy netsh thật).
     */
    boolean execute(String... netshArgs) {
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
