package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bug B — dọn firewall rule sót lúc Client khởi động. Test tuyệt đối KHÔNG
 * chạy netsh thật: override seam {@code execute}/{@code executeQuiet}.
 */
class WindowsNetshStartupCleanupTest {

    private static final Set<String> PROJECT_RULES = Set.of(
            WindowsNetshEnforcer.FIREWALL_RULE_NAME_TCP,
            WindowsNetshEnforcer.FIREWALL_RULE_NAME_UDP,
            BrowserBypassEnforcer.FIREWALL_RULE_NAME_QUIC);

    /** Giả lập netsh: rule nào "tồn tại" thì show trả true; delete ghi nhận. */
    private static final class FakeEnforcer extends WindowsNetshEnforcer {
        final List<String> shows = new CopyOnWriteArrayList<>();
        final List<String> deletes = new CopyOnWriteArrayList<>();
        final Set<String> existing;
        final boolean deleteSucceeds;

        FakeEnforcer(Set<String> existing, boolean deleteSucceeds) {
            super(true);
            this.existing = existing;
            this.deleteSucceeds = deleteSucceeds;
        }

        @Override
        boolean executeQuiet(String... netshArgs) {
            shows.add(String.join(" ", netshArgs));
            String name = ruleName(netshArgs);
            return name != null && existing.contains(name);
        }

        @Override
        boolean execute(String... netshArgs) {
            deletes.add(String.join(" ", netshArgs));
            return deleteSucceeds;
        }
    }

    private static String ruleName(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("name=")) {
                return arg.substring("name=".length());
            }
        }
        return null;
    }

    @Test
    @DisplayName("Rule sót: chỉ xóa đúng 3 tên rule của project, không wildcard")
    void deletesOnlyExactProjectRules() {
        FakeEnforcer enforcer = new FakeEnforcer(PROJECT_RULES, true);

        enforcer.cleanupStaleRulesOnStartup();

        assertEquals(3, enforcer.shows.size());
        assertEquals(3, enforcer.deletes.size());
        List<String> deleteNames = new ArrayList<>();
        for (String command : enforcer.deletes) {
            assertTrue(command.contains("advfirewall firewall delete rule name="),
                    "Chỉ xóa bằng tên rule chính xác: " + command);
            assertFalse(command.contains("*"), "KHÔNG được dùng wildcard: " + command);
            String[] args = command.split(" ");
            String name = ruleName(args);
            assertTrue(PROJECT_RULES.contains(name), "Không đụng rule ngoài project: " + name);
            deleteNames.add(name);
        }
        assertEquals(PROJECT_RULES, Set.copyOf(deleteNames));
    }

    @Test
    @DisplayName("Chỉ TCP sót: chỉ xóa TCP, không đụng UDP/QUIC")
    void deletesOnlyExistingRule() {
        FakeEnforcer enforcer = new FakeEnforcer(
                Set.of(WindowsNetshEnforcer.FIREWALL_RULE_NAME_TCP), true);

        enforcer.cleanupStaleRulesOnStartup();

        assertEquals(1, enforcer.deletes.size());
        assertTrue(enforcer.deletes.get(0).endsWith(
                "name=" + WindowsNetshEnforcer.FIREWALL_RULE_NAME_TCP));
    }

    @Test
    @DisplayName("Không có rule sót: không chạy lệnh delete nào")
    void doesNothingWhenNoStaleRule() {
        FakeEnforcer enforcer = new FakeEnforcer(Set.of(), true);

        enforcer.cleanupStaleRulesOnStartup();

        assertEquals(3, enforcer.shows.size(), "Vẫn kiểm tra đủ 3 rule");
        assertTrue(enforcer.deletes.isEmpty());
    }

    @Test
    @DisplayName("Delete thất bại (thiếu quyền) không ném và vẫn thử rule kế tiếp")
    void deleteFailureIsLoggedAndContinues() {
        FakeEnforcer enforcer = new FakeEnforcer(PROJECT_RULES, false);

        enforcer.cleanupStaleRulesOnStartup();

        assertEquals(3, enforcer.deletes.size(), "Phải thử xóa cả 3 dù lệnh đầu fail");
    }

    @Test
    @DisplayName("Non-Windows: no-op hoàn toàn, không gọi netsh")
    void nonWindowsIsNoOp() {
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(false) {
            @Override
            boolean execute(String... netshArgs) {
                throw new AssertionError("Không được chạy netsh trên non-Windows");
            }

            @Override
            boolean executeQuiet(String... netshArgs) {
                throw new AssertionError("Không được chạy netsh trên non-Windows");
            }
        };

        enforcer.cleanupStaleRulesOnStartup(); // không ném
        assertEquals(NetworkEnforcer.STATUS_ACTIVE, enforcer.getNetworkStatus());
    }
}
