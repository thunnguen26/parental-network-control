package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Kiểm thử DB cho số liệu biểu đồ (Tính năng 3.1): chỉ đọc
 * {@code metrics_daily_usage} (delta) và đếm {@code activity_logs}; không cộng
 * snapshot cumulative. Dùng DB in-memory riêng của test.
 */
class UsageStatisticsDaoTest {

    private DatabaseManager database;
    private MetricsDao metricsDao;
    private ActivityDao activityDao;
    private static final String CLIENT = "client-usage";

    @BeforeEach
    void setUp() {
        database = new DatabaseManager();
        new ClientDao(database).upsertClient(new ClientRecord(CLIENT, "PC-Test",
                "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB", "ONLINE"));
        metricsDao = new MetricsDao(database);
        activityDao = new ActivityDao(database);
    }

    /** Instant tại 12:00 LOCAL của ngày — reportDate độc lập với múi giờ máy chạy test. */
    private static Instant noon(LocalDate date) {
        return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    @DisplayName("getDailyUsage trả đúng delta/ngày, không cộng dồn snapshot cumulative")
    void readsDailyDeltasAcrossMonthBoundary() {
        LocalDate dec31 = LocalDate.of(2025, 12, 31);
        LocalDate jan1 = LocalDate.of(2026, 1, 1);
        // Phiên 1: 100s. Phiên 2 (duration 300, cùng tiến trình): delta 200s.
        metricsDao.insert(CLIENT, new MetricsPayload(1_000, 500, 0, 0, 100, "ACTIVE", 0),
                noon(dec31));
        metricsDao.insert(CLIENT, new MetricsPayload(3_000, 1_500, 0, 0, 300, "ACTIVE", 0),
                noon(jan1));

        List<MetricsDao.DailyUsage> rows = metricsDao.getDailyUsage(CLIENT, dec31, jan1);

        assertEquals(2, rows.size());
        assertEquals(dec31, rows.get(0).date());
        assertEquals(100, rows.get(0).usageSeconds());
        assertEquals(1_000, rows.get(0).downloadBytes());
        assertEquals(jan1, rows.get(1).date());
        assertEquals(200, rows.get(1).usageSeconds(), "Chỉ cộng phần tăng thêm, không cộng 300 lần nữa");
        assertEquals(2_000, rows.get(1).downloadBytes());
    }

    @Test
    @DisplayName("getDailyUsage: khoảng rỗng/đảo ngược trả list rỗng, không ném")
    void invalidRangeReturnsEmpty() {
        assertTrue(metricsDao.getDailyUsage(CLIENT,
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)).isEmpty());
        assertTrue(metricsDao.getDailyUsage(CLIENT, null, LocalDate.now()).isEmpty());
        assertTrue(metricsDao.getDailyUsage(null, LocalDate.now(), LocalDate.now()).isEmpty());
    }

    @Test
    @DisplayName("getDailyUsage: ngày không có dữ liệu không xuất hiện (tầng hiển thị tự điền 0)")
    void missingDaysAreNotReturned() {
        LocalDate day = LocalDate.of(2026, 1, 5);
        metricsDao.insert(CLIENT, new MetricsPayload(10, 10, 0, 0, 60, "ACTIVE", 0), noon(day));

        List<MetricsDao.DailyUsage> rows = metricsDao.getDailyUsage(CLIENT,
                day.minusDays(2), day.plusDays(2));

        assertEquals(1, rows.size());
        assertEquals(day, rows.get(0).date());
    }

    @Test
    @DisplayName("countByProcess: đếm đúng số lần theo tiến trình trong ngày, bỏ dòng ngoài khoảng")
    void countsActivityEventsByProcess() throws Exception {
        LocalDate today = LocalDate.now();
        long id1 = activityDao.logActivity(CLIENT, "chrome.exe", "Tab 1", "example.com");
        activityDao.logActivity(CLIENT, "chrome.exe", "Tab 2", "example.com");
        activityDao.logActivity(CLIENT, "notepad.exe", "Ghi chú", "");
        long oldId = activityDao.logActivity(CLIENT, "game.exe", "Game cũ", "");

        // Đẩy 1 dòng về hôm qua để kiểm tra lọc theo ngày LOCAL.
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE activity_logs SET recorded_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(
                    LocalDateTime.of(today.minusDays(1), java.time.LocalTime.NOON)));
            statement.setLong(2, oldId);
            statement.executeUpdate();
        }

        List<ActivityDao.ProcessCount> counts =
                activityDao.countByProcess(CLIENT, today, today, 10);

        assertEquals(2, counts.size(), "game.exe (hôm qua) bị loại");
        assertEquals("chrome.exe", counts.get(0).processName());
        assertEquals(2, counts.get(0).events());
        assertEquals("notepad.exe", counts.get(1).processName());
        assertEquals(1, counts.get(1).events());

        List<ActivityDao.ProcessCount> limited =
                activityDao.countByProcess(CLIENT, today, today, 1);
        assertEquals(1, limited.size());
        assertEquals("chrome.exe", limited.get(0).processName());
        assertTrue(id1 > 0);
    }

    @Test
    @DisplayName("countByProcess: process_name rỗng được gom vào nhóm '(không xác định)'")
    void blankProcessGetsUnknownBucket() {
        LocalDate today = LocalDate.now();
        activityDao.logActivity(CLIENT, " ", "Không rõ app", "");

        List<ActivityDao.ProcessCount> counts =
                activityDao.countByProcess(CLIENT, today, today, 10);

        assertEquals(1, counts.size());
        assertEquals("(không xác định)", counts.get(0).processName());
        assertEquals(1, counts.get(0).events());
    }

    @Test
    @DisplayName("purgeOlderThan: xóa đúng dòng cũ (epoch millis), giữ dòng hôm nay")
    void purgeOlderThanHandlesEpochMillis() throws Exception {
        long oldId = activityDao.logActivity(CLIENT, "old.exe", "Cũ", "");
        activityDao.logActivity(CLIENT, "today.exe", "Hôm nay", "");
        long oldMillis = System.currentTimeMillis() - 10L * 24 * 3600 * 1000;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE activity_logs SET recorded_at = ? WHERE id = ?")) {
            statement.setLong(1, oldMillis);
            statement.setLong(2, oldId);
            statement.executeUpdate();
        }

        int purged = activityDao.purgeOlderThan(7);

        assertEquals(1, purged, "Chỉ xóa dòng 10 ngày trước");
        assertEquals(1, activityDao.countAll(CLIENT), "Dòng hôm nay phải còn nguyên");
    }

    @Test
    @DisplayName("countByProcess: tham số sai trả rỗng, không ném")
    void invalidArgumentsReturnEmpty() {
        assertTrue(activityDao.countByProcess(CLIENT, null, LocalDate.now(), 5).isEmpty());
        assertTrue(activityDao.countByProcess(CLIENT,
                LocalDate.now(), LocalDate.now().minusDays(1), 5).isEmpty());
        assertTrue(activityDao.countByProcess("", LocalDate.now(), LocalDate.now(), 5).isEmpty());
    }
}
