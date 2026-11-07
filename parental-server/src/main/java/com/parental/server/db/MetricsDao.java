package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.MetricsPayload;

/**
 * DAO bảng {@code metrics_logs} (Task 4.1): lưu bản ghi traffic từ
 * METRICS_REPORT và truy vấn thống kê.
 */
public class MetricsDao {

    private static final Logger log = LoggerFactory.getLogger(MetricsDao.class);

    private final DatabaseManager database;

    public MetricsDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Lưu một bản ghi metrics của client. FK ràng buộc: client phải tồn tại
     * trong bảng {@code clients} (đã upsert khi handshake).
     *
     * @return id bản ghi vừa chèn; -1 nếu thất bại
     */
    public long insert(String clientId, MetricsPayload metrics) {
        return insert(clientId, metrics, Instant.now());
    }

    long insert(String clientId, MetricsPayload metrics, Instant recordedAt) {
        if (metrics == null) {
            log.warn("Cannot insert null metrics for client [{}]", clientId);
            return -1;
        }
        if (recordedAt == null) {
            throw new IllegalArgumentException("recordedAt must not be null");
        }
        String sql = """
                INSERT INTO metrics_logs (client_id, download_bytes, upload_bytes,
                        download_speed_bps, upload_speed_bps, session_duration_seconds, network_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.newConnection()) {
            connection.setAutoCommit(false);
            try {
                MetricsState previous = getState(connection, clientId);
                long trafficDownloadDelta = counterDelta(metrics.downloadBytes(),
                        previous == null ? null : previous.downloadBytes());
                long trafficUploadDelta = counterDelta(metrics.uploadBytes(),
                        previous == null ? null : previous.uploadBytes());
                boolean newSession = previous == null
                        || (previous.sessionStartTimestamp() > 0
                            && metrics.sessionStartTimestamp() > 0
                            && previous.sessionStartTimestamp() != metrics.sessionStartTimestamp())
                        || metrics.sessionDurationSeconds() < previous.sessionDurationSeconds();
                long previousDuration = newSession || previous == null
                        ? 0 : previous.sessionDurationSeconds();
                long usageDelta = Math.max(0,
                        metrics.sessionDurationSeconds() - previousDuration);
                long id;
                try (PreparedStatement statement = connection.prepareStatement(sql,
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, clientId);
                    statement.setLong(2, metrics.downloadBytes());
                    statement.setLong(3, metrics.uploadBytes());
                    statement.setLong(4, metrics.downloadSpeedBps());
                    statement.setLong(5, metrics.uploadSpeedBps());
                    statement.setLong(6, metrics.sessionDurationSeconds());
                    statement.setString(7, metrics.networkStatus() == null
                            ? "ACTIVE" : metrics.networkStatus());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        id = keys.next() ? keys.getLong(1) : -1L;
                    }
                }

                Map<LocalDate, Long> usageByDate = usageByLocalDate(
                        metrics.sessionStartTimestamp(), previousDuration,
                        metrics.sessionDurationSeconds(), usageDelta, recordedAt,
                        ZoneId.systemDefault());
                LocalDate reportDate = LocalDate.ofInstant(recordedAt, ZoneId.systemDefault());
                addDailyUsage(connection, clientId, reportDate, trafficDownloadDelta,
                        trafficUploadDelta, usageByDate.getOrDefault(reportDate, 0L));
                for (Map.Entry<LocalDate, Long> entry : usageByDate.entrySet()) {
                    if (!entry.getKey().equals(reportDate)) {
                        addDailyUsage(connection, clientId, entry.getKey(), 0, 0, entry.getValue());
                    }
                }
                saveState(connection, clientId, metrics);
                connection.commit();
                log.debug("Metrics inserted for client [{}] (id={}, downDelta={}, upDelta={})",
                        clientId, id, trafficDownloadDelta, trafficUploadDelta);
                return id;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Failed to insert metrics for client [{}]: {}", clientId, e.getMessage());
            return -1;
        }
    }

    /**
     * Tổng dung lượng đã dùng trong ngày hôm nay (download + upload) của
     * client — tính trên ngày LOCAL của server, dùng chênh lệch các snapshot
     * METRICS_REPORT thay vì cộng lặp các bộ đếm tích lũy.
     *
     * @return tổng bytes trong hôm nay; 0 nếu chưa có bản ghi
     */
    public long getTodayTrafficBytes(String clientId) {
        return todayUsageValue(clientId, "download_bytes + upload_bytes");
    }

    /** Today's accumulated client uptime, derived from monotonic metric deltas. */
    public long getTodayUsageSeconds(String clientId) {
        return todayUsageValue(clientId, "usage_seconds");
    }

    private long todayUsageValue(String clientId, String expression) {
        String sql = "SELECT COALESCE(SUM(" + expression + "), 0) "
                + "FROM metrics_daily_usage WHERE client_id = ? AND usage_date = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, LocalDate.now().toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to get today traffic for client [{}]: {}", clientId, e.getMessage());
            return 0L;
        }
    }

    private static MetricsState getState(Connection connection, String clientId)
            throws SQLException {
        String sql = """
                SELECT download_bytes, upload_bytes, session_duration_seconds,
                       session_start_timestamp
                FROM metrics_client_state WHERE client_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next()
                        ? new MetricsState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))
                        : null;
            }
        }
    }

    private static void saveState(Connection connection, String clientId, MetricsPayload metrics)
            throws SQLException {
        String sql = """
                INSERT INTO metrics_client_state (
                    client_id, download_bytes, upload_bytes, session_duration_seconds,
                    session_start_timestamp)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(client_id) DO UPDATE SET
                    download_bytes = excluded.download_bytes,
                    upload_bytes = excluded.upload_bytes,
                    session_duration_seconds = excluded.session_duration_seconds,
                    session_start_timestamp = excluded.session_start_timestamp
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setLong(2, metrics.downloadBytes());
            statement.setLong(3, metrics.uploadBytes());
            statement.setLong(4, metrics.sessionDurationSeconds());
            statement.setLong(5, metrics.sessionStartTimestamp());
            statement.executeUpdate();
        }
    }

    private static void addDailyUsage(Connection connection, String clientId, LocalDate date,
                                      long downloadDelta, long uploadDelta, long usageDelta)
            throws SQLException {
        String sql = """
                INSERT INTO metrics_daily_usage (
                    client_id, usage_date, download_bytes, upload_bytes, usage_seconds)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(client_id, usage_date) DO UPDATE SET
                    download_bytes = download_bytes + excluded.download_bytes,
                    upload_bytes = upload_bytes + excluded.upload_bytes,
                    usage_seconds = usage_seconds + excluded.usage_seconds
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, date.toString());
            statement.setLong(3, downloadDelta);
            statement.setLong(4, uploadDelta);
            statement.setLong(5, usageDelta);
            statement.executeUpdate();
        }
    }

    private static long counterDelta(long current, Long previous) {
        if (current < 0) {
            return 0;
        }
        if (previous == null || current < previous) {
            return current;
        }
        return current - previous;
    }

    static Map<LocalDate, Long> usageByLocalDate(long sessionStartTimestamp,
                                                  long previousDurationSeconds,
                                                  long currentDurationSeconds,
                                                  long usageDelta,
                                                  Instant recordedAt,
                                                  ZoneId zone) {
        if (usageDelta <= 0) {
            return Map.of();
        }
        if (sessionStartTimestamp <= 0) {
            return Map.of(LocalDate.ofInstant(recordedAt, zone), usageDelta);
        }
        long start = sessionStartTimestamp + Math.max(0, previousDurationSeconds);
        long end = sessionStartTimestamp + Math.max(0, currentDurationSeconds);
        if (end <= start) {
            return Map.of(LocalDate.ofInstant(recordedAt, zone), usageDelta);
        }
        Map<LocalDate, Long> usageByDate = new LinkedHashMap<>();
        LocalDate date = LocalDate.ofInstant(Instant.ofEpochSecond(start), zone);
        LocalDate endDate = LocalDate.ofInstant(Instant.ofEpochSecond(end - 1), zone);
        while (!date.isAfter(endDate)) {
            long dayStart = date.atStartOfDay(zone).toEpochSecond();
            long nextDayStart = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
            long overlap = Math.max(0, Math.min(end, nextDayStart) - Math.max(start, dayStart));
            if (overlap > 0) {
                usageByDate.put(date, overlap);
            }
            date = date.plusDays(1);
        }
        return usageByDate;
    }

    private record MetricsState(long downloadBytes, long uploadBytes,
                                long sessionDurationSeconds, long sessionStartTimestamp) {
    }

    /** Số bản ghi metrics của client trong hôm nay (hỗ trợ test/thống kê). */
    public long countToday(String clientId) {
        String sql = """
                SELECT COUNT(*) FROM metrics_logs
                WHERE client_id = ? AND DATE(recorded_at, 'localtime') = DATE('now', 'localtime')
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to count today metrics for client [{}]: {}", clientId, e.getMessage());
            return 0L;
        }
    }

    /** Tổng số bản ghi metrics của client (mọi thời gian). */
    public long countAll(String clientId) {
        String sql = "SELECT COUNT(*) FROM metrics_logs WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to count metrics for client [{}]: {}", clientId, e.getMessage());
            return 0L;
        }
    }
}
