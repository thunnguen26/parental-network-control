package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression: PAUSE/RESUME không được tin exit code netsh. Test dùng seam
 * {@code execute}/{@code isRulePresent} — KHÔNG chạy netsh thật.
 */
class WindowsNetshRuleVerificationTest {

    private static final class FakeNetsh extends WindowsNetshEnforcer {
        final Set<String> rules = new LinkedHashSet<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        boolean addRegistersRule = true;
        boolean deleteWorks = true;

        FakeNetsh() {
            super(true, true);
        }

        @Override
        boolean execute(String... args) {
            String command = String.join(" ", args);
            commands.add(command);
            if (args.length > 2 && "add".equals(args[2])) {
                if (addRegistersRule) {
                    rules.add(ruleName(args));
                }
                return true;
            }
            if (args.length > 2 && "delete".equals(args[2])) {
                if (deleteWorks) {
                    rules.remove(ruleName(args));
                    return true;
                }
                return false;
            }
            return true;
        }

        @Override
        boolean isRulePresent(String ruleName) {
            return rules.contains(ruleName);
        }

        private static String ruleName(String[] args) {
            for (String arg : args) {
                if (arg.startsWith("name=")) {
                    return arg.substring("name=".length());
                }
            }
            return "";
        }
    }

    @Test
    @DisplayName("PAUSE: add exit 0 nhưng show rule không thấy → thất bại + rollback")
    void pauseFailsWhenRuleIsNotVisible() {
        FakeNetsh enforcer = new FakeNetsh();
        enforcer.addRegistersRule = false;

        assertFalse(enforcer.pauseNetwork(),
                "Exit code 0 không đủ; rule phải thực sự tồn tại");
        assertEquals(NetworkEnforcer.STATUS_ACTIVE, enforcer.getNetworkStatus());

        long deletes = enforcer.commands.stream()
                .filter(c -> c.contains(" delete "))
                .count();
        assertEquals(2, deletes, "Phải rollback cả TCP + UDP");
    }

    @Test
    @DisplayName("PAUSE: add + show đều thấy rule → PAUSED; RESUME xóa và xác minh đã mất")
    void pauseAndResumeVerifyRuleState() {
        FakeNetsh enforcer = new FakeNetsh();

        assertTrue(enforcer.pauseNetwork());
        assertEquals(NetworkEnforcer.STATUS_PAUSED, enforcer.getNetworkStatus());
        assertEquals(2, enforcer.rules.size());

        assertTrue(enforcer.resumeNetwork());
        assertEquals(NetworkEnforcer.STATUS_ACTIVE, enforcer.getNetworkStatus());
        assertTrue(enforcer.rules.isEmpty(), "Rule phải biến mất thật, không chỉ exit 0");
    }

    @Test
    @DisplayName("RESUME: delete báo lỗi và rule vẫn còn → giữ PAUSED, trả false (không ACK giả)")
    void resumeStaysPausedWhenRulesRemain() {
        FakeNetsh enforcer = new FakeNetsh();
        assertTrue(enforcer.pauseNetwork());

        enforcer.deleteWorks = false;

        assertFalse(enforcer.resumeNetwork());
        assertEquals(NetworkEnforcer.STATUS_PAUSED, enforcer.getNetworkStatus(),
                "Không được chuyển ACTIVE khi firewall vẫn chặn");
    }

    @Test
    @DisplayName("RESUME khi chưa PAUSE: rule sót được xóa thật rồi mới trả true")
    void resumeRemovesStaleRulesWhenNotPaused() {
        FakeNetsh enforcer = new FakeNetsh();
        enforcer.rules.add(WindowsNetshEnforcer.FIREWALL_RULE_NAME_TCP);
        enforcer.rules.add(WindowsNetshEnforcer.FIREWALL_RULE_NAME_UDP);

        assertTrue(enforcer.resumeNetwork());
        assertTrue(enforcer.rules.isEmpty());
        assertEquals(NetworkEnforcer.STATUS_ACTIVE, enforcer.getNetworkStatus());
    }
}
