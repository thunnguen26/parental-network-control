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
            // Đồng bộ danh sách domain chặn: xóa hết rồi chèn mới (replace) trong 1 transaction
            connection.setAutoCommit(false);
            try {
                replaceBlockedDomains(connection, clientId, config.blockedDomains());
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            log.debug("Rule upserted for client [{}] ({} domains)", clientId,
                    config.blockedDomains().size());
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

    /** Bản ghi rule (DTO bất biến). */
    public record RuleRecord(
            String clientId,
            long dailyUsageLimitSeconds,
            long dailyTrafficQuotaBytes,
            String allowedStartTime,
            String allowedEndTime) {
    }
}
