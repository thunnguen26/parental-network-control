package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test {@link ActivityDao} (Activity Monitoring — PROTOCOL.md Mục 3.9)
 * trên SQLite :memory: — ghi/đọc nhật ký hoạt động, thứ tự mới nhất trước,
 * giới hạn limit, defensive với client không tồn tại (FK) và input xấu.
 */
class ActivityDaoTest {

    private DatabaseManager database;
    private ClientDao clientDao;
    private ActivityDao activityDao;

    @BeforeEach
    void setUp() {
        database = new DatabaseManager(); // :memory:
        clientDao = new ClientDao(database);
        activityDao = new ActivityDao(database);
        clientDao.upsertClient(new ClientRecord(
                "client-01", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", "ONLINE"));
    }

    @Test
    @DisplayName("logActivity → id > 0; getRecentActivities đọc lại đúng process/title")
    void logAndReadBack() {
        long id = activityDao.logActivity("client-01", "chrome.exe",
                "Giải bài tập Hóa 12 | VietJack - Google Chrome");

        assertTrue(id > 0, "logActivity phải trả id sinh ra");

        List<ActivityDao.ActivityRecord> records = activityDao.getRecentActivities("client-01", 10);
        assertEquals(1, records.size());
        ActivityDao.ActivityRecord record = records.get(0);
        assertEquals(id, record.id());
        assertEquals("client-01", record.clientId());
        assertEquals("chrome.exe", record.processName());
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", record.windowTitle());
        assertNotNull(record.recordedAt(), "recorded_at phải có giá trị DEFAULT");
    }

    @Test
    @DisplayName("getRecentActivities: mới nhất TRƯỚC, giới hạn đúng limit")
    void recentReturnsNewestFirstWithLimit() {
        activityDao.logActivity("client-01", "app1.exe", "Cửa sổ 1");
        activityDao.logActivity("client-01", "app2.exe", "Cửa sổ 2");
        activityDao.logActivity("client-01", "app3.exe", "Cửa sổ 3");
        activityDao.logActivity("client-01", "app4.exe", "Cửa sổ 4");

        List<ActivityDao.ActivityRecord> top2 = activityDao.getRecentActivities("client-01", 2);
        assertEquals(2, top2.size(), "Limit 2 → đúng 2 bản ghi");
        // Bản ghi mới nhất (id lớn nhất) đứng đầu
        assertEquals("app4.exe", top2.get(0).processName());
        assertEquals("app3.exe", top2.get(1).processName());

        List<ActivityDao.ActivityRecord> all = activityDao.getRecentActivities("client-01", 10);
        assertEquals(4, all.size());
        assertEquals("app4.exe", all.get(0).processName());
        assertEquals("app1.exe", all.get(3).processName());
    }

    @Test
    @DisplayName("logActivity: client không tồn tại (FK) → -1; clientId null/blank → -1")
    void logForMissingClientFailsGracefully() {
        assertEquals(-1, activityDao.logActivity("ghost", "app.exe", "title"));
        assertEquals(-1, activityDao.logActivity(null, "app.exe", "title"));
        assertEquals(-1, activityDao.logActivity("", "app.exe", "title"));
        assertEquals(0, activityDao.countAll("ghost"));
        assertTrue(activityDao.getRecentActivities("ghost", 5).isEmpty());
    }

    @Test
    @DisplayName("logActivity: process > 100 ký tự bị cắt còn 100 (VARCHAR(100))")
    void longProcessNameTruncated() {
        String longProcess = "p".repeat(150);
        assertTrue(activityDao.logActivity("client-01", longProcess, "title") > 0);

        ActivityDao.ActivityRecord record = activityDao.getRecentActivities("client-01", 1).get(0);
        assertEquals(100, record.processName().length(), "Process name phải bị cắt còn 100");
    }

    @Test
    @DisplayName("logActivity: giá trị null được chấp nhận (process/title null → lưu NULL)")
    void nullFieldsAccepted() {
        assertTrue(activityDao.logActivity("client-01", null, null) > 0);

        ActivityDao.ActivityRecord record = activityDao.getRecentActivities("client-01", 1).get(0);
        assertEquals(null, record.processName());
        assertEquals(null, record.windowTitle());
    }

    @Test
    @DisplayName("getRecentActivities: limit <= 0 → rỗng; countAll đếm đúng")
    void limitAndCountEdgeCases() {
        activityDao.logActivity("client-01", "app.exe", "title");
        activityDao.logActivity("client-01", "app.exe", "title");

        assertTrue(activityDao.getRecentActivities("client-01", 0).isEmpty());
        assertTrue(activityDao.getRecentActivities("client-01", -5).isEmpty());
        assertEquals(2, activityDao.countAll("client-01"));
    }

    @Test
    @DisplayName("deleteClient cascade: activity_logs bị xóa sạch cùng client")
    void deleteClientCascadesActivities() {
        activityDao.logActivity("client-01", "app.exe", "title");
        assertEquals(1, activityDao.countAll("client-01"));

        assertTrue(clientDao.deleteClient("client-01"));
        assertEquals(0, activityDao.countAll("client-01"),
                "deleteClient phải xóa cascade activity_logs");
    }
}
