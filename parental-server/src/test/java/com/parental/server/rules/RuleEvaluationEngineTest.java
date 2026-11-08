package com.parental.server.rules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.db.ClientDao;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.CommandLogDao;
import com.parental.server.db.DatabaseManager;
import com.parental.server.db.MetricsDao;
import com.parental.server.db.RuleDao;
import com.parental.server.db.RuleDao.RuleRecord;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientSessionManager;

/**
 * Unit test Task 4.2 — {@link RuleEvaluationEngine}:
 * <ul>
 *   <li>So khớp khung giờ {@code isWithinAllowedSchedule} với nhiều case biên
 *       (ban ngày, qua đêm, rỗng, format hỏng, biên đầu/cuối);</li>
 *   <li>{@code hasActiveRule} unconfigured;</li>
 *   <li>Vòng đánh giá bằng "session giả" không socket (ClientSession hỗ trợ
 *       socket null cho unit test) — vi phạm khung giờ/thời gian/dung lượng,
 *       chống spam lệnh, RESUME khi nới lỏng/gỡ rule, unconfigured bỏ qua,
 *       pre-warning ALERT đúng 1 lần, RESUME không lặp, offline cleanup, lệnh
 *       gửi hỏng không khóa, exception DAO không chết vòng quét, lifecycle
 *       start/stop, ghi command_logs SENT.</li>
 * </ul>
 *
 * <p>Không mở socket thật — {@code FakeClientSession} ghi đè {@code send()}
 * để ghi nhận lệnh; DB dùng {@code :memory:} riêng từng test (unit
 * single-thread). Lưu ý FK: rule/metrics chỉ ghi được sau khi client tồn tại
 * trong bảng {@code clients} (harness {@code registerClient}).</p>
 */
class RuleEvaluationEngineTest {

    // ==================================================================
    // isWithinAllowedSchedule — nhiều case biên
    // ==================================================================

    @Test
    @DisplayName("Khung giờ thường 08:00-22:00: trong khung → true")
    void scheduleNormalWithin() {
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(12, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(8, 0)));   // biên đầu
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(21, 59)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(10, 30)));
    }

    @Test
    @DisplayName("Khung giờ thường 08:00-22:00: ngoài khung → false")
    void scheduleNormalOutside() {
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(6, 0)));
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(23, 0)));
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(22, 0))); // biên cuối (nửa khỏang [start,end))
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(7, 59)));
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "22:00", LocalTime.of(0, 0)));
    }

    @Test
    @DisplayName("Khung giờ qua đêm 22:00-06:00: 23:30/02:00/00:00/biên đầu → true; 12:00/biên cuối → false")
    void scheduleOvernight() {
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(23, 30)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(2, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(0, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(5, 59)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(22, 0)));  // biên đầu
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(12, 0)));
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(6, 0)));  // biên cuối
        assertFalse(RuleEvaluationEngine.isWithinAllowedSchedule("22:00", "06:00", LocalTime.of(21, 59)));
    }

    @Test
    @DisplayName("Khung giờ rỗng/null/format hỏng → fail-open (không giới hạn giờ); 00:00-00:00 = cả ngày")
    void scheduleUnconfiguredOrBroken() {
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule(null, null, LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("", "", LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("  ", "08:00", LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", null, LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("25:99", "08:00", LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("abc", "08:00", LocalTime.of(3, 0)));
        assertTrue(RuleEvaluationEngine.isWithinAllowedSchedule("08:00", "08:00", LocalTime.of(3, 0)));
    }

    // ==================================================================
    // hasActiveRule — unconfigured
    // ==================================================================

    @Test
    @DisplayName("hasActiveRule: null/đủ 0/rỗng giờ → false; đủ 1 hạn mức hoặc khung giờ → true")
    void hasActiveRuleCases() {
        assertFalse(RuleEvaluationEngine.hasActiveRule(null));
        assertFalse(RuleEvaluationEngine.hasActiveRule(new RuleRecord("c1", 0, 0, null, null)));
        assertFalse(RuleEvaluationEngine.hasActiveRule(new RuleRecord("c1", 0, 0, "", "  ")));
        assertTrue(RuleEvaluationEngine.hasActiveRule(new RuleRecord("c1", 3600, 0, null, null)));
        assertTrue(RuleEvaluationEngine.hasActiveRule(new RuleRecord("c1", 0, 1_000_000, null, null)));
        assertTrue(RuleEvaluationEngine.hasActiveRule(new RuleRecord("c1", 0, 0, "08:00", "22:00")));
    }

    // ==================================================================
    // Evaluation loop — session giả không socket
    // ==================================================================

    /** Session giả: không socket, ghi nhận mọi lệnh COMMAND được gửi. */
    private static class FakeClientSession extends ClientSession {
        final List<BaseMessage<?>> sent;

        FakeClientSession(String clientId) {
            this(clientId, new ArrayList<>());
        }

        FakeClientSession(String clientId, List<BaseMessage<?>> sent) {
            super(clientId, null, null, null, new JsonProtocolParser());
            this.sent = sent;
        }

        @Override
        public boolean send(BaseMessage<?> message) {
            sent.add(message);
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static BaseMessage<CommandPayload> lastCommand(List<BaseMessage<?>> sent) {
        return sent.isEmpty() ? null : (BaseMessage<CommandPayload>) sent.get(sent.size() - 1);
    }

    private static long countCommands(List<BaseMessage<?>> sent, ActionType action) {
        return sent.stream()
                .filter(m -> m.getType() == MessageType.COMMAND)
                .map(m -> (CommandPayload) m.getPayload())
                .filter(p -> p.action() == action)
                .count();
    }

    /** Bộ dựng dùng chung: DB memory riêng + DAO + session manager + engine mặc định. */
    private record Harness(DatabaseManager db, ClientDao clientDao, RuleDao ruleDao,
                           MetricsDao metricsDao, CommandLogDao commandLogDao,
                           ClientSessionManager sessions, RuleEvaluationEngine engine) {

        static Harness create() {
            DatabaseManager db = new DatabaseManager();
            ClientDao clientDao = new ClientDao(db);
            RuleDao ruleDao = new RuleDao(db);
            MetricsDao metricsDao = new MetricsDao(db);
            CommandLogDao commandLogDao = new CommandLogDao(db);
            ClientSessionManager sessions = new ClientSessionManager();
            RuleEvaluationEngine engine = new RuleEvaluationEngine(
                    sessions, ruleDao, metricsDao, commandLogDao, 1, () -> LocalTime.of(12, 0));
            return new Harness(db, clientDao, ruleDao, metricsDao, commandLogDao, sessions, engine);
        }

        /** FK: rule/metrics chỉ ghi được khi client đã tồn tại trong bảng clients. */
        void registerClient(String clientId) {
            clientDao.upsertClient(new ClientRecord(clientId, "PC-Test", "DESKTOP",
                    "Windows 11", "127.0.0.1", clientId, "ONLINE"));
        }
    }

    @Test
    @DisplayName("Vi phạm khung giờ (23:00 ngoài 08:00-22:00) → PAUSE 1 lần, chu kỳ sau không spam")
    void violationOutsideSchedulePausesOnce() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-01");
        h.sessions().register(session);
        h.registerClient("kid-01");
        h.ruleDao().upsertRule("kid-01", new ConfigSyncPayload(List.of(), 0, 0, "08:00", "22:00"));

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), h.ruleDao(), h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(23, 0));
        engine.evaluateAll();
        engine.evaluateAll(); // chu kỳ 2: đã khóa → không gửi thêm lệnh

        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK));
        assertTrue(h.commandLogDao().countByStatus(CommandLogDao.STATUS_SENT) >= 1);
        BaseMessage<CommandPayload> cmd = lastCommand(session.sent);
        assertNotNull(cmd);
        assertEquals(ActionType.PAUSE_NETWORK, cmd.getPayload().action());
        assertEquals(RuleEvaluationEngine.REASON_TIME_SCHEDULE, cmd.getPayload().reason());
        assertNotNull(cmd.getPayload().commandId());
        assertTrue(engine.isEnforced("kid-01"));
    }

    @Test
    @DisplayName("Hết vi phạm (nới lỏng rule) → RESUME đúng 1 lần; chu kỳ sau không lặp")
    void resumeWhenViolationClears() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-02");
        h.sessions().register(session);
        h.registerClient("kid-02");
        h.ruleDao().upsertRule("kid-02", new ConfigSyncPayload(List.of(), 3600, 0, null, null));

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), h.ruleDao(), h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 7200, "ACTIVE"));
        engine.evaluateAll(); // 7200 > 3600 → PAUSE
        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK));

        // Phụ huynh nới lỏng 3600 → 9000: hết vi phạm → RESUME đúng 1 lần
        h.ruleDao().upsertRule("kid-02", new ConfigSyncPayload(List.of(), 9000, 0, null, null));
        engine.evaluateAll();
        engine.evaluateAll(); // đã gỡ khóa → không RESUME lặp

        assertEquals(1, countCommands(session.sent, ActionType.RESUME_NETWORK));
        assertFalse(engine.isEnforced("kid-02"));
    }

    @Test
    @DisplayName("Phụ huynh GỠ rule khi client đang bị khóa → RESUME ngay")
    void resumeWhenRuleRemoved() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-03");
        h.sessions().register(session);
        h.registerClient("kid-03");
        h.metricsDao().insert("kid-03", new MetricsPayload(2_000, 0, 0, 0, 0, "ACTIVE"));

        // DAO giả lập "rule bị gỡ" sau khi PAUSE (RuleDao không có delete — không scope-creep)
        class RemovableRuleDao extends RuleDao {
            boolean removed = false;
            RemovableRuleDao() { super(h.db()); }
            @Override
            public RuleRecord getRule(String clientId) {
                return removed ? null : super.getRule(clientId);
            }
        }
        RemovableRuleDao ruleDao = new RemovableRuleDao();
        ruleDao.upsertRule("kid-03", new ConfigSyncPayload(List.of(), 0, 1_000, null, null));

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), ruleDao, h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));
        engine.evaluateAll(); // 2000 > 1000 → PAUSE
        assertTrue(engine.isEnforced("kid-03"));
        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK));

        ruleDao.removed = true; // rule biến mất (phụ huynh gỡ cấu hình)
        engine.evaluateAll();
        assertEquals(1, countCommands(session.sent, ActionType.RESUME_NETWORK));
        assertFalse(engine.isEnforced("kid-03"));
    }

    @Test
    @DisplayName("Client không có rule / rule unconfigured (đủ 0) → không bao giờ can thiệp")
    void unconfiguredClientIgnored() {
        Harness h = Harness.create();
        FakeClientSession noRule = new FakeClientSession("kid-04");
        FakeClientSession zeroRule = new FakeClientSession("kid-05");
        h.sessions().register(noRule);
        h.sessions().register(zeroRule);
        h.registerClient("kid-05");
        h.ruleDao().upsertRule("kid-05", new ConfigSyncPayload(List.of(), 0, 0, null, null));

        h.engine().evaluateAll();
        h.engine().evaluateAll();

        assertTrue(noRule.sent.isEmpty());
        assertTrue(zeroRule.sent.isEmpty());
    }

    @Test
    @DisplayName("Vi phạm dung lượng (tổng DB hôm nay > quota) → PAUSE với reason quota")
    void trafficQuotaViolationPauses() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-06");
        h.sessions().register(session);
        h.registerClient("kid-06");
        h.ruleDao().upsertRule("kid-06", new ConfigSyncPayload(List.of(), 0, 1_000_000, null, null));
        h.metricsDao().insert("kid-06", new MetricsPayload(700_000, 400_000, 0, 0, 0, "ACTIVE"));

        h.engine().evaluateAll();

        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK));
        assertEquals(RuleEvaluationEngine.REASON_TRAFFIC_QUOTA, lastCommand(session.sent).getPayload().reason());
    }

    @Test
    @DisplayName("Pre-warning ALERT: còn ≤ 5 phút → ALERT ĐÚNG 1 lần trước PAUSE; không lặp")
    void preWarningAlertSentOnce() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-07");
        h.sessions().register(session);
        h.registerClient("kid-07");
        h.ruleDao().upsertRule("kid-07", new ConfigSyncPayload(List.of(), 3900, 0, null, null)); // 65 phút

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), h.ruleDao(), h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));

        // Lượt 1: đã dùng 3600s → còn 300s (= 5 phút, biên trên) → ALERT trước, chưa PAUSE
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 3600, "ACTIVE"));
        engine.evaluateAll();
        assertEquals(1, countCommands(session.sent, ActionType.ALERT));
        assertEquals(0, countCommands(session.sent, ActionType.PAUSE_NETWORK));

        // Lượt 2: dùng 4200s > 3900s → PAUSE (ALERT không lặp)
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 4200, "ACTIVE"));
        engine.evaluateAll();
        assertEquals(1, countCommands(session.sent, ActionType.ALERT));
        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK));
    }

    @Test
    @DisplayName("Client cũ (không sessionStartTimestamp) restart → hạn mức thời gian KHÔNG reset")
    void legacyClientRestartKeepsDailyUsage() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-legacy");
        h.sessions().register(session);
        h.registerClient("kid-legacy");
        h.ruleDao().upsertRule("kid-legacy", new ConfigSyncPayload(List.of(), 3600, 0, null, null));

        // Phiên cũ (client cũ: sessionStartTimestamp = 0) đã dùng 2 giờ.
        h.metricsDao().insert("kid-legacy", new MetricsPayload(0, 0, 0, 0, 7_200, "ACTIVE"));
        // Client restart → duration của tiến trình mới chỉ 60s (bộ đếm reset).
        h.metricsDao().insert("kid-legacy", new MetricsPayload(0, 0, 0, 0, 60, "ACTIVE"));
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 60, "ACTIVE"));

        h.engine().evaluateAll();

        assertEquals(1, countCommands(session.sent, ActionType.PAUSE_NETWORK),
                "Tổng 7.260s trong ngày > 3.600s → phải PAUSE dù phiên mới chỉ 60s");
        assertEquals(RuleEvaluationEngine.REASON_USAGE_LIMIT,
                lastCommand(session.sent).getPayload().reason());
    }

    @Test
    @DisplayName("Không vi phạm + chưa từng khóa → không gửi bất kỳ lệnh nào")
    void noViolationNoCommands() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-08");
        h.sessions().register(session);
        h.registerClient("kid-08");
        h.ruleDao().upsertRule("kid-08", new ConfigSyncPayload(List.of(), 3600, 1_000_000, "08:00", "22:00"));
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 100, "ACTIVE"));

        h.engine().evaluateAll();
        h.engine().evaluateAll();

        assertTrue(session.sent.isEmpty());
        assertFalse(h.engine().isEnforced("kid-08"));
    }

    @Test
    @DisplayName("PAUSE gửi hỏng (send=false) → không khóa/không ghi DB, chu kỳ sau thử lại")
    void failedSendRetriesNextCycle() {
        Harness h = Harness.create();
        List<BaseMessage<?>> sent = new ArrayList<>();
        FakeClientSession session = new FakeClientSession("kid-09", sent) {
            @Override
            public boolean send(BaseMessage<?> message) {
                sent.add(message);
                return false; // mô phỏng mạng đứt
            }
        };
        h.sessions().register(session);
        h.registerClient("kid-09");
        h.ruleDao().upsertRule("kid-09", new ConfigSyncPayload(List.of(), 3600, 0, null, null));
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 7200, "ACTIVE"));

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), h.ruleDao(), h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));
        engine.evaluateAll();
        engine.evaluateAll();

        assertFalse(engine.isEnforced("kid-09"));
        assertEquals(0, h.commandLogDao().countByStatus(CommandLogDao.STATUS_SENT));
        assertEquals(2, countCommands(session.sent, ActionType.PAUSE_NETWORK)); // thử lại mỗi chu kỳ
    }

    @Test
    @DisplayName("Client offline → trạng thái can thiệp bị dọn (isEnforced=false)")
    void offlineCleanup() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-10");
        h.sessions().register(session);
        h.registerClient("kid-10");
        h.ruleDao().upsertRule("kid-10", new ConfigSyncPayload(List.of(), 3600, 0, null, null));
        session.setLastMetrics(new MetricsPayload(0, 0, 0, 0, 7200, "ACTIVE"));

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), h.ruleDao(), h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));
        engine.evaluateAll();
        assertTrue(engine.isEnforced("kid-10"));

        h.sessions().remove("kid-10"); // client rời mạng
        engine.evaluateAll();          // lượt quét sau phải dọn trạng thái
        assertFalse(engine.isEnforced("kid-10"));
        assertTrue(engine.getEnforcedClients().isEmpty());
    }

    @Test
    @DisplayName("DAO ném RuntimeException → evaluateAll nuốt, vòng quét không chết")
    void evaluationSurvivesDaoFailure() {
        Harness h = Harness.create();
        RuleDao brokenRuleDao = new RuleDao(h.db()) {
            @Override
            public RuleRecord getRule(String clientId) {
                throw new IllegalStateException("DB down");
            }
        };
        FakeClientSession session = new FakeClientSession("kid-11");
        h.sessions().register(session);

        RuleEvaluationEngine engine = new RuleEvaluationEngine(
                h.sessions(), brokenRuleDao, h.metricsDao(), h.commandLogDao(), 1, () -> LocalTime.of(12, 0));
        assertDoesNotThrow(engine::evaluateAll);
        assertDoesNotThrow(engine::evaluateAll);
        assertTrue(session.sent.isEmpty());
    }

    @Test
    @DisplayName("Lifecycle: start() → isRunning + thread 'parental-server-rule-engine' daemon; stop() dừng hẳn; idempotent")
    void lifecycleStartStop() {
        Harness h = Harness.create();
        RuleEvaluationEngine engine = h.engine();
        assertFalse(engine.isRunning());

        engine.start();
        engine.start(); // idempotent
        assertTrue(engine.isRunning());

        Thread engineThread = threadByName("parental-server-rule-engine");
        assertNotNull(engineThread, "Scheduler phải chạy trên thread có tên đúng");
        assertTrue(engineThread.isDaemon());

        engine.stop();
        engine.stop(); // idempotent
        assertFalse(engine.isRunning());
    }

    private static Thread threadByName(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }
}
