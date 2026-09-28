package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test quản lý chặn tiến trình (Mở rộng 11 — {@link RuleDao} bảng
 * {@code blocked_processes}): chuẩn hóa tên, add/remove idempotent,
 * REPLACE-ALL transactional, FK client lạ, cascade xóa cùng client.
 */
class BlockedProcessDaoTest {

    private DatabaseManager database;
    private ClientDao clientDao;
    private RuleDao ruleDao;

    @BeforeEach
    void setUp() {
        database = new DatabaseManager(); // :memory:
        clientDao = new ClientDao(database);
        ruleDao = new RuleDao(database);
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));
    }

    @Test
    @DisplayName("normalizeProcessName: thêm .exe, lowercase; từ chối rỗng/ký tự lạ/..")
    void normalizeProcessName() {
        assertEquals("somegame.exe", RuleDao.normalizeProcessName("SomeGame"));
        assertEquals("somegame.exe", RuleDao.normalizeProcessName("  SOMEGAME.EXE  "));
        assertNull(RuleDao.normalizeProcessName(null));
        assertNull(RuleDao.normalizeProcessName("   "));
        assertNull(RuleDao.normalizeProcessName(".exe"));
        assertNull(RuleDao.normalizeProcessName("bad name!"));
        assertNull(RuleDao.normalizeProcessName("bad..name"));
    }

    @Test
    @DisplayName("addBlockedProcess: chuẩn hóa + idempotent (thêm lặp không nhân bản)")
    void addBlockedProcessIsIdempotent() {
        assertTrue(ruleDao.addBlockedProcess("client-01", "SomeGame"));
        assertTrue(ruleDao.addBlockedProcess("client-01", "somegame.exe"),
                "thêm lặp vẫn true (idempotent)");
        assertTrue(ruleDao.addBlockedProcess("client-01", "somegame.exe"));

        assertEquals(List.of("somegame.exe"), ruleDao.getBlockedProcesses("client-01"),
                "3 lần thêm cùng app → đúng 1 dòng");
    }

    @Test
    @DisplayName("removeBlockedProcess: bỏ chặn đúng app, giữ app khác; không tồn tại → false")
    void removeBlockedProcessUnblocksOnlyTarget() {
        ruleDao.addBlockedProcess("client-01", "somegame.exe");
        ruleDao.addBlockedProcess("client-01", "browser.exe");

        assertTrue(ruleDao.removeBlockedProcess("client-01", "SomeGame"),
                "Gỡ bằng tên chưa chuẩn hóa vẫn khớp sau normalize");
        assertEquals(List.of("browser.exe"), ruleDao.getBlockedProcesses("client-01"),
                "App khác phải còn nguyên trong danh sách chặn");
        assertFalse(ruleDao.removeBlockedProcess("client-01", "somegame.exe"),
                "Đã gỡ → false");
    }

    @Test
    @DisplayName("setBlockedProcesses: REPLACE-ALL — danh sách mới đè toàn bộ; rỗng = dọn sạch")
    void setBlockedProcessesReplacesAll() {
        ruleDao.addBlockedProcess("client-01", "oldapp.exe");

        assertTrue(ruleDao.setBlockedProcesses("client-01",
                java.util.Arrays.asList("NewGame", "browser.exe", "BROWSER.EXE", "  ", null)));
        assertEquals(List.of("browser.exe", "newgame.exe"),
                ruleDao.getBlockedProcesses("client-01"),
                "Danh sách cũ bị thay thế hoàn toàn; trùng/normalize khử sạch");

        assertTrue(ruleDao.setBlockedProcesses("client-01", List.of()));
        assertTrue(ruleDao.getBlockedProcesses("client-01").isEmpty(),
                "Danh sách rỗng = dọn sạch mọi chặn tiến trình");
    }

    @Test
    @DisplayName("addBlockedProcess: tên hỏng/client lạ (FK) → false")
    void addRejectsInvalidOrMissingClient() {
        assertFalse(ruleDao.addBlockedProcess("client-01", "bad name!"));
        assertFalse(ruleDao.addBlockedProcess("client-01", null));
        assertFalse(ruleDao.addBlockedProcess("ghost", "somegame.exe"));
        assertTrue(ruleDao.getBlockedProcesses("client-01").isEmpty());
    }

    @Test
    @DisplayName("setBlockedProcesses: client lạ (FK) → vẫn true về SQL nhưng danh sách client thật không đổi")
    void setForMissingClientDoesNotAffectOthers() {
        ruleDao.addBlockedProcess("client-01", "somegame.exe");

        // FK: client lạ — SQLite với FK ON sẽ chèn thất bại im lặng (INSERT OR IGNORE)
        ruleDao.setBlockedProcesses("ghost", List.of("somegame.exe"));
        assertEquals(List.of("somegame.exe"), ruleDao.getBlockedProcesses("client-01"),
                "Client thật không bị ảnh hưởng bởi thao tác trên client lạ");
        assertTrue(ruleDao.getBlockedProcesses("ghost").isEmpty(),
                "Client lạ (không có FK) không có dữ liệu");
    }

    @Test
    @DisplayName("deleteClient cascade: blocked_processes bị xóa sạch cùng client")
    void deleteClientCascadesBlockedProcesses() {
        ruleDao.addBlockedProcess("client-01", "somegame.exe");
        assertEquals(1, ruleDao.getBlockedProcesses("client-01").size());

        assertTrue(clientDao.deleteClient("client-01"));
        assertTrue(ruleDao.getBlockedProcesses("client-01").isEmpty(),
                "deleteClient phải xóa cascade blocked_processes");
    }

    @Test
    @DisplayName("upsertRule kèm ConfigSyncPayload.blockedProcesses → persist cùng lúc với domains")
    void upsertRulePersistsBlockedProcesses() {
        ruleDao.upsertRule("client-01", new com.parental.common.protocol.payload.ConfigSyncPayload(
                List.of("facebook.com"), 3_600, 1_000, "06:00", "22:00",
                List.of("somegame.exe", "Browser")));

        // getBlockedProcesses trả sorted theo alphabet (đúng documented behavior)
        assertEquals(List.of("browser.exe", "somegame.exe"),
                ruleDao.getBlockedProcesses("client-01"),
                "Lưu chính sách phải đồng thời persist danh sách tiến trình chặn");
        assertEquals(List.of("facebook.com"), ruleDao.getBlockedDomains("client-01"));
    }
}
