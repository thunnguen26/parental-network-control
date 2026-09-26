package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.ActionType;

/**
 * DAO bảng {@code command_logs} (Task 4.1): ghi nhận lệnh COMMAND gửi đi và
 * cập nhật trạng thái ACKED khi nhận phản hồi từ client.
 */
public class CommandLogDao {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_ACKED = "ACKED";

    private static final Logger log = LoggerFactory.getLogger(CommandLogDao.class);

    private final DatabaseManager database;

    public CommandLogDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Ghi nhận lệnh COMMAND đã gửi xuống client (status = SENT).
     *
     * @return {@code true} nếu ghi thành công
     */
    public boolean recordSent(String commandId, String clientId, ActionType action, String reason) {
        String sql = """
                INSERT INTO command_logs (command_id, client_id, action, reason, status)
                VALUES (?, ?, ?, ?, 'SENT')
                ON CONFLICT(command_id) DO UPDATE SET
                    client_id = excluded.client_id,
                    action    = excluded.action,
                    reason    = excluded.reason,
                    sent_at   = CURRENT_TIMESTAMP
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId);
            statement.setString(2, clientId);
            statement.setString(3, action != null ? action.name() : "UNKNOWN");
            statement.setString(4, reason);
            statement.executeUpdate();
            log.debug("Command [{}] logged as SENT for client [{}]", commandId, clientId);
            return true;
        } catch (SQLException e) {
            log.error("Failed to log command [{}]: {}", commandId, e.getMessage());
            return false;
        }
    }

    /**
     * Đánh dấu lệnh đã được client tiếp nhận (status = ACKED + acked_at).
     * Idempotent: ACK lặp vẫn ghi đè acknowledged_at.
     *
     * @return {@code true} nếu có lệnh được cập nhật
     */
    public boolean markAcked(String commandId) {
        String sql = """
                UPDATE command_logs
                SET status = 'ACKED', acked_at = CURRENT_TIMESTAMP
                WHERE command_id = ?
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId);
            boolean updated = statement.executeUpdate() > 0;
            if (updated) {
                log.debug("Command [{}] marked ACKED", commandId);
            }
            return updated;
        } catch (SQLException e) {
            log.error("Failed to mark command [{}] as ACKED: {}", commandId, e.getMessage());
            return false;
        }
    }

    /** Trạng thái hiện hành của lệnh (SENT/ACKED; null nếu không tồn tại). */
    public String getStatus(String commandId) {
        String sql = "SELECT status FROM command_logs WHERE command_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        } catch (SQLException e) {
            log.error("Failed to get status of command [{}]: {}", commandId, e.getMessage());
            return null;
        }
    }

    /** Số lệnh theo trạng thái (thống kê cho GUI/Rule Engine). */
    public long countByStatus(String status) {
        String sql = "SELECT COUNT(*) FROM command_logs WHERE status = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("Failed to count commands with status {}: {}", status, e.getMessage());
            return 0L;
        }
    }
}
