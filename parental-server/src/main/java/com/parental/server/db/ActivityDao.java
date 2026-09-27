package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO bảng {@code activity_logs} — nhật ký hoạt động người dùng
 * (Activity Monitoring — PROTOCOL.md Mục 3.9): mỗi dòng ghi lại cửa sổ
 * active (tiến trình + tiêu đề) của một client tại một thời điểm.
 *
 * <p>Giống toàn bộ DAO layer: mỗi thao tác mở connection riêng qua
 * {@code database.newConnection()}, bắt SQLException → trả giá trị an toàn,
 * không bao giờ ném ra ngoài.</p>
 */
public class ActivityDao {

    private static final Logger log = LoggerFactory.getLogger(ActivityDao.class);

    private final DatabaseManager database;

    /** Bản ghi nhật ký hoạt động (1 dòng bảng activity_logs). */
    public record ActivityRecord(
            long id,
            String clientId,
            String processName,
            String windowTitle,
            Instant recordedAt) {
    }

    public ActivityDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Ghi 1 bản ghi hoạt động (client đang mở gì).
     *
     * @return id bản ghi vừa ghi, hoặc -1 nếu lỗi/client không tồn tại (FK)
     */
    public long logActivity(String clientId, String processName, String windowTitle) {
        if (clientId == null || clientId.isBlank()) {
            return -1;
        }
        String sql = "INSERT INTO activity_logs (client_id, process_name, window_title) "
                + "VALUES (?, ?, ?)";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clientId);
            statement.setString(2, truncate(processName, 100));
            statement.setString(3, truncate(windowTitle, 2_000));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : -1;
                log.debug("Activity logged for [{}]: {} — {}", clientId, processName, windowTitle);
                return id;
            }
        } catch (SQLException e) {
            log.error("Failed to log activity for client [{}]: {}", clientId, e.getMessage());
            return -1;
        }
    }

    /**
     * Lấy {@code limit} bản ghi hoạt động GẦN NHẤT của client (mới nhất trước).
     *
     * @param clientId định danh client
     * @param limit    số bản ghi tối đa (≤ 0 → danh sách rỗng)
     */
    public List<ActivityRecord> getRecentActivities(String clientId, int limit) {
        List<ActivityRecord> activities = new ArrayList<>();
        if (clientId == null || clientId.isBlank() || limit <= 0) {
            return activities;
        }
        String sql = "SELECT id, client_id, process_name, window_title, recorded_at "
                + "FROM activity_logs WHERE client_id = ? "
                + "ORDER BY recorded_at DESC, id DESC LIMIT ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    activities.add(new ActivityRecord(
                            rs.getLong("id"),
                            rs.getString("client_id"),
                            rs.getString("process_name"),
                            rs.getString("window_title"),
                            toInstant(rs.getTimestamp("recorded_at"))));
                }
            }
            return activities;
        } catch (SQLException e) {
            log.error("Failed to read recent activities for client [{}]: {}",
                    clientId, e.getMessage());
            return activities;
        }
    }

    /** Tổng số bản ghi hoạt động của client (test/thống kê). */
    public long countAll(String clientId) {
        String sql = "SELECT COUNT(*) FROM activity_logs WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count activities for client [{}]: {}", clientId, e.getMessage());
            return 0;
        }
    }

    /** Xóa nhật ký quá cũ (giữ tối đa {@code keepDays} ngày) — dọn DB định kỳ. */
    public int purgeOlderThan(int keepDays) {
        String sql = "DELETE FROM activity_logs WHERE recorded_at < "
                + "datetime('now', '-' || ? || ' days')";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(0, keepDays));
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to purge old activities: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Xóa TOÀN BỘ nhật ký hoạt động của một client (nút "🗑️ Xóa lịch sử hoạt
     * động" trên Dashboard).
     *
     * @param clientId định danh client
     * @return số dòng đã xóa; 0 nếu client không tồn tại/lỗi/null (defensive)
     */
    public int deleteAll(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return 0;
        }
        String sql = "DELETE FROM activity_logs WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete all activities for client [{}]: {}",
                    clientId, e.getMessage());
            return 0;
        }
    }

    /** Cắt chuỗi an toàn (process_name VARCHAR(100) — window_title TEXT 2.000). */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
