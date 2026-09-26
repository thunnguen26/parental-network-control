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
 *   <li>Block: {@code netsh advfirewall firewall add rule name="ParentalControlBlock"
 *       dir=out action=block remoteport=80,443 protocol=TCP}</li>
 *   <li>Unblock: {@code netsh advfirewall firewall delete rule name="ParentalControlBlock"}</li>
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

    public static final String FIREWALL_RULE_NAME = "ParentalControlBlock";
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
        // Chỉ chặn outbound HTTP/HTTPS (80,443/TCP) — giữ LAN và DNS, server không mất quyền điều khiển.
        boolean ok = execute("advfirewall", "firewall", "add", "rule",
                "name=" + FIREWALL_RULE_NAME, "dir=out", "action=block",
                "remoteport=80,443", "protocol=TCP");
        if (ok) {
            paused = true;
            log.warn("Network PAUSED: outbound HTTP/HTTPS blocked by firewall rule '{}'", FIREWALL_RULE_NAME);
        }
        return ok;
    }

    @Override
    public boolean resumeNetwork() {
        if (!paused) {
            // Idempotent: xóa rule dù cờ đang ACTIVE (phòng trường hợp rule còn sót)
            execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME);
            return true;
        }
        boolean ok = execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME);
        if (ok) {
            paused = false;
            log.info("Network RESUMED: firewall rule '{}' removed", FIREWALL_RULE_NAME);
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
                execute("advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE_NAME);
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
                // Thường gặp: thiếu quyền Administrator (exit 1) — không crash, chỉ báo thất bại
                log.warn("netsh exited with code {} (likely lacking Administrator privileges): {}",
                        exitCode, String.join(" ", netshArgs));
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
