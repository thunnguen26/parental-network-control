package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Unit test cho toàn bộ DAO layer (Task 4.1) trên SQLite :memory: — CRUD trọn
 * vẹn, thống kê ngày, replace domains, flow SENT → ACKED.
 */
class DaosTest {

    private DatabaseManager database;
    private ClientDao clientDao;
    private MetricsDao metricsDao;
    private RuleDao ruleDao;
    private CommandLogDao commandLogDao;

    @BeforeEach
    void setUp() {
        database = new DatabaseManager(); // :memory:
        clientDao = new ClientDao(database);
        metricsDao = new MetricsDao(database);
        ruleDao = new RuleDao(database);
        commandLogDao = new CommandLogDao(database);
    }

    // ------------------------------------------------------------------
    // ClientDao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ClientDao: upsert mới → ONLINE, đủ thông tin device")
    void upsertNewClientSetsOnline() {
        assertTrue(clientDao.upsertClient(new ClientRecord(
                "client-01", "Laptop-ConTrai", "DESKTOP", "Windows 11", "192.168.1.50",
                "AA-BB-CC-DD-EE-FF", "ONLINE")));

        assertTrue(clientDao.exists("client-01"));
        assertEquals("ONLINE", clientDao.getStatus("client-01"));
        ClientRecord record = clientDao.get("client-01");
        assertNotNull(record);
        assertEquals("Laptop-ConTrai", record.deviceName());
        assertEquals("DESKTOP", record.deviceType());
        assertEquals("Windows 11", record.osVersion());
        assertEquals("192.168.1.50", record.ipAddress());
        assertEquals("AA-BB-CC-DD-EE-FF", record.macAddress());
        assertNotNull(clientDao.getLastSeen("client-01"));
    }

    @Test
    @DisplayName("ClientDao: upsert lại → UPDATE thông tin mới (không trùng dòng)")
    void upsertExistingClientUpdatesInfo() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "Old-Name", "DESKTOP", "Windows 10", "192.168.1.50",
                "AA-BB-CC-DD-EE-FF", "ONLINE"));
        clientDao.setStatus("client-01", "OFFLINE");

        assertTrue(clientDao.upsertClient(new ClientRecord(
                "client-01", "New-Name", "DESKTOP", "Windows 11", "192.168.1.77",
                "AA-BB-CC-DD-EE-FF", "ONLINE")));

        ClientRecord record = clientDao.get("client-01");
        assertEquals("New-Name", record.deviceName());
        assertEquals("Windows 11", record.osVersion());
        assertEquals("192.168.1.77", record.ipAddress());
        assertEquals("ONLINE", record.status(), "Reconnect phải chuyển lại ONLINE");
    }

    @Test
    @DisplayName("ClientDao: setStatus OFFLINE/ONLINE; client không tồn tại → false")
    void setStatusWorks() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        assertTrue(clientDao.setStatus("client-01", "OFFLINE"));
        assertEquals("OFFLINE", clientDao.getStatus("client-01"));
        assertTrue(clientDao.setStatus("client-01", "ONLINE"));
        assertEquals("ONLINE", clientDao.getStatus("client-01"));
        assertFalse(clientDao.setStatus("ghost", "OFFLINE"));
    }

    @Test
    @DisplayName("ClientDao: get/exists/lastSeen cho client không tồn tại → null/false")
    void missingClientReturnsNull() {
        assertNull(clientDao.get("ghost"));
        assertNull(clientDao.getStatus("ghost"));
        assertNull(clientDao.getLastSeen("ghost"));
        assertFalse(clientDao.exists("ghost"));
    }

    // ------------------------------------------------------------------
    // MetricsDao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MetricsDao: insert trả id tăng dần, đọc lại đúng giá trị")
    void insertMetricsReturnsGeneratedId() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        long id1 = metricsDao.insert("client-01",
                new MetricsPayload(1_000, 500, 100, 50, 60, "ACTIVE"));
        long id2 = metricsDao.insert("client-01",
                new MetricsPayload(2_000, 900, 200, 90, 120, "PAUSED"));

        assertTrue(id1 > 0);
        assertTrue(id2 > id1, "AUTOINCREMENT phải tăng dần");
        assertEquals(2, metricsDao.countAll("client-01"));
        assertEquals(2, metricsDao.countToday("client-01"));
    }

    @Test
    @DisplayName("MetricsDao: getTodayTrafficBytes = tổng download + upload trong hôm nay")
    void todayTrafficSumsDownloadsAndUploads() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        metricsDao.insert("client-01", new MetricsPayload(1_000, 500, 0, 0, 1, "ACTIVE"));
        metricsDao.insert("client-01", new MetricsPayload(2_000, 1_000, 0, 0, 2, "ACTIVE"));

        assertEquals(4_500, metricsDao.getTodayTrafficBytes("client-01"),
                "Tổng hôm nay = (1000+500) + (2000+1000) = 4500");
    }

    @Test
    @DisplayName("MetricsDao: client chưa có metrics → tổng 0; null payload → -1, không crash")
    void emptyAndNullMetricsHandled() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        assertEquals(0, metricsDao.getTodayTrafficBytes("client-01"));
        assertEquals(0, metricsDao.countAll("client-01"));
        assertEquals(-1, metricsDao.insert("client-01", null));
    }

    @Test
    @DisplayName("MetricsDao: FK từ chối client không tồn tại → insert trả -1")
    void insertForMissingClientFailsGracefully() {
        assertEquals(-1, metricsDao.insert("ghost",
                new MetricsPayload(1, 1, 0, 0, 1, "ACTIVE")));
        assertEquals(0, metricsDao.countAll("ghost"));
    }

    // ------------------------------------------------------------------
    // RuleDao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RuleDao: upsertRule lưu hạn mức + domains; đọc lại khớp")
    void upsertRuleAndReadBack() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        ConfigSyncPayload config = new ConfigSyncPayload(
                List.of("tiktok.com", "facebook.com"), 14_400, 5_368_709_120L, "06:00", "22:00");
        assertTrue(ruleDao.upsertRule("client-01", config));

        RuleDao.RuleRecord rule = ruleDao.getRule("client-01");
        assertNotNull(rule);
        assertEquals(14_400, rule.dailyUsageLimitSeconds());
        assertEquals(5_368_709_120L, rule.dailyTrafficQuotaBytes());
        assertEquals("06:00", rule.allowedStartTime());
        assertEquals("22:00", rule.allowedEndTime());
        assertEquals(List.of("facebook.com", "tiktok.com"), ruleDao.getBlockedDomains("client-01"),
                "Domains được chuẩn hóa lowercase + sort theo alphabet");
    }

    @Test
    @DisplayName("RuleDao: upsert lần 2 → đè rule cũ + replace domains (không trùng)")
    void upsertRuleReplacesOldDomains() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        ruleDao.upsertRule("client-01", new ConfigSyncPayload(
                List.of("tiktok.com", "facebook.com"), 14_400, 0, "06:00", "22:00"));
        ruleDao.upsertRule("client-01", new ConfigSyncPayload(
                List.of("instagram.com"), 7_200, 1_073_741_824L, "08:00", "20:00"));

        RuleDao.RuleRecord rule = ruleDao.getRule("client-01");
        assertEquals(7_200, rule.dailyUsageLimitSeconds());
        assertEquals("08:00", rule.allowedStartTime());
        assertEquals(List.of("instagram.com"), ruleDao.getBlockedDomains("client-01"),
                "Domains cũ phải bị thay thế hoàn toàn");
    }

    @Test
    @DisplayName("RuleDao: domains trùng lặp/blank được khử + lowercase; rule null → false")
    void ruleSanitizesDomains() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        // ConfigSyncPayload đã khử null element ở tầng DTO (List.copyOf);
        // tại đây test trùng lặp + blank + lowercase trên DB layer
        ruleDao.upsertRule("client-01", new ConfigSyncPayload(
                java.util.Arrays.asList("TikTok.COM", "tiktok.com", "  "), 0, 0, null, null));

        assertEquals(List.of("tiktok.com"), ruleDao.getBlockedDomains("client-01"));
        assertFalse(ruleDao.upsertRule("client-01", null));
        assertFalse(ruleDao.upsertRule("ghost", new ConfigSyncPayload(
                List.of("x.com"), 0, 0, null, null)), "Client không tồn tại (FK) → false");
    }

    @Test
    @DisplayName("RuleDao: client chưa có rule → getRule null, domains rỗng")
    void missingRuleReturnsNullAndEmpty() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));
        assertNull(ruleDao.getRule("client-01"));
        assertTrue(ruleDao.getBlockedDomains("client-01").isEmpty());
    }

    // ------------------------------------------------------------------
    // CommandLogDao
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CommandLogDao: recordSent → SENT; markAcked → ACKED (flow hoàn chỉnh)")
    void commandSentThenAckedFlow() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        assertTrue(commandLogDao.recordSent("cmd-1", "client-01",
                ActionType.PAUSE_NETWORK, "Het gio su dung"));
        assertEquals(CommandLogDao.STATUS_SENT, commandLogDao.getStatus("cmd-1"));
        assertEquals(1, commandLogDao.countByStatus(CommandLogDao.STATUS_SENT));

        assertTrue(commandLogDao.markAcked("cmd-1"));
        assertEquals(CommandLogDao.STATUS_ACKED, commandLogDao.getStatus("cmd-1"));
        assertEquals(0, commandLogDao.countByStatus(CommandLogDao.STATUS_SENT));
        assertEquals(1, commandLogDao.countByStatus(CommandLogDao.STATUS_ACKED));

        // Idempotent: ACK lần 2 không lỗi
        assertTrue(commandLogDao.markAcked("cmd-1"));
        assertEquals(1, commandLogDao.countByStatus(CommandLogDao.STATUS_ACKED));
    }

    @Test
    @DisplayName("CommandLogDao: recordSent trùng commandId → ghi đè; ACK lệnh lạ → false")
    void duplicateCommandOverwritesAndUnknownAckFails() {
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));

        commandLogDao.recordSent("cmd-1", "client-01", ActionType.PAUSE_NETWORK, "old");
        commandLogDao.recordSent("cmd-1", "client-01", ActionType.RESUME_NETWORK, "new");

        assertTrue(commandLogDao.markAcked("cmd-1"));
        assertFalse(commandLogDao.markAcked("cmd-unknown"));
        // markAcked chuyển status — kiểm tra không còn SENT
        assertEquals(0, commandLogDao.countByStatus(CommandLogDao.STATUS_SENT));
    }

    @Test
    @DisplayName("CommandLogDao: FK từ chối client không tồn tại → false")
    void commandForMissingClientFailsGracefully() {
        assertFalse(commandLogDao.recordSent("cmd-x", "ghost",
                ActionType.ALERT, "reason"));
        assertNull(commandLogDao.getStatus("cmd-x"));
    }
}
