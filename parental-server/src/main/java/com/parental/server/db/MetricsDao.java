package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
        if (metrics == null) {
            log.warn("Cannot insert null metrics for client [{}]", clientId);
            return -1;
        }
        String sql = """
                INSERT INTO metrics_logs (client_id, download_bytes, upload_bytes,
                        download_speed_bps, upload_speed_bps, session_duration_seconds, network_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clientId);
            statement.setLong(2, metrics.downloadBytes());
            statement.setLong(3, metrics.uploadBytes());
            statement.setLong(4, metrics.downloadSpeedBps());
            statement.setLong(5, metrics.uploadSpeedBps());
            statement.setLong(6, metrics.sessionDurationSeconds());
            statement.setString(7, metrics.networkStatus() == null ? "ACTIVE" : metrics.networkStatus());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : -1L;
                log.debug("Metrics inserted for client [{}] (id={})", clientId, id);
                return id;
            }
        } catch (SQLException e) {
            log.error("Failed to insert metrics for client [{}]: {}", clientId, e.getMessage());
            return -1;
        }
    }

    /**
     * Tổng dung lượng đã dùng trong ngày hôm nay (download + upload) của
     * client — tính trên ngày LOCAL của server, dựa {@code recorded_at}.
     *
     * @return tổng bytes trong hôm nay; 0 nếu chưa có bản ghi
     */
    public long getTodayTrafficBytes(String clientId) {
        String sql = """
                SELECT COALESCE(SUM(download_bytes + upload_bytes), 0)
                FROM metrics_logs
                WHERE client_id = ? AND DATE(recorded_at, 'localtime') = DATE('now', 'localtime')
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to get today traffic for client [{}]: {}", clientId, e.getMessage());
            return 0L;
        }
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
