package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test {@link BrowserBypassEnforcer} — vô hiệu hóa DoH/QUIC lách hosts.
 * Chạy với {@code windowsOs=false} (no-op an toàn, không spawn lệnh thật) +
 * bắt lệnh qua subclass override {@code execute} (package-private — test cùng
 * package). Kiểm tra: đúng bộ lệnh registry DoH (Chromium/Chrome/Edge, đủ
 * {@code DnsOverHttpsMode=off}), đúng rule QUIC UDP 443, flush cả 2 cache,
 * defensive không ném exception, non-Windows no-op trả true.
 */
class BrowserBypassEnforcerTest {

    /** Bắt lệnh thay vì chạy thật — ghi lại để assert. */
    private static final class RecordingEnforcer extends BrowserBypassEnforcer {
        final List<String> executed = new CopyOnWriteArrayList<>();

        RecordingEnforcer() {
            super(true); // giả lập Windows — vẫn không spawn lệnh thật nhờ override
        }

        @Override
        boolean execute(String... command) {
            executed.add(String.join(" ", command));
            return true;
        }
    }

    @Test
    @DisplayName("disableBrowserDoh: đủ 6 lệnh reg add — DnsOverHttpsMode=off cho Chromium/Chrome/Edge")
    void dohDisableCommandsCoverAllBrowsers() {
        RecordingEnforcer enforcer = new RecordingEnforcer();

        assertTrue(enforcer.disableBrowserDoh());

        String joined = String.join("\n", enforcer.executed);
        assertTrue(joined.contains("HKLM\\SOFTWARE\\Policies\\Chromium"),
                "Chromium chung (Cốc Cốc build Chromium) phải có key policy");
        assertTrue(joined.contains("HKLM\\SOFTWARE\\Policies\\Google\\Chrome"),
                "Google Chrome phải có key policy");
        assertTrue(joined.contains("HKLM\\SOFTWARE\\Policies\\Microsoft\\Edge"),
                "Microsoft Edge phải có key policy");
        assertEquals(6, enforcer.executed.size(), "3 trình duyệt x 2 giá trị (mode + builtin client)");
        assertEquals(3, countOccurrences(joined, "DnsOverHttpsMode"),
                "Mỗi trình duyệt (3) đều có DnsOverHttpsMode=off");
        assertEquals(3, countOccurrences(joined, "BuiltInDnsClientEnabled"),
                "Mỗi trình duyệt (3) đều tắt built-in DNS client (Secure DNS tự chế)");
    }

    @Test
    @DisplayName("blockQuic: đúng rule netsh chặn outbound UDP 443 (QUIC/HTTP3)")
    void quicBlockUsesUdp443Rule() {
        RecordingEnforcer enforcer = new RecordingEnforcer();

        assertTrue(enforcer.blockQuic());

        assertEquals(1, enforcer.executed.size());
        String command = enforcer.executed.get(0);
        assertTrue(command.startsWith("netsh advfirewall firewall add rule"), "Lệnh netsh add rule");
        assertTrue(command.contains("name=" + BrowserBypassEnforcer.FIREWALL_RULE_NAME_QUIC));
        assertTrue(command.contains("remoteport=443"), "Đúng cổng 443");
        assertTrue(command.contains("protocol=UDP"), "Giao thức UDP (QUIC/HTTP3)");
        assertTrue(command.contains("action=block") && command.contains("dir=out"));
    }

    @Test
    @DisplayName("flushDnsAndNetbiosCache: gọi CẢ ipconfig /flushdns + nbtstat -R")
    void flushCoversDnsAndNetbios() {
        RecordingEnforcer enforcer = new RecordingEnforcer();

        enforcer.flushDnsAndNetbiosCache();

        assertEquals(2, enforcer.executed.size());
        assertEquals("ipconfig /flushdns", enforcer.executed.get(0));
        assertEquals("nbtstat -R", enforcer.executed.get(1));
    }

    @Test
    @DisplayName("lockBypassPaths: khóa đủ DoH (6) + QUIC (1) + cache (2) = 9 lệnh theo thứ tự")
    void lockBypassPathsExecutesFullSequence() {
        RecordingEnforcer enforcer = new RecordingEnforcer();

        assertTrue(enforcer.lockBypassPaths());

        assertEquals(9, enforcer.executed.size(), "6 reg add + 1 netsh QUIC + 2 cache flush");
        assertTrue(enforcer.executed.get(6).startsWith("netsh"), "QUIC sau DoH");
        assertEquals("ipconfig /flushdns", enforcer.executed.get(7), "Cache flush sau cùng");
        assertEquals("nbtstat -R", enforcer.executed.get(8));
    }

    @Test
    @DisplayName("unlockBypassPaths: xóa rule QUIC + reg delete đủ 6 giá trị DoH")
    void unlockRemovesQuicRuleAndDohPolicies() {
        RecordingEnforcer enforcer = new RecordingEnforcer();

        enforcer.unlockBypassPaths();

        String joined = String.join("\n", enforcer.executed);
        assertTrue(joined.contains("netsh advfirewall firewall delete rule "
                + "name=" + BrowserBypassEnforcer.FIREWALL_RULE_NAME_QUIC), "Gỡ rule QUIC");
        assertEquals(6, countOccurrences(joined, "reg delete"),
                "reg delete cho từng giá trị DoH (3 trình duyệt x 2 value)");
        assertFalse(joined.contains("reg add"), "Unlock KHÔNG được ghi thêm policy mới");
    }

    @Test
    @DisplayName("Lệnh bị từ chối (execute=false) → lockBypassPaths vẫn chạy đủ, không ném")
    void failedCommandsNeverThrow() {
        BrowserBypassEnforcer enforcer = new BrowserBypassEnforcer(true) {
            @Override
            boolean execute(String... command) {
                return false; // mô phỏng thiếu quyền Administrator
            }
        };

        assertFalse(enforcer.lockBypassPaths(), "Không khóa được gì → false (log WARN)");
        assertFalse(enforcer.disableBrowserDoh());
        assertFalse(enforcer.blockQuic());
    }

    @Test
    @DisplayName("Non-Windows: mọi thao tác no-op an toàn trả true (không spawn lệnh)")
    void nonWindowsIsSafeNoOp() {
        BrowserBypassEnforcer enforcer = new BrowserBypassEnforcer(false);

        assertTrue(enforcer.lockBypassPaths());
        assertTrue(enforcer.disableBrowserDoh());
        assertTrue(enforcer.blockQuic());
        enforcer.unlockBypassPaths(); // không ném
        enforcer.flushDnsAndNetbiosCache(); // không ném
    }

    @Test
    @DisplayName("DOH_DISABLE_COMMANDS: mỗi lệnh đều reg add /f với /d off hoặc /d 0")
    void dohCommandShapesAreValid() {
        for (String command : BrowserBypassEnforcer.DOH_DISABLE_COMMANDS) {
            assertTrue(command.startsWith("reg add HKLM\\SOFTWARE\\Policies\\"),
                    "Chỉ ghi HKLM policy key: " + command);
            assertTrue(command.endsWith("/f"), "Ghi đè không hỏi: " + command);
            assertTrue(command.contains("/d off") || command.contains("/d 0"),
                    "Giá trị tắt DoH: " + command);
        }
        assertEquals(6, BrowserBypassEnforcer.DOH_DISABLE_COMMANDS.size());
    }

    private static long countOccurrences(String content, String needle) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
