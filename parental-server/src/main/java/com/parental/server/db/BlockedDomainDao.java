package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO quản lý danh sách tên miền bị chặn (Domain Blacklist Management) của
 * bảng {@code blocked_domains}.
 *
 * <p>Bảng {@code blocked_domains} đã tồn tại từ Task 4.1 (client_id + domain
 * UNIQUE, FK tới {@code clients ON DELETE CASCADE}) — xóa client tự động xóa
 * sạch danh sách domain của client đó; {@code ClientDao.deleteClient} cũng
 * xóa tường minh trong transaction.</p>
 *
 * <p>Điểm mới so với {@link RuleDao#setBlockedDomains}: chuẩn hóa domain từ
 * URL người dùng dán vào ({@code https://www.Facebook.com/} →
 * {@code facebook.com}) qua {@link #normalizeDomain(String)}, và các thao tác
 * đơn {@link #addDomain}/{@link #removeDomain} phục vụ GUI thêm/xóa tức thời.
 * Giống toàn bộ DAO layer: mỗi thao tác mở connection riêng, bắt SQLException
 * → trả giá trị an toàn, không bao giờ ném ra ngoài.</p>
 */
public class BlockedDomainDao {

    private static final Logger log = LoggerFactory.getLogger(BlockedDomainDao.class);

    /**
     * Kết quả thêm domain vào danh sách chặn — phân biệt 3 trạng thái để GUI
     * phản hồi đúng (idempotent add: trùng không phải lỗi, vẫn phải đẩy
     * CONFIG_SYNC để client áp dụng lại quy tắc chặn mới nhất).
     */
    public enum AddResult {
        /** Đã thêm domain MỚI vào danh sách. */
        ADDED,
        /** Domain ĐÃ CÓ SẴN trong danh sách — không đổi DB (idempotent). */
        ALREADY_PRESENT,
        /** Thất bại: domain không hợp lệ, client lạ (FK) hoặc lỗi DB. */
        FAILED;

        /** Thuận tiện cho GUI/test: thành công khi ADDED hoặc ALREADY_PRESENT. */
        public boolean isSuccess() {
            return this == ADDED || this == ALREADY_PRESENT;
        }
    }

    private final DatabaseManager database;

    public BlockedDomainDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Chuẩn hóa đầu vào thành domain sạch:
     * <ul>
     *   <li>trim + lowercase;</li>
     *   <li>bỏ scheme {@code http://} / {@code https://} (và {@code www.});</li>
     *   <li>bỏ path/query sau dấu {@code /} đầu tiên;</li>
     *   <li>bỏ port {@code :8080};</li>
     *   <li>bỏ {@code www.} đầu (chặn domain gốc đã đủ — hosts block khớp cả
     *       subdomain khi người dùng gõ www).</li>
     * </ul>
     *
     * @return domain sạch, hoặc {@code null} nếu đầu vào rỗng/không hợp lệ
     */
    static String normalizeDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String domain = raw.trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty()) {
            return null;
        }
        // Bỏ scheme
        domain = domain.replaceFirst("^https?://", "");
        // Bỏ path/query/fragment sau dấu / đầu tiên
        int slash = domain.indexOf('/');
        if (slash >= 0) {
            domain = domain.substring(0, slash);
        }
        // Bỏ port
        int colon = domain.indexOf(':');
        if (colon >= 0) {
            domain = domain.substring(0, colon);
        }
        // Bỏ prefix www. (lặp tối đa 1 lần — www.www.com vẫn giữ nguyên thân)
        if (domain.startsWith("www.") && domain.length() > 4) {
            domain = domain.substring(4);
        }
        domain = domain.trim();
        if (domain.isEmpty() || !domain.contains(".") || domain.startsWith(".")
                || domain.endsWith(".") || domain.contains(" ")
                || !domain.matches("[a-z0-9.-]+")) {
            return null; // không giống domain hợp lệ
        }
        return domain;
    }

    /** Danh sách domain chặn của client (sorted A-Z, rỗng nếu chưa có/lỗi). */
    public List<String> getBlockedDomains(String clientId) {
        List<String> domains = new ArrayList<>();
        if (clientId == null || clientId.isBlank()) {
            return domains;
        }
        String sql = "SELECT domain FROM blocked_domains WHERE client_id = ? ORDER BY domain";
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

    /**
     * Thêm 1 domain vào danh sách chặn (chuẩn hóa qua {@link #normalizeDomain})
     * — <b>IDEMPOTENT</b>: dùng {@code INSERT OR IGNORE}, domain trùng KHÔNG
     * phải lỗi mà là trạng thái riêng ({@link AddResult#ALREADY_PRESENT})
     * để GUI vẫn đẩy CONFIG_SYNC đồng bộ lại client (fix: trước đây trả
     * {@code false} khi trùng → GUI báo sai "lỗi DB hoặc đã có sẵn" và bỏ
     * qua push, client không được cập nhật trạng thái chặn mới nhất).
     *
     * @return {@link AddResult#ADDED} nếu thêm mới; {@link
     *         AddResult#ALREADY_PRESENT} nếu đã tồn tại; {@link
     *         AddResult#FAILED} nếu domain không hợp lệ, client chưa tồn tại
     *         (FK) hoặc lỗi DB
     */
    public AddResult addDomain(String clientId, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null || clientId == null || clientId.isBlank()) {
            log.warn("Cannot add invalid domain '{}' for client [{}]", domain, clientId);
            return AddResult.FAILED;
        }
        String sql = "INSERT OR IGNORE INTO blocked_domains (client_id, domain) VALUES (?, ?)";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, normalized);
            boolean added = statement.executeUpdate() > 0;
            if (added) {
                log.info("Domain '{}' blocked for client [{}]", normalized, clientId);
                return AddResult.ADDED;
            }
            log.info("Domain '{}' already blocked for client [{}] — idempotent, re-syncing", normalized, clientId);
            return AddResult.ALREADY_PRESENT;
        } catch (SQLException e) {
            log.error("Failed to add domain '{}' for client [{}]: {}", domain, clientId, e.getMessage());
            return AddResult.FAILED;
        }
    }

    /**
     * Xóa 1 domain khỏi danh sách chặn (đầu vào được chuẩn hóa trước khi so
     * khớp — người dùng có thể dán URL đầy đủ).
     *
     * @return {@code true} nếu xóa được; {@code false} nếu không tồn tại/lỗi
     */
    public boolean removeDomain(String clientId, String domain) {
        String normalized = normalizeDomain(domain);
        if (normalized == null || clientId == null || clientId.isBlank()) {
            return false;
        }
        String sql = "DELETE FROM blocked_domains WHERE client_id = ? AND domain = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, clientId);
            statement.setString(2, normalized);
            boolean removed = statement.executeUpdate() > 0;
            if (removed) {
                log.info("Domain '{}' unblocked for client [{}]", normalized, clientId);
            }
            return removed;
        } catch (SQLException e) {
            log.error("Failed to remove domain '{}' for client [{}]: {}", domain, clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Thay thế TOÀN BỘ danh sách domain chặn của client: DELETE all + INSERT
     * trong MỘT TRANSACTION (atomic — all-or-nothing). Mỗi domain được chuẩn
     * hóa + khử trùng lặp trước khi ghi. Client chưa tồn tại (FK) → rollback
     * và trả {@code false}.
     */
    public boolean setBlockedDomains(String clientId, List<String> domains) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        // Chuẩn hóa + khử trùng lặp ngoài transaction
        Set<String> normalized = new LinkedHashSet<>();
        if (domains != null) {
            for (String domain : domains) {
                String clean = normalizeDomain(domain);
                if (clean != null) {
                    normalized.add(clean);
                }
            }
        }
        Connection connection = null;
        try {
            connection = database.newConnection();
            connection.setAutoCommit(false); // BEGIN — atomic
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM blocked_domains WHERE client_id = ?");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT OR IGNORE INTO blocked_domains (client_id, domain) VALUES (?, ?)")) {
                delete.setString(1, clientId);
                delete.executeUpdate();
                for (String domain : normalized) {
                    insert.setString(1, clientId);
                    insert.setString(2, domain);
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
                log.info("Blocked domains replaced for client [{}] ({} domains)",
                        clientId, normalized.size());
                return true;
            }
        } catch (SQLException e) {
            log.error("Failed to set blocked domains for client [{}]: {} — rolling back",
                    clientId, e.getMessage());
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    log.error("Rollback failed: {}", rollbackError.getMessage());
                }
            }
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                } catch (SQLException e) {
                    log.debug("Connection close ignored: {}", e.getMessage());
                }
            }
        }
    }
}
