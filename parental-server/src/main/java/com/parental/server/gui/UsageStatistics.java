package com.parental.server.gui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.parental.server.db.MetricsDao;

/**
 * Tổng hợp dữ liệu thống kê thời gian sử dụng (Tính năng 3.1) — pure function,
 * không đụng Swing/DB, unit test được ở môi trường headless.
 *
 * <p><b>Nguồn số liệu:</b> chỉ {@code metrics_daily_usage} (đã là delta theo
 * ngày do {@link MetricsDao#insert} cộng dồn). KHÔNG đọc lại
 * {@code metrics_logs} cumulative để tránh cộng trùng; KHÔNG nhân số bản ghi
 * {@code activity_logs} với chu kỳ để bịa thời lượng.</p>
 *
 * <p>Ngày không có dòng trong DB được điền 0 (dữ liệu thiếu hiển thị rõ, không
 * nội suy). Mọi phép cộng dùng {@link LocalDate} nên tự xử lý đổi tháng/năm
 * và năm nhuận; {@code zone} chỉ là metadata hiển thị — {@code usage_date}
 * được Server ghi theo ngày LOCAL của server.</p>
 */
public final class UsageStatistics {

    private UsageStatistics() {
        // utility class
    }

    /** Một cột ngày trên biểu đồ. */
    public record DailyPoint(LocalDate date, long seconds) {
    }

    /** Tổng theo tuần (tuần bắt đầu Thứ Hai, ISO). */
    public record WeeklyPoint(LocalDate weekStart, long seconds) {
    }

    /**
     * Ảnh chụp thống kê trong một khoảng ngày.
     *
     * @param days               đủ mọi ngày trong {@code [from, to]}, tăng dần
     * @param totalSeconds       tổng thời gian trong kỳ
     * @param last7DaysSeconds   7 ngày gần nhất tính đến {@code today}
     * @param previous7DaysSeconds 7 ngày trước đó
     * @param weeks              tổng theo tuần trong kỳ
     * @param hasData            có ít nhất một số liệu khác 0
     */
    public record Snapshot(LocalDate from, LocalDate to, ZoneId zone,
                           List<DailyPoint> days,
                           long totalSeconds, long totalDownloadBytes, long totalUploadBytes,
                           long last7DaysSeconds, long previous7DaysSeconds,
                           List<WeeklyPoint> weeks, boolean hasData) {

        /** TB giây/ngày trong kỳ (0 nếu kỳ rỗng). */
        public long averageSecondsPerDay() {
            return days.isEmpty() ? 0 : totalSeconds / days.size();
        }
    }

    /**
     * Dựng snapshot từ các dòng DAO.
     *
     * @param rows  dữ liệu {@code metrics_daily_usage} (có thể null/rỗng)
     * @param from  ngày bắt đầu (bao gồm)
     * @param to    ngày kết thúc (bao gồm), phải ≥ {@code from}
     * @param today "hôm nay" theo {@code zone}; null → {@link LocalDate#now(ZoneId)}
     * @param zone  múi giờ hiển thị; null → {@link ZoneId#systemDefault()}
     * @throws IllegalArgumentException khi {@code from}/{@code to} null hoặc from &gt; to
     */
    public static Snapshot build(List<MetricsDao.DailyUsage> rows,
                                 LocalDate from, LocalDate to,
                                 LocalDate today, ZoneId zone) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to must not be null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        LocalDate effectiveToday = today != null ? today : LocalDate.now(effectiveZone);

        Map<LocalDate, MetricsDao.DailyUsage> byDate = new LinkedHashMap<>();
        if (rows != null) {
            for (MetricsDao.DailyUsage row : rows) {
                if (row == null || row.date() == null
                        || row.date().isBefore(from) || row.date().isAfter(to)) {
                    continue;
                }
                // PK (client_id, usage_date) đảm bảo duy nhất; cộng dồn defensive
                // nếu DAO/DB trả trùng để không mất số liệu.
                MetricsDao.DailyUsage previous = byDate.get(row.date());
                byDate.put(row.date(), previous == null ? row
                        : new MetricsDao.DailyUsage(row.date(),
                                previous.usageSeconds() + row.usageSeconds(),
                                previous.downloadBytes() + row.downloadBytes(),
                                previous.uploadBytes() + row.uploadBytes()));
            }
        }

        List<DailyPoint> days = new ArrayList<>();
        List<WeeklyPoint> weeks = new ArrayList<>();
        Map<LocalDate, Long> weekTotals = new LinkedHashMap<>();
        long totalSeconds = 0;
        long totalDownload = 0;
        long totalUpload = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            MetricsDao.DailyUsage row = byDate.get(date);
            long seconds = row == null ? 0 : Math.max(0, row.usageSeconds());
            days.add(new DailyPoint(date, seconds));
            totalSeconds += seconds;
            if (row != null) {
                totalDownload += Math.max(0, row.downloadBytes());
                totalUpload += Math.max(0, row.uploadBytes());
            }
            LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weekTotals.merge(weekStart, seconds, Long::sum);
        }
        for (Map.Entry<LocalDate, Long> entry : weekTotals.entrySet()) {
            weeks.add(new WeeklyPoint(entry.getKey(), entry.getValue()));
        }

        long last7 = sumRange(byDate, effectiveToday.minusDays(6), effectiveToday);
        long previous7 = sumRange(byDate, effectiveToday.minusDays(13), effectiveToday.minusDays(7));
        boolean hasData = totalSeconds > 0 || totalDownload > 0 || totalUpload > 0;
        return new Snapshot(from, to, effectiveZone, List.copyOf(days),
                totalSeconds, totalDownload, totalUpload, last7, previous7,
                List.copyOf(weeks), hasData);
    }

    private static long sumRange(Map<LocalDate, MetricsDao.DailyUsage> byDate,
                                 LocalDate from, LocalDate to) {
        long total = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            MetricsDao.DailyUsage row = byDate.get(date);
            if (row != null) {
                total += Math.max(0, row.usageSeconds());
            }
        }
        return total;
    }
}
