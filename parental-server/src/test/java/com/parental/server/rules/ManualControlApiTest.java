package com.parental.server.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * Unit test Task 5.2 — Manual Control API của {@link RuleEvaluationEngine}:
 * {@code manualPause}/{@code manualResume}/{@code sendAlert}/
 * {@code syncConfigToClient}/{@code releaseEnforcement} — FakeClientSession
 * (không socket, ghi đè {@code send}) + DB :memory: riêng từng test; mô phỏng
 * offline (không register session), send hỏng (acceptSend=false) và tương tác
 * với vòng quét tự động.
 */
class ManualControlApiTest {

    // ==================================================================
    // Fake session + harness (mẫu RuleEvaluationEngineTest)
    // ==================================================================

    /** Session giả: không socket, ghi nhận lệnh; acceptSend=false mô phỏng mạng đứt. */
    private static class FakeClientSession extends ClientSession {
        final List<BaseMessage<?>> sent = new ArrayList<>();
        private final boolean acceptSend;

        FakeClientSession(String clientId) {
            this(clientId, true);
        }

        FakeClientSession(String clientId, boolean acceptSend) {
            super(clientId, null, null, null, new JsonProtocolParser());
            this.acceptSend = acceptSend;
        }

        @Override
        public boolean send(BaseMessage<?> message) {
            sent.add(message);
            return acceptSend;
        }
    }

    /** Bộ dựng: DB memory riêng + DAO + manager + engine Clock cố định 12:00. */
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

        /** FK: rule/metrics chỉ ghi được khi client tồn tại trong bảng clients. */
        void registerClient(String clientId) {
            clientDao.upsertClient(new ClientRecord(clientId, "PC-" + clientId, "DESKTOP",
                    "Windows 11", "127.0.0.1", clientId, "ONLINE"));
        }
    }

    private static long countMessages(List<BaseMessage<?>> sent, MessageType type, ActionType action) {
        return sent.stream()
                .filter(m -> m.getType() == type)
                .filter(m -> !(m.getPayload() instanceof CommandPayload p) || p.action() == action)
                .count();
    }

    // ==================================================================
    // manualPause
    // ==================================================================

    @Test
    @DisplayName("manualPause: client online → PAUSE + SENT trong DB + đánh dấu enforced")
    void manualPauseOnline() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m1");
        h.sessions().register(session);
        h.registerClient("kid-m1");

        String commandId = h.engine().manualPause("kid-m1", "Tạm dừng thủ công từ phụ huynh");

        assertNotNull(commandId);
        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
        assertEquals("SENT", h.commandLogDao().getStatus(commandId));
        assertTrue(h.engine().isEnforced("kid-m1"));
        CommandPayload payload = (CommandPayload) session.sent.get(0).getPayload();
        assertEquals("Tạm dừng thủ công từ phụ huynh", payload.reason());
    }

    @Test
    @DisplayName("manualPause: reason null/blank → dùng reason mặc định")
    void manualPauseDefaultReason() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m2");
        h.sessions().register(session);
        h.registerClient("kid-m2");

        String commandId = h.engine().manualPause("kid-m2", "  ");

        assertNotNull(commandId);
        CommandPayload payload = (CommandPayload) session.sent.get(0).getPayload();
        assertEquals("Manual pause by parent", payload.reason());
    }

    @Test
    @DisplayName("manualPause: client offline → null, không gửi, không ghi DB, không enforced")
    void manualPauseOffline() {
        Harness h = Harness.create();
        h.registerClient("kid-m3"); // chỉ có trong DB, không có session → offline

        String commandId = h.engine().manualPause("kid-m3", "test");

        assertNull(commandId);
        assertEquals(0, h.commandLogDao().countByStatus(CommandLogDao.STATUS_SENT));
        assertFalse(h.engine().isEnforced("kid-m3"));
    }

    @Test
    @DisplayName("manualPause: send hỏng → null, KHÔNG ghi DB, KHÔNG enforced")
    void manualPauseSendFailed() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m4", false);
        h.sessions().register(session);
        h.registerClient("kid-m4");

        String commandId = h.engine().manualPause("kid-m4", "test");

        assertNull(commandId);
        assertEquals(0, h.commandLogDao().countByStatus(CommandLogDao.STATUS_SENT));
        assertFalse(h.engine().isEnforced("kid-m4"));
        assertFalse(h.engine().isManuallyEnforced("kid-m4"), "Send hỏng → không đánh dấu manual enforced");
    }

    @Test
    @DisplayName("VẤN ĐỀ 1: manualPause khi hạn mức HỢP LỆ → vòng quét KHÔNG tự RESUME (bỏ qua client)")
    void manualPauseNotUnpausedByAutoEngine() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m13");
        h.sessions().register(session);
        h.registerClient("kid-m13");
        // Rule VI PHẠM tồn tại trong DB nhưng metrics session = 0 → engine tự động
        // thấy KHÔNG vi phạm (sessionDurationSeconds = 0 < limit)
        h.ruleDao().upsertRule("kid-m13", new ConfigSyncPayload(List.of(), 3_600, 0, null, null));

        assertNotNull(h.engine().manualPause("kid-m13", "Tạm dừng thủ công từ phụ huynh"));
        assertTrue(h.engine().isManuallyEnforced("kid-m13"));
        assertTrue(h.engine().isEnforced("kid-m13"));

        h.engine().evaluateAll(); // vòng quét: hạn mức hợp lệ + không rule vi phạm

        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
        assertEquals(0, countMessages(session.sent, MessageType.COMMAND, ActionType.RESUME_NETWORK),
                "Vòng quét tự động TUYỆT ĐỐI KHÔNG được gửi RESUME cho client khóa thủ công");
        assertTrue(h.engine().isEnforced("kid-m13"), "Client vẫn bị khóa bởi phụ huynh");
        assertTrue(h.engine().isManuallyEnforced("kid-m13"));
    }

    @Test
    @DisplayName("VẤN ĐỀ 1: manualPause khi vi phạm còn → không spam PAUSE (bỏ qua hoàn toàn)")
    void manualPauseWithOngoingViolationNotSpammed() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m14");
        h.sessions().register(session);
        h.registerClient("kid-m14");
        h.ruleDao().upsertRule("kid-m14", new ConfigSyncPayload(List.of(), 0, 1_000, null, null));
        h.metricsDao().insert("kid-m14", new MetricsPayload(2_000, 0, 0, 0, 0, "ACTIVE"));

        assertNotNull(h.engine().manualPause("kid-m14", "thủ công"));
        h.engine().evaluateAll();
        h.engine().evaluateAll();

        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK),
                "Không PAUSE spam dù vi phạm kéo dài");
        assertEquals(0, countMessages(session.sent, MessageType.COMMAND, ActionType.RESUME_NETWORK));
    }

    @Test
    @DisplayName("VẤN ĐỀ 1: manualResume → xóa manualEnforced → quét kế tiếp tự đánh giá lại")
    void manualResumeReenablesAutoEvaluation() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m15");
        h.sessions().register(session);
        h.registerClient("kid-m15");
        h.ruleDao().upsertRule("kid-m15", new ConfigSyncPayload(List.of(), 0, 1_000, null, null));
        h.metricsDao().insert("kid-m15", new MetricsPayload(2_000, 0, 0, 0, 0, "ACTIVE"));

        assertNotNull(h.engine().manualPause("kid-m15", "thủ công"));
        assertTrue(h.engine().isManuallyEnforced("kid-m15"));
        h.engine().evaluateAll();
        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));

        // manualResume khi vi phạm CÒN: gửi RESUME, xóa manual flag
        assertNotNull(h.engine().manualResume("kid-m15"));
        assertFalse(h.engine().isManuallyEnforced("kid-m15"));
        assertFalse(h.engine().isEnforced("kid-m15"));

        h.engine().evaluateAll(); // vi phạm còn → auto PAUSE lại
        assertTrue(h.engine().isEnforced("kid-m15"), "Vi phạm còn → tự động PAUSE lại");
        assertFalse(h.engine().isManuallyEnforced("kid-m15"));
        assertEquals(2, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
    }

    @Test
    @DisplayName("VẤN ĐỀ 1: client rời mạng → manualEnforced bị dọn sạch (reconnect có trạng thái mới)")
    void manualEnforcedCleanedUpWhenOffline() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m16");
        h.sessions().register(session);
        h.registerClient("kid-m16");

        assertNotNull(h.engine().manualPause("kid-m16", "thủ công"));
        assertTrue(h.engine().isManuallyEnforced("kid-m16"));

        h.sessions().remove("kid-m16"); // client rời mạng
        h.engine().evaluateAll();

        assertFalse(h.engine().isManuallyEnforced("kid-m16"), "Offline → manual flag bị dọn");
        assertFalse(h.engine().isEnforced("kid-m16"));
    }

    // ==================================================================
    // manualResume / releaseEnforcement
    // ==================================================================

    @Test
    @DisplayName("manualResume: đang khóa → RESUME + SENT + gỡ enforced + reason thủ công")
    void manualResumeReleases() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m5");
        h.sessions().register(session);
        h.registerClient("kid-m5");

        h.engine().manualPause("kid-m5", "test");
        assertTrue(h.engine().isEnforced("kid-m5"));

        String commandId = h.engine().manualResume("kid-m5");

        assertNotNull(commandId);
        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.RESUME_NETWORK));
        assertEquals("SENT", h.commandLogDao().getStatus(commandId));
        assertFalse(h.engine().isEnforced("kid-m5"));
        assertEquals("Network access restored manually by parent",
                ((CommandPayload) session.sent.get(1).getPayload()).reason());
    }

    @Test
    @DisplayName("manualResume offline → null; releaseEnforcement gỡ cờ KHÔNG gửi lệnh")
    void manualResumeOfflineAndRelease() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m6");
        h.sessions().register(session);
        h.registerClient("kid-m6");

        h.engine().manualPause("kid-m6", "test");
        assertTrue(h.engine().isEnforced("kid-m6"));
        assertTrue(h.engine().isManuallyEnforced("kid-m6"));

        assertNull(h.engine().manualResume("ghost-offline"));
        assertTrue(h.engine().isEnforced("kid-m6")); // client khác không bị ảnh hưởng

        assertTrue(h.engine().releaseEnforcement("kid-m6"));
        assertFalse(h.engine().isEnforced("kid-m6"));
        assertFalse(h.engine().isManuallyEnforced("kid-m6"), "release gỡ cả manual flag");
        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
        assertEquals(0, countMessages(session.sent, MessageType.COMMAND, ActionType.RESUME_NETWORK));
        assertFalse(h.engine().releaseEnforcement("kid-m6")); // lần 2 → false
    }

    // ==================================================================
    // sendAlert
    // ==================================================================

    @Test
    @DisplayName("sendAlert: online → ALERT + SENT; blank → reason mặc định; offline → null")
    void sendAlertCases() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m7");
        h.sessions().register(session);
        h.registerClient("kid-m7");

        String commandId = h.engine().sendAlert("kid-m7", "Đến giờ ăn cơm");

        assertNotNull(commandId);
        assertEquals("SENT", h.commandLogDao().getStatus(commandId));
        assertEquals("Đến giờ ăn cơm", ((CommandPayload) session.sent.get(0).getPayload()).reason());

        assertNotNull(h.engine().sendAlert("kid-m7", " "));
        assertEquals("Message from parent",
                ((CommandPayload) session.sent.get(1).getPayload()).reason());

        assertNull(h.engine().sendAlert("offline-kid", "hello"));
    }

    // ==================================================================
    // syncConfigToClient
    // ==================================================================

    @Test
    @DisplayName("syncConfigToClient: CONFIG_SYNC payload đúng rule + domains (sanitize) từ DB")
    void syncConfigPushesPayload() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m8");
        h.sessions().register(session);
        h.registerClient("kid-m8");
        h.ruleDao().upsertRule("kid-m8", new ConfigSyncPayload(
                List.of("TikTok.COM", "facebook.com"), 14_400, 5_368_709_120L, "06:00", "22:00"));

        assertTrue(h.engine().syncConfigToClient("kid-m8"));

        assertEquals(1, session.sent.size());
        BaseMessage<?> message = session.sent.get(0);
        assertEquals(MessageType.CONFIG_SYNC, message.getType());
        ConfigSyncPayload payload = (ConfigSyncPayload) message.getPayload();
        // getBlockedDomains trả sorted theo alphabet (đúng documented behavior)
        assertEquals(List.of("facebook.com", "tiktok.com"), payload.blockedDomains());
        assertEquals(14_400, payload.dailyUsageLimitSeconds());
        assertEquals(5_368_709_120L, payload.dailyTrafficQuotaBytes());
        assertEquals("06:00", payload.allowedStartTime());
        assertEquals("22:00", payload.allowedEndTime());
    }

    @Test
    @DisplayName("syncConfigToClient: chưa có rule → payload rỗng/0; offline → false")
    void syncConfigWithoutRuleAndOffline() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m9");
        h.sessions().register(session);
        h.registerClient("kid-m9");

        assertTrue(h.engine().syncConfigToClient("kid-m9"));
        ConfigSyncPayload payload = (ConfigSyncPayload) session.sent.get(0).getPayload();
        assertTrue(payload.blockedDomains().isEmpty());
        assertEquals(0, payload.dailyUsageLimitSeconds());
        assertEquals(0, payload.dailyTrafficQuotaBytes());

        assertFalse(h.engine().syncConfigToClient("offline-kid"));
    }

    // ==================================================================
    // Tương tác với vòng quét tự động
    // ==================================================================

    @Test
    @DisplayName("Vi phạm tự động → manualResume gỡ khóa → lượt quét kế tiếp PAUSE lại (vi phạm còn)")
    void manualResumeThenAutoEngineRePauses() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m10");
        h.sessions().register(session);
        h.registerClient("kid-m10");
        h.ruleDao().upsertRule("kid-m10", new ConfigSyncPayload(List.of(), 0, 1_000, null, null));
        h.metricsDao().insert("kid-m10", new MetricsPayload(2_000, 0, 0, 0, 0, "ACTIVE"));

        h.engine().evaluateAll(); // tự động PAUSE (quota 2000 > 1000)
        assertTrue(h.engine().isEnforced("kid-m10"));

        assertNotNull(h.engine().manualResume("kid-m10")); // phụ huynh gỡ khóa
        assertFalse(h.engine().isEnforced("kid-m10"));

        h.engine().evaluateAll(); // vi phạm còn → tự động PAUSE lại
        assertTrue(h.engine().isEnforced("kid-m10"));
        assertEquals(2, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
    }

    @Test
    @DisplayName("manualPause giữ trạng thái qua lượt quét: vi phạm còn nhưng KHÔNG PAUSE spam")
    void manualPauseStateSurvivesAutoScan() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m11");
        h.sessions().register(session);
        h.registerClient("kid-m11");
        h.ruleDao().upsertRule("kid-m11", new ConfigSyncPayload(List.of(), 0, 1_000, null, null));
        h.metricsDao().insert("kid-m11", new MetricsPayload(2_000, 0, 0, 0, 0, "ACTIVE"));

        assertNotNull(h.engine().manualPause("kid-m11", "thủ công"));
        h.engine().evaluateAll(); // quét thấy vi phạm + enforced → không gửi thêm

        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.PAUSE_NETWORK));
    }

    @Test
    @DisplayName("RuleDao.setBlockedDomains: thay thế transactional + sanitize + FK fail")
    void setBlockedDomainsReplaces() {
        Harness h = Harness.create();
        h.registerClient("kid-m12");
        h.ruleDao().upsertRule("kid-m12", new ConfigSyncPayload(
                List.of("old.com"), 0, 0, null, null));
        assertEquals(List.of("old.com"), h.ruleDao().getBlockedDomains("kid-m12"));

        assertTrue(h.ruleDao().setBlockedDomains("kid-m12",
                List.of("New.COM", "  ", "new.com", "other.org")));
        assertEquals(List.of("new.com", "other.org"), h.ruleDao().getBlockedDomains("kid-m12"));

        assertTrue(h.ruleDao().setBlockedDomains("kid-m12", List.of()));
        assertTrue(h.ruleDao().getBlockedDomains("kid-m12").isEmpty());

        assertFalse(h.ruleDao().setBlockedDomains("ghost", List.of("x.com"))); // FK fail
    }

    // ==================================================================
    // Mở rộng 11 — blockedProcesses trong CONFIG_SYNC (chặn/bỏ chặn app .exe)
    // ==================================================================

    /** Lấy CONFIG_SYNC cuối cùng trong các gói session đã gửi. */
    private static ConfigSyncPayload findLastConfigSync(FakeClientSession session) {
        for (int i = session.sent.size() - 1; i >= 0; i--) {
            BaseMessage<?> message = session.sent.get(i);
            if (message.getType() == MessageType.CONFIG_SYNC) {
                return (ConfigSyncPayload) message.getPayload();
            }
        }
        throw new AssertionError("No CONFIG_SYNC sent");
    }

    @Test
    @DisplayName("Mở rộng 11: syncConfigToClient gửi kèm blockedProcesses đã normalize")
    void syncConfigCarriesBlockedProcesses() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m13");
        h.sessions().register(session);
        h.registerClient("kid-m13");
        h.ruleDao().upsertRule("kid-m13", new ConfigSyncPayload(
                List.of("tiktok.com"), 3_600, 1_000, "06:00", "22:00",
                List.of("somegame.exe", "Browser")));

        assertTrue(h.engine().syncConfigToClient("kid-m13"));

        ConfigSyncPayload last = findLastConfigSync(session);
        // getBlockedProcesses trả sorted theo alphabet (đúng documented behavior)
        assertEquals(List.of("browser.exe", "somegame.exe"), last.blockedProcesses(),
                "CONFIG_SYNC mang danh sách tiến trình đã normalize (REPLACE-ALL)");
        assertEquals(List.of("tiktok.com"), last.blockedDomains());
    }

    @Test
    @DisplayName("Mở rộng 11: bỏ chặn app → sync danh sách MỚI KHÔNG còn app đó (client ngừng kill)")
    void syncAfterUnblockRemovesProcess() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m14");
        h.sessions().register(session);
        h.registerClient("kid-m14");

        h.ruleDao().setBlockedProcesses("kid-m14", List.of("somegame.exe", "browser.exe"));
        // Phụ huynh bỏ chặn somegame qua menu nhật ký (1-click):
        assertTrue(h.ruleDao().removeBlockedProcess("kid-m14", "somegame.exe"));
        assertTrue(h.engine().syncConfigToClient("kid-m14"));

        ConfigSyncPayload last = findLastConfigSync(session);
        assertEquals(List.of("browser.exe"), last.blockedProcesses(),
                "Danh sách sync mới KHÔNG còn app đã bỏ chặn");
    }

    @Test
    @DisplayName("Mở rộng 11: sync khi chưa có rule → vẫn mang blockedProcesses")
    void syncWithoutRuleStillCarriesProcesses() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m15");
        h.sessions().register(session);
        h.registerClient("kid-m15");

        h.ruleDao().setBlockedProcesses("kid-m15", List.of("somegame.exe"));
        assertTrue(h.engine().syncConfigToClient("kid-m15"));

        ConfigSyncPayload last = findLastConfigSync(session);
        assertEquals(List.of("somegame.exe"), last.blockedProcesses(),
                "Chưa có rule hạn mức nhưng vẫn phải sync danh sách tiến trình");
        assertEquals(0, last.dailyUsageLimitSeconds());
    }

    @Test
    @DisplayName("Mở rộng 11: blockProcess gửi COMMAND BLOCK_PROCESS với reason = tên tiến trình")
    void blockProcessSendsCommand() {
        Harness h = Harness.create();
        FakeClientSession session = new FakeClientSession("kid-m17");
        h.sessions().register(session);
        h.registerClient("kid-m17");

        assertNotNull(h.engine().blockProcess("kid-m17", "SomeGame"));
        assertEquals(1, countMessages(session.sent, MessageType.COMMAND, ActionType.BLOCK_PROCESS));
        CommandPayload payload = (CommandPayload) session.sent.get(0).getPayload();
        assertEquals("SomeGame", payload.reason(), "reason mang tên tiến trình");

        assertNull(h.engine().blockProcess("offline-kid", "app.exe"), "Offline → null");
        assertNull(h.engine().blockProcess("kid-m17", "  "), "Tên rỗng → null");
    }
}
