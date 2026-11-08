package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.server.db.MetricsDao;

/**
 * Test thuần {@link UsageStatistics} — không DB, không Swing (headless an toàn).
 */
class UsageStatisticsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static MetricsDao.DailyUsage usage(String date, long seconds,
                                               long down, long up) {
        return new MetricsDao.DailyUsage(LocalDate.parse(date), seconds, down, up);
    }

    @Test
    @DisplayName("Khoảng 7 ngày: điền đủ ngày trống = 0, tổng đúng, dữ liệu thiếu không nội suy")
    void fillsMissingDaysWithZero() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 7);

        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(usage("2026-01-02", 3600, 1000, 500),
                        usage("2026-01-05", 1800, 2000, 100)),
                from, to, LocalDate.of(2026, 1, 7), ZONE);

        assertEquals(7, snapshot.days().size());
        assertEquals(0, snapshot.days().get(0).seconds(), "01/01 không có dữ liệu → 0");
        assertEquals(3600, snapshot.days().get(1).seconds());
        assertEquals(0, snapshot.days().get(3).seconds());
        assertEquals(5400, snapshot.totalSeconds());
        assertEquals(3000, snapshot.totalDownloadBytes());
        assertEquals(600, snapshot.totalUploadBytes());
        assertTrue(snapshot.hasData());
        assertEquals(ZONE, snapshot.zone());
    }

    @Test
    @DisplayName("Đổi tháng/năm: 29/12/2025 → 04/01/2026 vẫn đủ 7 ngày, tuần ISO đúng")
    void crossesMonthAndYearBoundary() {
        LocalDate from = LocalDate.of(2025, 12, 29);
        LocalDate to = LocalDate.of(2026, 1, 4);

        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(usage("2025-12-31", 600, 0, 0),
                        usage("2026-01-01", 1200, 0, 0)),
                from, to, LocalDate.of(2026, 1, 4), ZONE);

        assertEquals(7, snapshot.days().size());
        assertEquals(LocalDate.of(2025, 12, 29), snapshot.days().get(0).date());
        assertEquals(LocalDate.of(2026, 1, 4), snapshot.days().get(6).date());
        assertEquals(1800, snapshot.totalSeconds());
        assertEquals(1, snapshot.weeks().size(), "29/12/2025 là Thứ Hai — cả 7 ngày cùng tuần ISO");
        assertEquals(LocalDate.of(2025, 12, 29), snapshot.weeks().get(0).weekStart());
        assertEquals(1800, snapshot.weeks().get(0).seconds());
    }

    @Test
    @DisplayName("Năm nhuận: khoảng 27/02 → 02/03/2024 có đúng 5 ngày và chứa 29/02")
    void handlesLeapYear() {
        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(), LocalDate.of(2024, 2, 27), LocalDate.of(2024, 3, 2),
                LocalDate.of(2024, 3, 2), ZONE);

        assertEquals(5, snapshot.days().size());
        assertTrue(snapshot.days().stream()
                .anyMatch(point -> point.date().equals(LocalDate.of(2024, 2, 29))));
        assertFalse(snapshot.hasData());
        assertEquals(0, snapshot.totalSeconds());
    }

    @Test
    @DisplayName("Tuần hiện tại / 7 ngày trước tính đúng theo today, kể cả today ngoài khoảng")
    void calculatesLastAndPreviousSevenDays() {
        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(usage("2026-01-10", 100, 0, 0),
                        usage("2026-01-04", 200, 0, 0),
                        usage("2026-01-03", 400, 0, 0)),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 10), ZONE);

        assertEquals(300, snapshot.last7DaysSeconds(), "04/01..10/01 gồm 04/01=200 + 10/01=100");
        assertEquals(400, snapshot.previous7DaysSeconds(), "28/12..03/01 có 03/01 = 400s");
    }

    @Test
    @DisplayName("Nhiều tuần: chia bucket theo Thứ Hai, tổng từng tuần đúng")
    void groupsByIsoWeek() {
        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(usage("2026-01-05", 60, 0, 0),   // tuần 05/01
                        usage("2026-01-11", 120, 0, 0),  // CN cùng tuần
                        usage("2026-01-12", 240, 0, 0)), // tuần 12/01
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 12),
                LocalDate.of(2026, 1, 12), ZONE);

        assertEquals(2, snapshot.weeks().size());
        assertEquals(LocalDate.of(2026, 1, 5), snapshot.weeks().get(0).weekStart());
        assertEquals(180, snapshot.weeks().get(0).seconds());
        assertEquals(240, snapshot.weeks().get(1).seconds());
    }

    @Test
    @DisplayName("Rows null/rỗng hoặc ngoài khoảng bị bỏ qua an toàn")
    void ignoresNullAndOutOfRangeRows() {
        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                java.util.Arrays.asList(null,
                        usage("2025-12-31", 999, 0, 0),
                        usage("2026-01-03", 30, 0, 0)),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                LocalDate.of(2026, 1, 3), null);

        assertEquals(3, snapshot.days().size());
        assertEquals(30, snapshot.totalSeconds());
        assertEquals(ZoneId.systemDefault(), snapshot.zone(), "zone null → system default");
    }

    @Test
    @DisplayName("Khoảng sai (from > to) hoặc null bị từ chối rõ ràng")
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> UsageStatistics.build(
                List.of(), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2), ZONE));
        assertThrows(IllegalArgumentException.class, () -> UsageStatistics.build(
                List.of(), null, LocalDate.of(2026, 1, 1), null, ZONE));
    }

    @Test
    @DisplayName("TB/ngày chia đúng số ngày trong kỳ (kể cả ngày 0)")
    void averageUsesAllDays() {
        UsageStatistics.Snapshot snapshot = UsageStatistics.build(
                List.of(usage("2026-01-01", 700, 0, 0)),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7),
                LocalDate.of(2026, 1, 7), ZONE);
        assertEquals(100, snapshot.averageSecondsPerDay());
    }
}
