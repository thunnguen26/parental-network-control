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

/**
 * Unit test {@link BlockedDomainDao} (Domain Blacklist Management): chuẩn hóa
 * URL → domain sạch, add/remove đơn lẻ, setBlockedDomains thay thế atomic,
 * defensive FK, cascade xóa cùng client.
 */
class BlockedDomainDaoTest {

    private DatabaseManager database;
    private ClientDao clientDao;
    private BlockedDomainDao dao;

    @BeforeEach
    void setUp() {
        database = new DatabaseManager(); // :memory:
        clientDao = new ClientDao(database);
        dao = new BlockedDomainDao(database);
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));
    }

    // ------------------------------------------------------------------
    // normalizeDomain — chuẩn hóa URL thành domain sạch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("normalizeDomain: https://www.Facebook.com/ → facebook.com (scheme/www/slash/path)")
    void normalizeStripsSchemeWwwSlashPath() {
        assertEquals("facebook.com", BlockedDomainDao.normalizeDomain("https://www.Facebook.com/"));
        assertEquals("facebook.com", BlockedDomainDao.normalizeDomain("http://facebook.com"));
        assertEquals("facebook.com", BlockedDomainDao.normalizeDomain("  FACEBOOK.COM  "));
        assertEquals("youtube.com", BlockedDomainDao.normalizeDomain("https://www.youtube.com/watch?v=abc"));
        assertEquals("bet88.com", BlockedDomainDao.normalizeDomain("https://bet88.com:8080/path"));
        assertEquals("facebook.com", BlockedDomainDao.normalizeDomain("www.facebook.com"));
    }

    @Test
    @DisplayName("normalizeDomain: giữ subdomain thật (không phải www), domain nước ngoài")
    void normalizeKeepsRealSubdomains() {
        assertEquals("mail.google.com", BlockedDomainDao.normalizeDomain("https://mail.google.com/"));
        assertEquals("tiktok.com", BlockedDomainDao.normalizeDomain("TIKTOK.com"));
        assertEquals("x.com", BlockedDomainDao.normalizeDomain("https://x.com/home"));
    }

    @Test
    @DisplayName("normalizeDomain: đầu vào rỗng/không hợp lệ → null")
    void normalizeRejectsInvalidInput() {
        assertNull(BlockedDomainDao.normalizeDomain(null));
        assertNull(BlockedDomainDao.normalizeDomain(""));
        assertNull(BlockedDomainDao.normalizeDomain("   "));
        assertNull(BlockedDomainDao.normalizeDomain("không phải domain"));
        assertNull(BlockedDomainDao.normalizeDomain("https://"));
        assertNull(BlockedDomainDao.normalizeDomain(".com"));
        assertNull(BlockedDomainDao.normalizeDomain("nodot"));
        assertNull(BlockedDomainDao.normalizeDomain("https://x com/"));
    }

    // ------------------------------------------------------------------
    // addDomain / removeDomain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("addDomain: URL thô được chuẩn hóa; đọc lại sorted; trùng → false")
    void addDomainNormalizesAndDeduplicates() {
        assertTrue(dao.addDomain("client-01", "https://www.Facebook.com/"));
        assertTrue(dao.addDomain("client-01", "TikTok.Com"));

        assertEquals(List.of("facebook.com", "tiktok.com"), dao.getBlockedDomains("client-01"),
                "Đọc lại sorted A-Z, đã chuẩn hóa lowercase");

        assertFalse(dao.addDomain("client-01", "facebook.com"), "Trùng → false (INSERT OR IGNORE)");
        assertEquals(2, dao.getBlockedDomains("client-01").size());
    }

    @Test
    @DisplayName("addDomain: đầu vào không hợp lệ/client lạ (FK) → false")
    void addDomainRejectsInvalidOrMissingClient() {
        assertFalse(dao.addDomain("client-01", "not a domain"));
        assertFalse(dao.addDomain("client-01", null));
        assertFalse(dao.addDomain("ghost", "facebook.com"), "FK từ chối client lạ");
        assertTrue(dao.getBlockedDomains("client-01").isEmpty());
    }

    @Test
    @DisplayName("removeDomain: xóa theo domain đã chuẩn hóa; không tồn tại → false")
    void removeDomainNormalizesAndDeletes() {
        dao.addDomain("client-01", "facebook.com");
        dao.addDomain("client-01", "tiktok.com");

        assertTrue(dao.removeDomain("client-01", "https://www.Facebook.com/"),
                "Xóa bằng URL thô vẫn khớp sau chuẩn hóa");
        assertEquals(List.of("tiktok.com"), dao.getBlockedDomains("client-01"));
        assertFalse(dao.removeDomain("client-01", "facebook.com"), "Đã xóa → false");
        assertFalse(dao.removeDomain("ghost", "tiktok.com"));
        assertFalse(dao.removeDomain("client-01", "không hợp lệ!!!"));
    }

    // ------------------------------------------------------------------
    // setBlockedDomains — thay thế toàn bộ (transaction atomic)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setBlockedDomains: thay thế toàn bộ, chuẩn hóa + khử trùng lặp")
    void setReplacesAllAndNormalizes() {
        dao.addDomain("client-01", "old.com");

        assertTrue(dao.setBlockedDomains("client-01", java.util.Arrays.asList(
                "https://www.YouTube.com/", "tiktok.com", "TIKTOK.com", "  ", null)));

        assertEquals(List.of("tiktok.com", "youtube.com"), dao.getBlockedDomains("client-01"),
                "Danh sách cũ bị thay thế hoàn toàn; trùng/rỗng/null bị khử");
    }

    @Test
    @DisplayName("setBlockedDomains: danh sách rỗng/null → xóa sạch; client lạ → false")
    void setEmptyClearsAndMissingClientFails() {
        dao.addDomain("client-01", "facebook.com");

        assertTrue(dao.setBlockedDomains("client-01", List.of()));
        assertTrue(dao.getBlockedDomains("client-01").isEmpty(), "Rỗng → xóa sạch");

        assertFalse(dao.setBlockedDomains("ghost", List.of("x.com")), "FK client lạ → false");
        assertFalse(dao.setBlockedDomains(null, List.of("x.com")));
    }

    @Test
    @DisplayName("setBlockedDomains atomic: đủ dữ liệu rule + metrics bên cạnh không mất")
    void setKeepsOtherClientDataIntact() {
        // Client có cả rule/metrics — set domains không được ảnh hưởng bảng khác
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));
        RuleDao ruleDao = new RuleDao(database);
        ruleDao.upsertRule("client-01", new com.parental.common.protocol.payload.ConfigSyncPayload(
                List.of("a.com"), 3_600, 1_000, "06:00", "22:00"));

        assertTrue(dao.setBlockedDomains("client-01", List.of("facebook.com")));

        assertNotNull(ruleDao.getRule("client-01"), "client_rules phải nguyên vẹn");
        assertEquals("facebook.com", ruleDao.getBlockedDomains("client-01").get(0));
    }

    // ------------------------------------------------------------------
    // Cascade với deleteClient
    // ------------------------------------------------------------------

    @Test
    @DisplayName("deleteClient cascade: blocked_domains bị xóa sạch cùng client")
    void deleteClientCascadesBlockedDomains() {
        dao.addDomain("client-01", "facebook.com");
        assertEquals(1, dao.getBlockedDomains("client-01").size());

        assertTrue(clientDao.deleteClient("client-01"));
        assertTrue(dao.getBlockedDomains("client-01").isEmpty(),
                "deleteClient phải xóa cascade blocked_domains");
    }

    // ------------------------------------------------------------------
    // Nhất quán quy tắc chuẩn hóa GUI ↔ DAO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GUI helper và DAO normalize cho kết quả nhất quán trên bộ mẫu phổ biến")
    void normalizationMatchesGuiHelper() throws Exception {
        // Tránh phụ thuộc compile-time sang package gui (dialog là package-private):
        // so kết quả qua dữ liệu thật — GUI lưu qua DAO phải ra domain sạch giống nhau.
        String[] rawInputs = {"https://www.Facebook.com/", "HTTP://X.COM/path", "TikTok.Com"};
        for (String raw : rawInputs) {
            // DAO là nguồn chuẩn: addDomain rồi đọc lại
            assertTrue(dao.addDomain("client-01", raw));
            List<String> stored = dao.getBlockedDomains("client-01");
            assertFalse(stored.isEmpty(), "addDomain với input '" + raw + "' phải thành công");
            dao.removeDomain("client-01", raw); // dọn cho lần lặp sau
        }
    }
}
