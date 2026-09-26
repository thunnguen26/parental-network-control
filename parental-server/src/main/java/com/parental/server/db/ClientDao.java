package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO bảng {@code clients} (Task 4.1): upsert thông tin thiết bị khi
 * HANDSHAKE (set ONLINE + last_seen), cập nhật OFFLINE khi ngắt kết nối.
 * Tách biệt hoàn toàn khỏi TCP Engine.
 */
public class ClientDao {

    private static final Logger log = LoggerFactory.getLogger(ClientDao.class);

    private final DatabaseManager database;

    public ClientDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Upsert thông tin thiết bị khi nhận HANDSHAKE: có sẵn → UPDATE toàn bộ
     * thông tin + status ONLINE + last_seen; chưa có → INSERT mới. Idempotent,
     * an toàn khi client reconnect nhiều lần.
     *
     * @return {@code true} nếu ghi thành công
     */
    public boolean upsertClient(ClientRecord record) {
        String sql = """
                INSERT INTO clients (client_id, device_name, device_type, os_version,
                                     ip_address, mac_address, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ONLINE', CURRENT_TIMESTAMP)
                ON CONFLICT(client_id) DO UPDATE SET
                    device_name  = excluded.device_name,
                    device_type  = excluded.device_type,
                    os_version   = excluded.os_version,
                    ip_address   = excluded.ip_address,
                    mac_address  = excluded.mac_address,
                    status       = 'ONLINE',
                    last_seen_at = CURRENT_TIMESTAMP
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.clientId());
            statement.setString(2, record.deviceName());
            statement.setString(3, record.deviceType());
            statement.setString(4, record.osVersion());
            statement.setString(5, record.ipAddress());
            statement.setString(6, record.macAddress());
            statement.executeUpdate();
            log.debug("Client [{}] upserted (ONLINE)", record.clientId());
            return true;
        } catch (SQLException e) {
            log.error("Failed to upsert client [{}]: {}", record.clientId(), e.getMessage());
            return false;
        }
    }

    /** Đổi trạng thái client (ONLINE/OFFLINE). Client chưa tồn tại → no-op. */
    public boolean setStatus(String clientId, String status) {
        String sql = "UPDATE clients SET status = ?, last_seen_at = CURRENT_TIMESTAMP "
                + "WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, clientId);
            int updated = statement.executeUpdate();
            if (updated > 0) {
                log.debug("Client [{}] set status {}", clientId, status);
            }
            return updated > 0;
        } catch (SQLException e) {
            log.error("Failed to set status {} for client [{}]: {}", status, clientId, e.getMessage());
            return false;
        }
    }

    /** Cập nhật last_seen_at = hiện tại (dùng khi nhận HEARTBEAT nếu cần). */
    public boolean touchLastSeen(String clientId) {
        String sql = "UPDATE clients SET last_seen_at = CURRENT_TIMESTAMP WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to touch last_seen for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    /** Lấy bản ghi client (null nếu không tồn tại). */
    public ClientRecord get(String clientId) {
        String sql = "SELECT client_id, device_name, device_type, os_version, "
                + "ip_address, mac_address, status FROM clients WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new ClientRecord(
                            rs.getString("client_id"),
                            rs.getString("device_name"),
                            rs.getString("device_type"),
                            rs.getString("os_version"),
                            rs.getString("ip_address"),
                            rs.getString("mac_address"),
                            rs.getString("status"));
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("Failed to get client [{}]: {}", clientId, e.getMessage());
            return null;
        }
    }

    /** Trạng thái hiện hành của client (null nếu không tồn tại). */
    public String getStatus(String clientId) {
        ClientRecord record = get(clientId);
        return record != null ? record.status() : null;
    }

    /** Giá trị last_seen_at (Instant; null nếu không tồn tại). */
    public Instant getLastSeen(String clientId) {
        String sql = "SELECT last_seen_at FROM clients WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    Timestamp timestamp = rs.getTimestamp("last_seen_at");
                    return timestamp != null ? timestamp.toInstant() : null;
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("Failed to get last_seen for client [{}]: {}", clientId, e.getMessage());
            return null;
        }
    }

    /** Client tồn tại trong DB? */
    public boolean exists(String clientId) {
        String sql = "SELECT 1 FROM clients WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to check existence of client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }
}
