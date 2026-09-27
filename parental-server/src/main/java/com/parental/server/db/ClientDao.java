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
 * DAO bảng {@code clients} (Task 4.1 + Device Pairing): upsert thông tin
 * thiết bị khi HANDSHAKE (set ONLINE + last_seen), cập nhật OFFLINE khi ngắt
 * kết nối; lưu/kiểm tra {@code device_token} cho cơ chế ghép đôi.
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
     * <p><b>Identity Persistence</b>: UPSERT theo {@code clientId} giữ nguyên
     * {@code device_token}/{@code is_paired} — mỗi thiết bị luôn là 1 dòng
     * duy nhất, reconnect chỉ cập nhật trạng thái ONLINE/OFFLINE trên dòng
     * đó, không bao giờ nhân bản.</p>
     *
     * @return {@code true} nếu ghi thành công
     */
    public boolean upsertClient(ClientRecord record) {
        // CẬP NHẬT device info kể cả khi dòng chỉ tồn tại nhờ saveDeviceToken
        // (device_name IS NULL) — handshake của thiết bị ghép đôi xong quay lại
        // phải điền đủ tên máy, không để dòng "vô danh" trên Dashboard.
        String sql = """
                INSERT INTO clients (client_id, device_name, device_type, os_version,
                                     ip_address, mac_address, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ONLINE', CURRENT_TIMESTAMP)
                ON CONFLICT(client_id) DO UPDATE SET
                    device_name  = COALESCE(excluded.device_name, device_name),
                    device_type  = COALESCE(excluded.device_type, device_type),
                    os_version   = COALESCE(excluded.os_version, os_version),
                    ip_address   = COALESCE(excluded.ip_address, ip_address),
                    mac_address  = COALESCE(excluded.mac_address, mac_address),
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

    /**
     * Kiểm tra cặp {@code clientId} + {@code deviceToken} khớp với một thiết
     * bị ĐÃ ghép đôi trong DB (xác thực HANDSHAKE).
     */
    public boolean isValidPairing(String clientId, String deviceToken) {
        if (clientId == null || clientId.isBlank() || deviceToken == null || deviceToken.isBlank()) {
            return false;
        }
        String sql = "SELECT 1 FROM clients WHERE client_id = ? AND device_token = ? AND is_paired = 1";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, deviceToken);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to validate pairing for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Lưu (cấp mới hoặc thay thế) {@code deviceToken} cho client và đánh dấu
     * ĐÃ ghép đôi — dùng khi PAIR_REQUEST thành công. UPSERT theo clientId:
     * thiết bị chưa có trong DB → INSERT mới; đã có (dù chưa paired) → cập
     * nhật token trên CÙNG một dòng.
     *
     * <p>Lưu ý: KHÔNG set {@code status} khi insert dòng mới — handshake
     * tiếp theo sẽ upsert đủ thông tin thiết bị và chuyển ONLINE. Nếu insert
     * với {@code status='OFFLINE'} mà handshake không chạy ngay (thiết bị ghép
     * đôi xong tắt máy), Dashboard sẽ treo vĩnh viễn 1 dòng Offline "vô danh"
     * vì upsertClient không thể phân biệt dòng này với thiết bị cũ thật.</p>
     */
    public boolean saveDeviceToken(String clientId, String deviceToken) {
        String sql = """
                INSERT INTO clients (client_id, device_token, is_paired)
                VALUES (?, ?, 1)
                ON CONFLICT(client_id) DO UPDATE SET
                    device_token = excluded.device_token,
                    is_paired    = 1
                """;
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, deviceToken);
            statement.executeUpdate();
            log.info("Device token saved for client [{}] (is_paired=1)", clientId);
            return true;
        } catch (SQLException e) {
            log.error("Failed to save device token for client [{}]: {}", clientId, e.getMessage());
            return false;
        }
    }

    /** Trạng thái ghép đôi của client (false nếu chưa tồn tại/chưa ghép đôi). */
    public boolean isPaired(String clientId) {
        String sql = "SELECT is_paired FROM clients WHERE client_id = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt("is_paired") == 1;
            }
        } catch (SQLException e) {
            log.error("Failed to check pairing status of client [{}]: {}", clientId, e.getMessage());
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

    /**
     * Toàn bộ client trong DB (ONLINE đứng trước, cùng trạng thái xếp theo
     * tên máy) — dùng cho Dashboard GUI (Task 5.1). Danh sách rỗng nếu lỗi.
     */
    public List<ClientRecord> getAllClients() {
        String sql = "SELECT client_id, device_name, device_type, os_version, "
                + "ip_address, mac_address, status FROM clients "
                + "ORDER BY CASE WHEN status = 'ONLINE' THEN 0 ELSE 1 END, device_name";
        List<ClientRecord> clients = new ArrayList<>();
        try (Connection connection = database.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                clients.add(new ClientRecord(
                        rs.getString("client_id"),
                        rs.getString("device_name"),
                        rs.getString("device_type"),
                        rs.getString("os_version"),
                        rs.getString("ip_address"),
                        rs.getString("mac_address"),
                        rs.getString("status")));
            }
            return clients;
        } catch (SQLException e) {
            log.error("Failed to list clients: {}", e.getMessage());
            return clients;
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
