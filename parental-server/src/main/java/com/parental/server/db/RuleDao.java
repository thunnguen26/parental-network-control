package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ConfigSyncPayload;

/**
 * DAO bảng {@code client_rules} + {@code blocked_domains} (Task 4.1): lấy và
 * lưu cấu hình hạn mức/thời gian, danh sách domain chặn theo từng client.
 */
public class RuleDao {

    private static final Logger log = LoggerFactory.getLogger(RuleDao.class);

    private final DatabaseManager database;

    public RuleDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Lưu cấu hình rule cho client (upsert — đè rule cũ). Chỉ lưu khi client
     * đã tồn tại (FK).
     *
     * @return {@code true} nếu thành công
     */
    public boolean upsertRule(String clientId, ConfigSyncPayload config) {
        if (config == null) {
            log.warn("Cannot upsert null rule for client [{}]", clientId);
            return false;
        }
        String sql = """
                INSERT INTO client_rules (client_id, daily_usage_limit_seconds,
                        daily_traffic_quota_bytes, allowed_start_time, allowed_end_time)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(client_id) DO UPDATE SET
                    daily_usage_limit_seconds = excluded.daily_usage_limit_seconds,
                    daily_traffic_quota_bytes = excluded.daily_traffic_quota_bytes,
                    allowed_start_time        = excluded.allowed_start_time,
                    allowed_end_time          = excluded.allowed_end_time,
                    updated_at                = CURRENT_TIMESTAMP
                """;
        try (Connection connection = database.newConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, clientId);
                statement.setLong(2, config.dailyUsageLimitSeconds());
                statement.setLong(3, config.dailyTrafficQuotaBytes());
                statement.setString(4, config.allowedStartTime());
                statement.setString(5, config.allowedEndTime());
                statement.executeUpdate();
            }
            // Đồng bộ danh sách domain + tiến trình chặn: xóa hết rồi chèn mới
            // (replace) trong 1 transaction (Mở rộng 11: kèm blockedProcesses).
            connection.setAutoCommit(false);
            try {
                replaceBlockedDomains(connection, clientId, config.blockedDomains());
                replaceBlockedProcesses(connection, clientId, config.blockedProcesses());
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            log.debug("Rule upserted for client [{}] ({} domains, {} processes)", clientId,
                    config.blockedDomains().size(), config.blockedProcesses().size());
            return true;
        } catch (SQLException e) {
            log.error("Failed to upsert rule for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    private void replaceBlockedDomains(Connection connection, String clientId,
                                       List<String> domains) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM blocked_domains WHERE client_id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR IGNORE INTO blocked_domains (client_id, domain) VALUES (?, ?)")) {
            delete.setString(1, clientId);
            delete.executeUpdate();
            if (domains != null) {
                for (String domain : domains) {
                    if (domain == null || domain.isBlank()) {
                        continue;
                    }
                    insert.setString(1, clientId);
                    insert.setString(2, domain.trim().toLowerCase(java.util.Locale.ROOT));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    /** Thay thế toàn bộ blocked_processes trong transaction (REPLACE-ALL — Mở rộng 11). */
    private void replaceBlockedProcesses(Connection connection, String clientId,
                                         List<String> processNames) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM blocked_processes WHERE client_id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT OR IGNORE INTO blocked_processes (client_id, process_name) VALUES (?, ?)")) {
            delete.setString(1, clientId);
            delete.executeUpdate();
            if (processNames != null) {
                for (String name : processNames) {
                    String clean = normalizeProcessName(name);
                    if (clean == null) {
                        continue;
                    }
                    insert.setString(1, clientId);
                    insert.setString(2, clean);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    /**
     * Thay thế toàn bộ danh sách domain chặn của client (Task 5.2 — GUI quản
     * lý blocked domains): DELETE all + INSERT OR IGNORE trong 1 transaction,
     * sanitize trim+lowercase, bỏ phần tử null/blank. Client chưa tồn tại
     * (FK) hoặc lỗi SQL → {@code false}.
     */
    public boolean setBlockedDomains(String clientId, java.util.List<String> domains) {
        try (Connection connection = database.newConnection()) {
            connection.setAutoCommit(false);
            try {
                replaceBlockedDomains(connection, clientId, domains);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            log.debug("Blocked domains replaced for client [{}]", clientId);
            return true;
        } catch (SQLException e) {
            log.error("Failed to set blocked domains for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    /** Bản ghi rule hiện hành của client (null nếu chưa có). */
    public RuleRecord getRule(String clientId) {
        String sql = """
                SELECT client_id, daily_usage_limit_seconds, daily_traffic_quota_bytes,
                       allowed_start_time, allowed_end_time
                FROM client_rules WHERE client_id = ?
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new RuleRecord(
                            rs.getString("client_id"),
                            rs.getLong("daily_usage_limit_seconds"),
                            rs.getLong("daily_traffic_quota_bytes"),
                            rs.getString("allowed_start_time"),
                            rs.getString("allowed_end_time"));
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("Failed to get rule for client [{}]: {}", clientId, e.getMessage());
            return null;
        }
    }

    /** Danh sách domain chặn của client (danh sách rỗng nếu chưa có). */
    public List<String> getBlockedDomains(String clientId) {
        String sql = "SELECT domain FROM blocked_domains WHERE client_id = ? ORDER BY domain";
        List<String> domains = new ArrayList<>();
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    domains.add(rs.getString("domain"));
                }
            }
            return domains;
        } catch (SQLException e) {
            log.error("Failed to get blocked domains for client [{}]: {}", clientId, e.getMessage());
            return domains;
        }
    }

    // ------------------------------------------------------------------
    // Mở rộng 11 — Quản lý chặn tiến trình (bảng blocked_processes)
    // ------------------------------------------------------------------

    /**
     * Danh sách tiến trình bị chặn của client (sorted A-Z, rỗng nếu chưa có;
     * defensive lỗi SQL).
     */
    public List<String> getBlockedProcesses(String clientId) {
        String sql = "SELECT process_name FROM blocked_processes WHERE client_id = ? ORDER BY process_name";
        List<String> processes = new ArrayList<>();
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    processes.add(rs.getString("process_name"));
                }
            }
            return processes;
        } catch (SQLException e) {
            // DB cũ chưa migrate → coi như chưa chặn app nào (rỗng)
            log.warn("Failed to read blocked_processes for client [{}]: {}",
                    clientId, e.getMessage());
            return processes;
        }
    }

    /**
     * Thêm 1 tiến trình vào danh sách chặn (chuẩn hóa tên qua quy tắc của
     * client: lowercase + đuôi .exe) — idempotent (INSERT OR IGNORE).
     *
     * @return {@code true} nếu hợp lệ và đã ghi (mới thêm HOẶC đã có sẵn);
     *         {@code false} nếu tên không hợp lệ/lỗi DB
     */
    public boolean addBlockedProcess(String clientId, String processName) {
        String normalized = normalizeProcessName(processName);
        if (normalized == null || clientId == null || clientId.isBlank()) {
            log.warn("Cannot add invalid process '{}' for client [{}]", processName, clientId);
            return false;
        }
        String sql = "INSERT OR IGNORE INTO blocked_processes (client_id, process_name) VALUES (?, ?)";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, normalized);
            statement.executeUpdate();
            log.info("Process '{}' blocked for client [{}]", normalized, clientId);
            return true;
        } catch (SQLException e) {
            log.error("Failed to add blocked process '{}' for client [{}]: {}",
                    processName, clientId, e.getMessage());
            return false;
        }
    }

    /**
     * GỠ chặn 1 tiến trình (Mở rộng 11 — bỏ chặn 1-click từ menu nhật ký):
     * xóa khỏi bảng {@code blocked_processes}.
     *
     * @return {@code true} nếu xóa được; {@code false} nếu không tồn tại/lỗi
     */
    public boolean removeBlockedProcess(String clientId, String processName) {
        String normalized = normalizeProcessName(processName);
        if (normalized == null || clientId == null || clientId.isBlank()) {
            return false;
        }
        String sql = "DELETE FROM blocked_processes WHERE client_id = ? AND process_name = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, normalized);
            boolean removed = statement.executeUpdate() > 0;
            if (removed) {
                log.info("Process '{}' UNBLOCKED for client [{}]", normalized, clientId);
            }
            return removed;
        } catch (SQLException e) {
            log.error("Failed to remove blocked process '{}' for client [{}]: {}",
                    processName, clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Thay thế TOÀN BỘ danh sách tiến trình chặn (REPLACE-ALL — cùng semantics
     * với {@code blockedProcesses} trong CONFIG_SYNC): DELETE all + INSERT
     * chuẩn hóa trong 1 transaction. Danh sách rỗng = dọn sạch.
     *
     * @return {@code true} nếu thành công; {@code false} nếu client lạ (FK)/lỗi
     */
    public boolean setBlockedProcesses(String clientId, List<String> processNames) {
        // Chuẩn hóa + khử trùng lặp trước transaction
        java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
        if (processNames != null) {
            for (String name : processNames) {
                String clean = normalizeProcessName(name);
                if (clean != null) {
                    normalized.add(clean);
                }
            }
        }
        try (Connection connection = database.newConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM blocked_processes WHERE client_id = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT OR IGNORE INTO blocked_processes (client_id, process_name) VALUES (?, ?)")) {
                delete.setString(1, clientId);
                delete.executeUpdate();
                for (String name : normalized) {
                    insert.setString(1, clientId);
                    insert.setString(2, name);
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            log.debug("Blocked processes replaced for client [{}] ({} processes)",
                    clientId, normalized.size());
            return true;
        } catch (SQLException e) {
            log.error("Failed to set blocked processes for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Chuẩn hóa tên tiến trình về dạng lưu DB (GIỐNG quy tắc của
     * {@code ProcessBlockerManager} phía client): trim + lowercase + đuôi
     * {@code .exe}; chỉ nhận {@code [a-z0-9._-]}.
     *
     * @return tên chuẩn hóa, hoặc {@code null} nếu không hợp lệ
     */
    static String normalizeProcessName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        if (!name.endsWith(".exe")) {
            name = name + ".exe";
        }
        if (!name.matches("[a-z0-9._-]+\\.exe") || name.equals(".exe")
                || name.contains("..")) {
            return null;
        }
        return name;
    }

    /** Bản ghi rule (DTO bất biến). */
    public record RuleRecord(
            String clientId,
            long dailyUsageLimitSeconds,
            long dailyTrafficQuotaBytes,
            String allowedStartTime,
            String allowedEndTime) {
    }
}
