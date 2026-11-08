package com.parental.client.enforcement;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vô hiệu hóa các đường lách file {@code hosts} của trình duyệt Chromium
 * (Cốc Cốc/Chrome/Edge/Brave) — gọi TRƯỚC khi ghi hosts (chặn đường thoát
 * trước) và sau khi ghi (flush cache):
 *
 * <ol>
 *   <li><b>DoH (DNS over HTTPS / Secure DNS)</b>: trình duyệt giải mã tên
 *       miền qua HTTPS tới server DoH → bypass hoàn toàn file hosts. Vô hiệu
 *       bằng Registry Policy của từng trình duyệt
 *       ({@code HKLM\SOFTWARE\Policies\...}: {@code DnsOverHttpsMode = "off"}
 *       + {@code BuiltInDnsClientEnabled = 0} — xem
 *       {@link #DOH_DISABLE_COMMANDS}) — quyền HKLM không cần Administrator
 *       nhưng ghi bằng {@code reg add} qua ProcessBuilder;</li>
 *   <li><b>QUIC / HTTP3 (UDP 443)</b>: trình duyệt tái sử dụng connection
 *       QUIC đã thiết lập → vẫn vào được web dù DNS đã trỏ về 0.0.0.0. Chặn
 *       bằng firewall rule chặn outbound UDP 443 (đưa QUIC về TCP thường —
 *       khi đó hosts có hiệu lực);</li>
 *   <li><b>Cache DNS/NetBIOS</b>: {@code ipconfig /flushdns} +
 *       {@code nbtstat -R} để kết nối MỚI giải mã lại qua hosts.</li>
 * </ol>
 *
 * <p><b>Defensive OS Interfacing</b> (AGENTS.md): mọi lệnh đều
 * {@code waitFor(timeout)} + destroyForcibly khi treo, kiểm tra exitCode,
 * nuốt IOException/SecurityException — lỗi một bước chỉ log WARN và tiếp tục
 * bước sau, KHÔNG BAO GIỜ ném exception làm crash Client hay ngắt Socket.
 * Non-Windows là no-op an toàn (trả true).</p>
 */
public class BrowserBypassEnforcer {

    private static final Logger log = LoggerFactory.getLogger(BrowserBypassEnforcer.class);

    /** Firewall rule chặn QUIC/HTTP3 (outbound UDP 443) — tách riêng rule pause. */
    public static final String FIREWALL_RULE_NAME_QUIC = "ParentalControlBlockQUIC";

    /** Thời gian tối đa chờ mỗi tiến trình hệ thống (giây). */
    public static final int PROCESS_TIMEOUT_SECONDS = 5;

    /**
     * Các key policy Chromium mà trình duyệt đọc {@code URLBlocklist}. Dùng
     * chung danh sách domain đang chặn: policy này chặn cả subdomain (ví dụ
     * {@code youtube.com} chặn {@code consent.youtube.com}) và tự làm mới khi
     * registry đổi mà không cần restart trình duyệt. Đây là lớp bổ sung cho
     * hosts — hosts không hỗ trợ wildcard.
     */
    static final List<String> URL_BLOCKLIST_POLICY_KEYS = List.of(
            "HKLM\\SOFTWARE\\Policies\\Chromium",
            "HKLM\\SOFTWARE\\Policies\\Google\\Chrome",
            "HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge");

    /**
     * Lệnh Registry tắt DoH của trình duyệt Chromium qua Group Policy — key
     * {@code reg add ... /f} (ghi đè không hỏi). Chrome = key Google\Chrome,
     * Edge = key Microsoft\Edge, Cốc Cốc/Brave dùng key Chromium.
     */
    static final List<String> DOH_DISABLE_COMMANDS = List.of(
            // Chromium chung (bao gồm Cốc Cốc build Chromium thuần)
            "reg add HKLM\\SOFTWARE\\Policies\\Chromium /v DnsOverHttpsMode /t REG_SZ /d off /f",
            "reg add HKLM\\SOFTWARE\\Policies\\Chromium /v BuiltInDnsClientEnabled /t REG_DWORD /d 0 /f",
            // Google Chrome
            "reg add HKLM\\SOFTWARE\\Policies\\Google\\Chrome /v DnsOverHttpsMode /t REG_SZ /d off /f",
            "reg add HKLM\\SOFTWARE\\Policies\\Google\\Chrome /v BuiltInDnsClientEnabled /t REG_DWORD /d 0 /f",
            // Microsoft Edge
            "reg add HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge /v DnsOverHttpsMode /t REG_SZ /d off /f",
            "reg add HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge /v BuiltInDnsClientEnabled /t REG_DWORD /d 0 /f");

    /** Kết quả khóa đường lách — dùng để báo cáo trung thực về Server/GUI. */
    public record LockStatus(boolean dohDisabled, boolean quicBlocked,
                             boolean urlBlocklistApplied, boolean fullyLocked,
                             String message) {
    }

    private final boolean windowsOs;

    public BrowserBypassEnforcer() {
        this(isWindows());
    }

    /** Cho test inject OS flag — không bao giờ chạy lệnh thật trong test. */
    BrowserBypassEnforcer(boolean windowsOs) {
        this.windowsOs = windowsOs;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * Khóa toàn bộ đường lách hosts TRƯỚC khi ghi block mới: tắt DoH (registry)
     * + thêm firewall rule chặn QUIC UDP 443 + flush DNS/NetBIOS cache.
     * Lỗi từng bước không chặn bước sau; chỉ trả {@code true} khi ĐÚNG ít nhất
     * DoH tắt được hoặc QUIC chặn được (các cache flush là best-effort).
     *
     * @return {@code true} nếu ít nhất 1 cơ chế lách bị vô hiệu thành công
     */
    public boolean lockBypassPaths() {
        return lockBypassPathsWithStatus(List.of()).fullyLocked();
    }

    /**
     * Khóa đường lách kèm trạng thái chi tiết và áp {@code URLBlocklist} cho
     * danh sách domain. Chỉ {@code fullyLocked} khi CẢ DoH lẫn QUIC đều khóa
     * được; nếu có domain thì {@code URLBlocklist} cũng phải ghi được. Trước
     * đây hàm chỉ cần MỘT trong hai thành công nên vẫn báo "đã khóa" khi
     * đường còn lại mở — đó là false success.
     */
    public LockStatus lockBypassPathsWithStatus(List<String> domains) {
        boolean dohOff = disableBrowserDoh();
        boolean quicBlocked = blockQuic();
        boolean urlApplied = applyUrlBlocklist(domains);
        flushDnsAndNetbiosCache();
        boolean needsUrl = domains != null && !domains.isEmpty();
        boolean fully = dohOff && quicBlocked && (!needsUrl || urlApplied);
        String message = "DoH=" + (dohOff ? "off" : "FAILED")
                + "; QUIC=" + (quicBlocked ? "blocked" : "FAILED")
                + (needsUrl ? "; URLBlocklist=" + (urlApplied ? "applied" : "FAILED") : "");
        if (fully) {
            log.info("Browser bypass paths fully locked ({} ) — hosts + URLBlocklist effective", message);
        } else {
            log.warn("Browser bypass paths NOT fully locked ({}) — browser may still bypass; "
                    + "kiểm tra quyền Administrator", message);
        }
        return new LockStatus(dohOff, quicBlocked, urlApplied, fully, message);
    }

    /** Gỡ khóa khi resume/cleanup: mở lại QUIC + trả registry DoH về mặc định. */
    public void unlockBypassPaths() {
        removeQuicBlock();
        restoreBrowserDoh();
        removeUrlBlocklist();
        flushDnsAndNetbiosCache();
        log.info("Browser bypass paths unlocked — DoH/URLBlocklist policies removed, QUIC unblocked");
    }

    /**
     * Ghi policy {@code URLBlocklist} cho Chromium/Chrome/Edge. Entry dạng
     * {@code domain.com} chặn cả subdomain; không dùng wildcard. Danh sách
     * rỗng là no-op (unlock mới là nơi dọn policy cũ).
     */
    boolean applyUrlBlocklist(List<String> domains) {
        if (!windowsOs) {
            return true;
        }
        if (domains == null || domains.isEmpty()) {
            return true;
        }
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String clean = domain.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "").replace("\"", "");
            if (clean.isEmpty()) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            json.append('"').append(clean).append('"');
            first = false;
        }
        json.append(']');
        if (first) {
            return true; // toàn phần tử hỏng — không ghi policy rỗng
        }
        boolean allOk = true;
        for (String key : URL_BLOCKLIST_POLICY_KEYS) {
            allOk &= execute("reg", "add", key, "/v", "URLBlocklist",
                    "/t", "REG_SZ", "/d", json.toString(), "/f");
        }
        if (allOk) {
            log.info("URLBlocklist policy applied for {} domain(s) — subdomains covered",
                    json.toString());
        } else {
            log.warn("URLBlocklist policy failed for at least one browser key");
        }
        return allOk;
    }

    /** Xóa policy URLBlocklist (khôi phục hành vi mặc định). */
    private void removeUrlBlocklist() {
        if (!windowsOs) {
            return;
        }
        for (String key : URL_BLOCKLIST_POLICY_KEYS) {
            execute("reg", "delete", key, "/v", "URLBlocklist", "/f");
        }
    }

    /**
     * Tắt DoH của Chrome/Edge/Chromium qua registry policy. Mỗi lệnh chạy
     * độc lập — trình duyệt nào có key là được vô hiệu. Trả true nếu CẢ
     * 6 lệnh reg đều exit 0 (nghiêm ngặt để log có ý nghĩa).
     */
    boolean disableBrowserDoh() {
        if (!windowsOs) {
            return true; // non-Windows: trình duyệt dùng NSS/hosts bình thường
        }
        boolean allOk = true;
        for (String command : DOH_DISABLE_COMMANDS) {
            // reg add là lệnh shell — chạy qua cmd /c để hỗ trợ full line
            boolean ok = execute("cmd", "/c", command);
            allOk = allOk && ok;
        }
        if (allOk) {
            log.info("Browser DoH disabled via registry policies (Chromium/Chrome/Edge)");
        } else {
            log.warn("Some DoH registry policies failed — browser Secure DNS có thể còn hoạt động");
        }
        return allOk;
    }

    /** Xóa registry policy DoH (khôi phục hành vi mặc định của trình duyệt). */
    private void restoreBrowserDoh() {
        if (!windowsOs) {
            return;
        }
        for (String command : DOH_DISABLE_COMMANDS) {
            int vPos = command.indexOf(" /v ");
            int tPos = command.indexOf(" /t ", vPos);
            if (vPos < 0 || tPos < 0) {
                continue;
            }
            String keyPath = command.substring("reg add ".length(), vPos);
            String valueName = command.substring(vPos + 4, tPos);
            execute("cmd", "/c", "reg delete " + keyPath + " /v " + valueName + " /f");
        }
    }

    /**
     * Thêm firewall rule chặn outbound UDP 443 (QUIC/HTTP3) — ép trình duyệt
     * fallback về TCP where hosts enforcement có hiệu lực. LAN không ảnh
     * hưởng (rule chỉ outbound web).
     */
    boolean blockQuic() {
        if (!windowsOs) {
            return true;
        }
        boolean ok = execute("netsh", "advfirewall", "firewall", "add", "rule",
                "name=" + FIREWALL_RULE_NAME_QUIC, "dir=out", "action=block",
                "remoteport=443", "protocol=UDP");
        if (ok) {
            log.info("QUIC/HTTP3 (UDP 443) blocked by firewall rule '{}' — browsers fall back to TCP",
                    FIREWALL_RULE_NAME_QUIC);
        }
        return ok;
    }

    /** Xóa firewall rule chặn QUIC (idempotent — rule không tồn tại vẫn ok). */
    private void removeQuicBlock() {
        if (!windowsOs) {
            return;
        }
        execute("netsh", "advfirewall", "firewall", "delete", "rule",
                "name=" + FIREWALL_RULE_NAME_QUIC);
    }

    /**
     * Flush DNS resolver cache + NetBIOS name cache — kết nối MỚI giải mã
     * lại tên miền qua hosts file vừa ghi. Best-effort: lỗi chỉ log WARN.
     * Package-private để unit test đọc qua trực tiếp (không spawn thật).
     */
    void flushDnsAndNetbiosCache() {
        if (!windowsOs) {
            return;
        }
        execute("ipconfig", "/flushdns");
        execute("nbtstat", "-R");
    }

    /**
     * Thực thi 1 tiến trình hệ thống với đầy đủ phòng thủ (giống
     * {@link WindowsNetshEnforcer}): timeout + destroyForcibly, kiểm tra
     * exitCode, nuốt mọi exception — không bao giờ crash Client.
     */
    boolean execute(String... command) {
        if (!windowsOs) {
            return true;
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("java.io.tmpdir")));
            process = pb.start();

            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Command timed out after {}s: {}",
                        PROCESS_TIMEOUT_SECONDS, String.join(" ", command));
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Command exited with code {} (thiếu quyền Administrator?): {}",
                        exitCode, String.join(" ", command));
                return false;
            }
            return true;
        } catch (java.io.IOException e) {
            log.warn("Failed to start command ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (SecurityException e) {
            log.warn("SecurityManager blocked command execution: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            log.warn("Command execution interrupted: {}", String.join(" ", command));
            return false;
        } catch (RuntimeException e) {
            log.warn("Unexpected error executing command: {}", e.getMessage());
            return false;
        }
    }
}
