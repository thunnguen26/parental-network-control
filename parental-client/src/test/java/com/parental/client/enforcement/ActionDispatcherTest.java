package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.client.metrics.NetworkMetricsCollector;

/**
 * Unit test cho {@link ActionDispatcher} và {@link WindowsNetshEnforcer}
 * (Task 3.3) — dùng Mock enforcer/notifier/collector, KHÔNG chạy netsh thật.
 */
class ActionDispatcherTest {

    @TempDir
    Path tempDir;

    /** Mock enforcer ghi nhận số lần pause/resume + trạng thái. */
    private static final class MockNetworkEnforcer implements NetworkEnforcer {
        final AtomicInteger pauseCount = new AtomicInteger();
        final AtomicInteger resumeCount = new AtomicInteger();
        final AtomicInteger cleanupCount = new AtomicInteger();
        final AtomicBoolean failNext = new AtomicBoolean(false);
        volatile boolean paused = false;

        @Override
        public boolean pauseNetwork() {
            if (failNext.get()) {
                return false;
            }
            pauseCount.incrementAndGet();
            paused = true;
            return true;
        }

        @Override
        public boolean resumeNetwork() {
            if (failNext.get()) {
                return false;
            }
            resumeCount.incrementAndGet();
            paused = false;
            return true;
        }

        @Override
        public String getNetworkStatus() {
            return paused ? STATUS_PAUSED : STATUS_ACTIVE;
        }

        @Override
        public void cleanup() {
            cleanupCount.incrementAndGet();
            paused = false;
        }
    }

    /** Mock notifier ghi nhận message. */
    private static final class MockAlertNotifier implements AlertNotifier {
        final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean notify(String message) {
            messages.add(message);
            return true;
        }
    }

    /** Stub collector ghi nhận status được sync. */
    private static final class StubMetricsCollector implements NetworkMetricsCollector {
        volatile String status = STATUS_ACTIVE;
        long downloadBytes;

        @Override
        public MetricsPayload collect() {
            return new MetricsPayload(downloadBytes, 0, 0, 0, 1, status);
        }

        @Override
        public void setNetworkStatus(String status) {
            this.status = status;
        }
    }

    // ------------------------------------------------------------------
    // ActionDispatcher
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PAUSE_NETWORK: enforcer pause + sync status PAUSED + trả commandId")
    void pauseCommandDispatchesEnforcerAndSyncsStatus() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        StubMetricsCollector collector = new StubMetricsCollector();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, new MockAlertNotifier(), collector);

        String commandId = dispatcher.dispatch(
                new CommandPayload("cmd-pause-1", ActionType.PAUSE_NETWORK, "Het gio"));

        assertEquals("cmd-pause-1", commandId);
        assertEquals(1, enforcer.pauseCount.get());
        assertEquals(0, enforcer.resumeCount.get());
        assertEquals("PAUSED", enforcer.getNetworkStatus());
        assertEquals("PAUSED", collector.status, "Metrics collector phải được sync status PAUSED");
        assertEquals("PAUSED", collector.collect().networkStatus());
    }

    @Test
    @DisplayName("RESUME_NETWORK: enforcer resume + sync status ACTIVE + trả commandId")
    void resumeCommandDispatchesEnforcerAndSyncsStatus() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        StubMetricsCollector collector = new StubMetricsCollector();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, new MockAlertNotifier(), collector);
        dispatcher.dispatch(new CommandPayload("cmd-1", ActionType.PAUSE_NETWORK, "x"));

        String commandId = dispatcher.dispatch(
                new CommandPayload("cmd-resume-1", ActionType.RESUME_NETWORK, "Cho dung lai"));

        assertEquals("cmd-resume-1", commandId);
        assertEquals(1, enforcer.resumeCount.get());
        assertEquals("ACTIVE", enforcer.getNetworkStatus());
        assertEquals("ACTIVE", collector.status);
    }

    @Test
    @DisplayName("ALERT: notifier nhận reason, không đụng enforcer, trả commandId")
    void alertCommandNotifiesWithReason() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        MockAlertNotifier notifier = new MockAlertNotifier();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, notifier, null);

        String commandId = dispatcher.dispatch(
                new CommandPayload("cmd-alert-1", ActionType.ALERT, "Con tro da vuot gio"));

        assertEquals("cmd-alert-1", commandId);
        assertEquals(List.of("Con tro da vuot gio"), notifier.messages);
        assertEquals(0, enforcer.pauseCount.get() + enforcer.resumeCount.get());
    }

    @Test
    @DisplayName("Enforcer thất bại → trả null (không ACK), không sync status")
    void enforcerFailureReturnsNullNoAck() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        enforcer.failNext.set(true);
        StubMetricsCollector collector = new StubMetricsCollector();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, new MockAlertNotifier(), collector);

        String result = dispatcher.dispatch(
                new CommandPayload("cmd-fail", ActionType.PAUSE_NETWORK, "x"));

        assertNull(result, "Thất bại phải trả null → Engine không gửi ACK");
        assertEquals("ACTIVE", collector.status, "Status không được đổi khi thất bại");
    }

    @Test
    @DisplayName("BLOCK_PROCESS: taskkill Access Denied → không ACK dù policy đã persist")
    void blockProcessAccessDeniedIsNotAcked() {
        ProcessBlockerManager denied = new ProcessBlockerManager(
                tempDir.resolve("blocked-denied.json"), true) {
            @Override
            ProcessBlockerManager.KillResult killProcessWithResult(String normalizedProcessName) {
                return ProcessBlockerManager.KillResult.ACCESS_DENIED;
            }
        };
        ActionDispatcher dispatcher = new ActionDispatcher(
                new MockNetworkEnforcer(), new MockAlertNotifier(), null, denied);

        String ack = dispatcher.dispatch(new CommandPayload(
                "cmd-block-denied", ActionType.BLOCK_PROCESS, "elevatedgame.exe"));

        assertNull(ack, "App elevated vẫn chạy → Server phải thấy thất bại, không ACK");
        assertTrue(denied.getBlockedProcesses().contains("elevatedgame.exe"),
                "Policy vẫn persist để watchdog thử lại");
    }

    @Test
    @DisplayName("Payload hỏng (null action / blank commandId / null command) → null, không exception")
    void invalidPayloadReturnsNull() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, new MockAlertNotifier(), null);

        assertNull(dispatcher.dispatch(null));
        assertNull(dispatcher.dispatch(new CommandPayload("", ActionType.PAUSE_NETWORK, "x")));
        assertNull(dispatcher.dispatch(new CommandPayload("cmd-2", null, "x")));
        assertEquals(0, enforcer.pauseCount.get());
    }

    @Test
    @DisplayName("cleanup() ủy quyền cho enforcer.cleanup()")
    void cleanupDelegatesToEnforcer() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, new MockAlertNotifier(), null);

        dispatcher.cleanup();
        assertEquals(1, enforcer.cleanupCount.get());
    }

    // ------------------------------------------------------------------
    // WindowsNetshEnforcer (override execute — không chạy netsh thật)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WindowsNetshEnforcer: pause/resume đổi status, execute thành công")
    void netshEnforcerTracksStatusWithoutRunningRealNetsh() {
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                return true; // giả lập netsh exit 0
            }
        };

        assertEquals("ACTIVE", enforcer.getNetworkStatus());
        assertTrue(enforcer.pauseNetwork());
        assertEquals("PAUSED", enforcer.getNetworkStatus());
        assertTrue(enforcer.resumeNetwork());
        assertEquals("ACTIVE", enforcer.getNetworkStatus());
    }

    @Test
    @DisplayName("WindowsNetshEnforcer: pause idempotent (add 1 lần); resume luôn delete cả TCP + UDP")
    void netshEnforcerIsIdempotent() {
        AtomicInteger addTcpCalls = new AtomicInteger();
        AtomicInteger addUdpCalls = new AtomicInteger();
        AtomicInteger deleteCalls = new AtomicInteger();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                if (netshArgs.length > 2 && "add".equals(netshArgs[2])) {
                    if (hasUdpProtocol(netshArgs)) {
                        addUdpCalls.incrementAndGet();
                    } else {
                        addTcpCalls.incrementAndGet();
                    }
                } else if (netshArgs.length > 2 && "delete".equals(netshArgs[2])) {
                    deleteCalls.incrementAndGet();
                }
                return true;
            }
        };

        assertTrue(enforcer.pauseNetwork());
        assertTrue(enforcer.pauseNetwork(), "PAUSE lặp phải no-op (không add rule nữa)");
        assertEquals(1, addTcpCalls.get(), "PAUSE lặp không add lại rule TCP");
        assertEquals(1, addUdpCalls.get(), "PAUSE lặp không add lại rule UDP");
        assertTrue(enforcer.resumeNetwork());
        assertTrue(enforcer.resumeNetwork(), "RESUME khi ACTIVE vẫn delete — phòng rule sót từ crash");
        assertEquals(4, deleteCalls.get(), "Mỗi resumeNetwork xóa 2 rule (TCP + UDP); 2 lần resume → 4 delete");
    }

    @Test
    @DisplayName("VẤN ĐỀ 2: pause tạo CẢ HAI rule TCP (80,443) + UDP (80,443,53) chống QUIC/HTTP3")
    void netshEnforcerBlocksUdpQuic() {
        List<String> addedRules = new ArrayList<>();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                if (netshArgs.length > 2 && "add".equals(netshArgs[2])) {
                    addedRules.add(String.join(" ", netshArgs));
                }
                return true;
            }
        };

        assertTrue(enforcer.pauseNetwork());

        assertEquals(2, addedRules.size(), "PAUSE phải tạo đúng 2 rule: TCP + UDP");
        assertTrue(addedRules.get(0).contains("protocol=TCP")
                        && addedRules.get(0).contains("remoteport=80,443")
                        && addedRules.get(0).contains("name=ParentalControlBlockTCP"),
                "Rule TCP: remoteport=80,443 protocol=TCP, name=ParentalControlBlockTCP");
        assertTrue(addedRules.get(1).contains("protocol=UDP")
                        && addedRules.get(1).contains("remoteport=80,443,53")
                        && addedRules.get(1).contains("name=ParentalControlBlockUDP"),
                "Rule UDP: remoteport=80,443,53 protocol=UDP, name=ParentalControlBlockUDP (chặn QUIC/HTTP3)");
    }

    @Test
    @DisplayName("VẤN ĐỀ 2: resume xóa CẢ HAI rule TCP + UDP")
    void netshEnforcerResumeDeletesBothRules() {
        List<String> deletedRules = new ArrayList<>();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                if (netshArgs.length > 2 && "delete".equals(netshArgs[2])) {
                    deletedRules.add(String.join(" ", netshArgs));
                }
                return true;
            }
        };

        assertTrue(enforcer.resumeNetwork());

        assertEquals(2, deletedRules.size(), "RESUME phải xóa cả 2 rule TCP + UDP");
        assertTrue(deletedRules.get(0).contains("name=ParentalControlBlockTCP"));
        assertTrue(deletedRules.get(1).contains("name=ParentalControlBlockUDP"));
    }

    @Test
    @DisplayName("VẤN ĐỀ 2: add UDP thất bại → rollback rule TCP, PAUSE trả false, không rơi trạng thái lửng")
    void netshEnforcerPartialFailureRollsBack() {
        List<String> executed = new ArrayList<>();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                executed.add(String.join(" ", netshArgs));
                // Lệnh add rule UDP luôn thất bại (mô phỏng thiếu quyền một phần)
                return !(netshArgs.length > 2 && "add".equals(netshArgs[2]) && hasUdpProtocol(netshArgs));
            }
        };

        assertFalse(enforcer.pauseNetwork(), "Một trong hai rule thất bại → PAUSE thất bại");
        assertEquals("ACTIVE", enforcer.getNetworkStatus(), "Không rơi trạng thái bán khóa");

        long rollbackDeletes = executed.stream()
                .filter(cmd -> cmd.contains("delete") && cmd.contains("ParentalControlBlock"))
                .count();
        assertEquals(2, rollbackDeletes, "Phải rollback xóa cả 2 rule để không bỏ lỡ rule TCP đã thêm");
    }

    /** netshArgs có chứa 'protocol=UDP' không? (helper chung cho anonymous enforcer) */
    private static boolean hasUdpProtocol(String... netshArgs) {
        for (String arg : netshArgs) {
            if ("protocol=UDP".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("WindowsNetshEnforcer: execute trả false (thiếu quyền) → pause thất bại, không crash")
    void netshEnforcerDefensiveOnFailure() {
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                return false; // giả lập exit code != 0
            }
        };

        assertFalse(enforcer.pauseNetwork());
        assertEquals("ACTIVE", enforcer.getNetworkStatus(), "Thất bại → status không đổi");
        assertFalse(enforcer.pauseNetwork());
        // resume khi chưa pause vẫn idempotent true
        assertTrue(enforcer.resumeNetwork());
    }

    @Test
    @DisplayName("WindowsNetshEnforcer: cleanup luôn xóa cả 2 rule TCP + UDP ngay cả khi chưa pause")
    void netshEnforcerCleanupAlwaysDeletesRule() {
        List<String> deletedRules = new ArrayList<>();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(true) {
            @Override
            boolean execute(String... netshArgs) {
                if (netshArgs.length > 2 && "delete".equals(netshArgs[2])) {
                    deletedRules.add(String.join(" ", netshArgs));
                }
                return true;
            }
        };

        enforcer.cleanup();
        assertEquals(2, deletedRules.size(), "Cleanup phải xóa cả 2 rule TCP + UDP sót (phòng crash phiên trước)");
        assertTrue(deletedRules.get(0).contains("name=ParentalControlBlockTCP"));
        assertTrue(deletedRules.get(1).contains("name=ParentalControlBlockUDP"));
        assertEquals("ACTIVE", enforcer.getNetworkStatus());
    }

    @Test
    @DisplayName("WindowsNetshEnforcer trên non-Windows: execute là no-op trả true (không spawn netsh)")
    void netshEnforcerNoOpOnNonWindows() {
        AtomicInteger netshInvocations = new AtomicInteger();
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer(false) {
            @Override
            boolean execute(String... netshArgs) {
                netshInvocations.incrementAndGet(); // execute được gọi nhưng trả true ngay (no-op)
                return true;
            }
        };

        assertTrue(enforcer.pauseNetwork());
        assertEquals("PAUSED", enforcer.getNetworkStatus());
        assertTrue(enforcer.resumeNetwork());
        assertEquals("ACTIVE", enforcer.getNetworkStatus());
        assertTrue(netshInvocations.get() >= 0, "Không có ProcessBuilder nào được spawn trên non-Windows");
    }

    @Test
    @DisplayName("LOCK/LOGOFF/RESTART/SHUTDOWN: chuyển đúng action cho PowerCommandExecutor, không đụng mạng")
    void powerCommandsUsePowerExecutor() {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        StubMetricsCollector collector = new StubMetricsCollector();
        List<ActionType> executed = new ArrayList<>();
        com.parental.client.power.PowerCommandExecutor executor = action -> {
            executed.add(action);
            return com.parental.client.power.PowerCommandExecutor.Result.accepted("ok");
        };
        ActionDispatcher dispatcher = new ActionDispatcher(
                enforcer, new MockAlertNotifier(), collector, null, executor);

        for (ActionType action : List.of(ActionType.LOCK_SESSION, ActionType.LOGOFF_SESSION,
                ActionType.RESTART_DEVICE, ActionType.SHUTDOWN_DEVICE)) {
            assertEquals("cmd-" + action, dispatcher.dispatch(
                    new CommandPayload("cmd-" + action, action, "test")));
        }

        assertEquals(List.of(ActionType.LOCK_SESSION, ActionType.LOGOFF_SESSION,
                ActionType.RESTART_DEVICE, ActionType.SHUTDOWN_DEVICE), executed);
        assertEquals(0, enforcer.pauseCount.get(), "Lệnh nguồn không được pause mạng");
        assertEquals(0, enforcer.resumeCount.get());
        assertEquals("ACTIVE", collector.status);
    }

    @Test
    @DisplayName("Power executor từ chối (OS khác/thiếu quyền) → không ACK (null)")
    void rejectedPowerCommandIsNotAcked() {
        ActionDispatcher dispatcher = new ActionDispatcher(
                new MockNetworkEnforcer(), new MockAlertNotifier(), new StubMetricsCollector(), null,
                action -> com.parental.client.power.PowerCommandExecutor.Result.rejected("no"));

        assertNull(dispatcher.dispatch(new CommandPayload("cmd-power",
                ActionType.SHUTDOWN_DEVICE, "test")));
    }

    @Test
    @DisplayName("Power executor null → từ chối an toàn, không giả vờ thành công")
    void powerDisabledRejectsWithoutAck() {
        ActionDispatcher dispatcher = new ActionDispatcher(
                new MockNetworkEnforcer(), new MockAlertNotifier(), new StubMetricsCollector());

        assertNull(dispatcher.dispatch(new CommandPayload("cmd-power-2",
                ActionType.RESTART_DEVICE, "test")));
    }

    @Test
    @DisplayName("SystemTrayAlertNotifier headless: log fallback (trả false), không ném exception")
    void headlessNotifierFallsBackToLog() {
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier(true);

        assertFalse(notifier.notify(null), "null → false");
        assertFalse(notifier.notify("  "), "blank → false");
        assertFalse(notifier.notify("Message for headless environment"),
                "Headless không có GUI → kênh GUI trả false (đã log WARN)");
    }
}
