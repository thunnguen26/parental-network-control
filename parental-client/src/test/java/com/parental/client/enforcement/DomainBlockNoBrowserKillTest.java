package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REGRESSION TEST (Mở rộng 12 — "KHÔNG kill trình duyệt khi chặn tên miền"):
 * toàn bộ cơ chế chặn domain chỉ can thiệp TẦNG MẠNG — ghi hosts, flush DNS
 * ({@code ipconfig /flushdns}), DoH registry, QUIC firewall — và KHÔNG BAO
 * GIỜ gọi taskkill tiến trình trình duyệt (giữ nguyên các tab đang làm việc;
 * người dùng reload trang bị chặn sẽ thấy lỗi kết nối, các tab khác an toàn).
 *
 * <p>Hai lớp bảo vệ:</p>
 * <ol>
 *   <li><b>Hành vi</b>: bắt mọi lệnh hệ thống qua override seam
 *       ({@code flushDnsCache}/{@code execute}) — luồng chặn domain đầy đủ
 *       không được sinh ra lệnh taskkill/tasklist nào;</li>
 *   <li><b>Ngược đã mã nguồn (source contract)</b>: quét file .java của
 *       {@code HostsFileManager} + {@code BrowserBypassEnforcer} — KHÔNG được
 *       chứa chuỗi {@code taskkill}. Test này chặn ai đó sau này thêm kill
 *       trình duyệt vào luồng chặn domain. Chỉ chạy khi chạy qua Maven (thấy
 *       được thư mục src); môi trường khác → skip, không fail sai.</li>
 * </ol>
 */
class DomainBlockNoBrowserKillTest {

    @TempDir
    Path tempDir;

    /** HostsFileManager ghi nhận lần gọi flush thay vì spawn ipconfig thật. */
    private static final class CapturingHosts extends HostsFileManager {
        final List<String> invoked = new CopyOnWriteArrayList<>();

        CapturingHosts(Path file) {
            super(file);
        }

        @Override
        public boolean flushDnsCache() {
            invoked.add("ipconfig /flushdns");
            return true;
        }
    }

    /** BrowserBypassEnforcer bắt lệnh qua seam execute (giống test hiện có). */
    private static final class CapturingBypass extends BrowserBypassEnforcer {
        final List<String> commands = new CopyOnWriteArrayList<>();

        CapturingBypass() {
            super(true); // giả lập Windows — vẫn không spawn lệnh thật nhờ override
        }

        @Override
        boolean execute(String... command) {
            commands.add(String.join(" ", command));
            return true;
        }
    }

    @Test
    @DisplayName("applyBlockedDomains: ghi hosts + flush DNS — KHÔNG taskkill trình duyệt")
    void applyDomainsNeverKillsBrowser() throws Exception {
        CapturingHosts hosts = new CapturingHosts(tempDir.resolve("hosts"));

        assertTrue(hosts.applyBlockedDomains(List.of("youtube.com", "facebook.com")));

        String hostsContent = Files.readString(hosts.getHostsFilePath());
        assertTrue(hostsContent.contains("0.0.0.0  youtube.com"),
                "Hosts phải được ghi đúng (chặn thuần tầng mạng)");
        assertTrue(hostsContent.contains(HostsFileManager.MARKER_START),
                "Block phải nằm giữa marker");
        assertNoBrowserKill(hosts.invoked, "applyBlockedDomains");
        assertEquals(List.of("ipconfig /flushdns"), hosts.invoked,
                "Lệnh hệ thống duy nhất của luồng chặn domain là flush DNS");
    }

    @Test
    @DisplayName("clearBlocks: gỡ marker hosts — KHÔNG taskkill trình duyệt")
    void clearBlocksNeverKillsBrowser() throws Exception {
        CapturingHosts hosts = new CapturingHosts(tempDir.resolve("hosts"));
        assertTrue(hosts.applyBlockedDomains(List.of("tiktok.com")));
        hosts.invoked.clear();

        assertTrue(hosts.clearBlocks());

        assertFalse(Files.readString(hosts.getHostsFilePath())
                .contains(HostsFileManager.MARKER_START), "Marker block phải được gỡ");
        assertNoBrowserKill(hosts.invoked, "clearBlocks");
    }

    @Test
    @DisplayName("BrowserBypassEnforcer lock/unlock: chỉ reg + netsh — KHÔNG taskkill")
    void bypassEnforcerNeverKillsBrowser() {
        CapturingBypass bypass = new CapturingBypass();
        bypass.lockBypassPaths();
        bypass.unlockBypassPaths();

        assertNoBrowserKill(bypass.commands, "lockBypassPaths/unlockBypassPaths");
        // reg/netsh chạy qua "cmd /c <lệnh>" — kiểm tra bằng contains
        assertTrue(bypass.commands.stream().anyMatch(cmd -> cmd.contains("reg add")),
                "Khóa đường lách phải có lệnh reg add tắt DoH");
        assertTrue(bypass.commands.stream().anyMatch(cmd -> cmd.contains("reg delete")),
                "Mở khóa phải có lệnh reg delete gỡ policy DoH");
    }

    @Test
    @DisplayName("Luồng chặn domain ĐẦY ĐỦ (khóa bypass + hosts + mở khóa): 0 lệnh taskkill")
    void fullDomainBlockFlowNeverKillsBrowser() throws Exception {
        CapturingHosts hosts = new CapturingHosts(tempDir.resolve("hosts"));
        CapturingBypass bypass = new CapturingBypass();

        // Đúng thứ tự wiring trong ClientApp: khóa bypass TRƯỚC khi ghi hosts
        bypass.lockBypassPaths();
        assertTrue(hosts.applyBlockedDomains(List.of("somegame-site.com")));
        bypass.unlockBypassPaths();

        assertNoBrowserKill(hosts.invoked, "hosts flow");
        assertNoBrowserKill(bypass.commands, "bypass flow");
    }

    @Test
    @DisplayName("SOURCE CONTRACT: HostsFileManager + BrowserBypassEnforcer không chứa 'taskkill'")
    void sourceNeverMentionsTaskkill() throws Exception {
        for (String sourceFile : List.of(
                "src/main/java/com/parental/client/enforcement/HostsFileManager.java",
                "src/main/java/com/parental/client/enforcement/BrowserBypassEnforcer.java")) {
            Path path = Path.of(sourceFile);
            assumeTrue(Files.exists(path),
                    "Chạy qua Maven từ thư mục module mới quét được source: " + sourceFile);
            String source = Files.readString(path);
            assertFalse(source.toLowerCase(java.util.Locale.ROOT).contains("taskkill"),
                    sourceFile + " KHÔNG được gọi taskkill — chặn tên miền chỉ can thiệp "
                            + "tầng mạng (hosts/DoH/QUIC/flushdns), giữ tab trình duyệt sống");
        }
    }

    /** Không lệnh nào trong danh sách được phép là taskkill/tasklist. */
    private static void assertNoBrowserKill(List<String> commands, String phase) {
        for (String command : commands) {
            String lower = command.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("taskkill"),
                    phase + " KHÔNG được gọi taskkill (giữ tab trình duyệt sống), thấy: " + command);
            assertFalse(lower.contains("tasklist"),
                    phase + " KHÔNG cần tasklist — chặn domain thuần tầng mạng");
        }
    }
}
