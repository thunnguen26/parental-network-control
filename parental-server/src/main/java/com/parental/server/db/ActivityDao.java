package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
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

    /**
     * Biểu thức ngày LOCAL cho {@code recorded_at} — cột TIMESTAMP nhưng
     * sqlite-jdbc lưu {@code java.sql.Timestamp} dạng epoch MILLIS (INTEGER).
     * {@code DATE(recorded_at)} trên INTEGER trả NULL, nên phải quy đổi
     * {@code /1000, 'unixepoch', 'localtime'}. Vẫn hỗ trợ dòng TEXT cũ
     * (DEFAULT CURRENT_TIMESTAMP) để không mất dữ liệu legacy.
     */
    private static final String LOCAL_RECORDED_DATE =
            "CASE WHEN typeof(recorded_at) = 'integer' "
            + "THEN DATE(recorded_at / 1000, 'unixepoch', 'localtime') "
            + "ELSE DATE(recorded_at) END";

    private final DatabaseManager database;

    /** Bản ghi nhật ký hoạt động (1 dòng bảng activity_logs). */
    public record ActivityRecord(
            long id,
            String clientId,
            String processName,
            String windowTitle,
            String activeUrlOrDomain,
            Instant recordedAt) {

        /** Constructor 5 tham số (tương thích code cũ) — URL rỗng. */
        public ActivityRecord(long id, String clientId, String processName,
                              String windowTitle, Instant recordedAt) {
            this(id, clientId, processName, windowTitle, null, recordedAt);
        }
    }

    public ActivityDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Ghi 1 bản ghi hoạt động (client đang mở gì) — giữ chữ ký 3 tham số cho
     * tương thích: ủy quyền {@link #logActivity(String, String, String, String)}
     * với URL rỗng.
     *
     * @return id bản ghi vừa ghi, hoặc -1 nếu lỗi/client không tồn tại (FK)
     */
    public long logActivity(String clientId, String processName, String windowTitle) {
        return logActivity(clientId, processName, windowTitle, null);
    }

    /**
     * Ghi 1 bản ghi hoạt động kèm URL/domain bóc tách từ thanh địa chỉ
     * (Mở rộng 10 — dùng cho menu "Chặn nhanh" 1-click).
     *
     * @param clientId        định danh client
     * @param processName     tên tiến trình active
     * @param windowTitle     tiêu đề cửa sổ active
     * @param activeUrlOrDomain URL/domain từ address bar (null/rỗng nếu không có)
     * @return id bản ghi vừa ghi, hoặc -1 nếu lỗi/client không tồn tại (FK)
     */
    public long logActivity(String clientId, String processName, String windowTitle,
                            String activeUrlOrDomain) {
        if (clientId == null || clientId.isBlank()) {
            return -1;
        }
        String sql = "INSERT INTO activity_logs (client_id, process_name, window_title, "
                + "active_url_or_domain, recorded_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql,
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, clientId);
            statement.setString(2, truncate(processName, 100));
            statement.setString(3, truncate(windowTitle, 2_000));
            statement.setString(4, truncate(activeUrlOrDomain, 500));
            // GHI TƯỜNG MINH giờ LOCAL (hệ máy SERVER) thay vì DEFAULT
            // CURRENT_TIMESTAMP (UTC của SQLite) — hết lệch múi giờ khi hiển thị
            // trên JTable so với Event Log; getTimestamp() đọc lại khớp tuyệt đối.
            statement.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
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
        String sql = "SELECT id, client_id, process_name, window_title, "
                + "COALESCE(active_url_or_domain, '') AS active_url_or_domain, recorded_at "
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
                            rs.getString("active_url_or_domain"),
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

    /**
     * Số bản ghi tối đa hiển thị trong dialog "Xem toàn bộ" nhật ký hoạt động.
     */
    public static final int FULL_VIEW_LIMIT = 100;

    /**
     * Lấy nhiều bản ghi hơn cho dialog "Xem toàn bộ" (mặc định 100 gần nhất,
     * mới nhất trước). Khác {@link #getRecentActivities(String, int)}: limit
     * ≤ 0 → dùng {@link #FULL_VIEW_LIMIT} thay vì trả rỗng.
     */
    public List<ActivityRecord> getRecentActivitiesForDialog(String clientId, int limit) {
        int effective = limit > 0 ? limit : FULL_VIEW_LIMIT;
        return getRecentActivities(clientId, effective);
    }

    /** Số lần một tiến trình xuất hiện trong nhật ký (KHÔNG phải thời gian dùng). */
    public record ProcessCount(String processName, long events) {
    }

    /**
     * Đếm số LẦN XUẤT HIỆN của từng tiến trình trong nhật ký hoạt động theo
     * khoảng ngày LOCAL {@code [from, to]}.
     *
     * <p><b>Không phải thời lượng:</b> bảng {@code activity_logs} chỉ ghi khi
     * cửa sổ active thay đổi (đã dedup), không có mẫu thời gian đều đặn — vì
     * vậy chỉ được hiển thị "số lần ghi nhận", tuyệt đối không nhân với chu
     * kỳ để bịa ra thời gian sử dụng.</p>
     *
     * @param limit số dòng tối đa (≤ 0 → dùng 10)
     */
    public List<ProcessCount> countByProcess(String clientId, java.time.LocalDate from,
                                             java.time.LocalDate to, int limit) {
        List<ProcessCount> result = new ArrayList<>();
        if (clientId == null || clientId.isBlank() || from == null || to == null
                || from.isAfter(to)) {
            return result;
        }
        int effectiveLimit = limit > 0 ? limit : 10;
        String sql = "SELECT COALESCE(NULLIF(TRIM(process_name), ''), '(không xác định)') "
                + "AS process_label, COUNT(*) AS events FROM activity_logs "
                + "WHERE client_id = ? AND " + LOCAL_RECORDED_DATE + " BETWEEN ? AND ? "
                + "GROUP BY process_label ORDER BY events DESC, process_label ASC LIMIT ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, from.toString());
            statement.setString(3, to.toString());
            statement.setInt(4, effectiveLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new ProcessCount(rs.getString("process_label"),
                            Math.max(0, rs.getLong("events"))));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to count activities by process for client [{}]: {}",
                    clientId, e.getMessage());
        }
        return result;
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
        // recorded_at lưu epoch millis (INTEGER) — so sánh trực tiếp với chuỗi
        // datetime() sẽ sai kiểu (INTEGER luôn < TEXT trong SQLite → xóa nhầm
        // toàn bộ). Quy đổi về ngày LOCAL rồi so sánh theo ngày.
        String sql = "DELETE FROM activity_logs WHERE " + LOCAL_RECORDED_DATE
                + " < DATE('now', 'localtime', '-' || ? || ' days')";
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
     * @return true nếu có ít nhất 1 dòng bị xóa (hoặc client sạch sẵn), false khi lỗi
     */
    public boolean deleteAll(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        String sql = "DELETE FROM activity_logs WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.executeUpdate();
            return true; // DELETE hợp lệ kể cả 0 dòng (client sạch sẵn)
        } catch (SQLException e) {
            log.error("Failed to delete all activities for client [{}]: {}",
                    clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Dọn dữ liệu RÁC từ era script PowerShell lỗi (hotfix 2026-09-27):
     * các dòng nhầm chuỗi lỗi PowerShell làm tiến trình — process_name chứa
     * "ACTIVE" (vd "ACTIVE.exe") hoặc window_title chứa "not recognized".
     * Gọi MỘT LẦN khi mở Dashboard.
     *
     * @return số dòng rác đã xóa; 0 nếu sạch/lỗi
     */
    public int purgeCorruptedRows() {
        String sql = "DELETE FROM activity_logs WHERE process_name LIKE '%ACTIVE%' "
                + "OR window_title LIKE '%recognized%'";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to purge corrupted activity rows: {}", e.getMessage());
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
