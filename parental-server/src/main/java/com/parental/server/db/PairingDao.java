package com.parental.server.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO bảng {@code pairing_sessions} — sinh và xác thực mã PIN ghép đôi
 * 6 chữ số (Device Pairing / Zero-Config Setup).
 *
 * <p>Vòng đời mã PIN:</p>
 * <ol>
 *   <li>{@link #createPin()}: sinh ngẫu nhiên bằng {@link SecureRandom},
 *       hiệu lực 5 phút, lưu với {@code is_used = 0}; mã mới xóa các PIN
 *       đã hết hạn cho gọn bảng.</li>
 *   <li>{@link #validateAndConsumePin(String)}: kiểm tra PIN tồn tại, còn
 *       hạn, chưa dùng → đánh dấu {@code is_used = 1} (mỗi PIN chỉ ghép
 *       đôi được MỘT thiết bị) và trả {@code true}.</li>
 * </ol>
 *
 * <p>Giống toàn bộ DAO layer: mỗi thao tác mở connection riêng qua
 * {@code database.newConnection()}, bắt SQLException → trả giá trị an toàn,
 * không bao giờ ném ra ngoài.</p>
 */
public class PairingDao {

    /** Hiệu lực của mã PIN ghép đôi — 5 phút theo yêu cầu Roadmap. */
    public static final long PIN_VALIDITY_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(PairingDao.class);

    private final DatabaseManager database;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Bản ghi một phiên ghép đôi (1 dòng bảng pairing_sessions). */
    public record PairingSession(String pin, Instant createdAt, Instant expiresAt, boolean used) {
    }

    /** Trạng thái của mã PIN dùng cho lý do từ chối PAIR_RESPONSE (Mục 3.7). */
    public enum PinState {
        /** Còn hạn, chưa dùng — hợp lệ để ghép đôi. */
        ACTIVE,
        /** Đã quá thời hạn hiệu lực 5 phút. */
        EXPIRED,
        /** Đã được một thiết bị tiêu thụ (is_used = 1). */
        USED,
        /** Không tồn tại trong DB. */
        NOT_FOUND
    }

    public PairingDao(DatabaseManager database) {
        this.database = database;
    }

    /**
     * Sinh mã PIN ngẫu nhiên 6 chữ số ({@code SecureRandom}), hiệu lực
     * 5 phút, chưa dùng. Đảm bảo mã mới không trùng mã còn hạn chưa dùng
     * (thử tối đa 20 lần). Dọn dẹp PIN hết hạn trước khi sinh.
     *
     * @return mã PIN 6 chữ số dạng chuỗi, hoặc {@code null} nếu lỗi DB
     */
    public String createPin() {
        purgeExpiredPins();
        // GHI CHÚ MÚI GIỜI (bài học test): mọi timestamp của bảng này PHẢI được
        // ghi bằng JDBC setTimestamp (driver format theo TZ local) — KHÔNG dùng
        // DEFAULT CURRENT_TIMESTAMP của SQLite (UTC). Trộn 2 kiểu ghi làm lệch
        // so sánh chuỗi SQL đúng bằng offset múi giờ (VN +07:00 → lệch 7h).
        String sql = "INSERT INTO pairing_sessions (pin, created_at, expires_at, is_used) VALUES (?, ?, ?, 0)";
        for (int attempt = 0; attempt < 20; attempt++) {
            String pin = String.format("%06d", secureRandom.nextInt(1_000_000));
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(PIN_VALIDITY_SECONDS);
            try (Connection connection = database.newConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, pin);
                statement.setTimestamp(2, Timestamp.from(now));
                statement.setTimestamp(3, Timestamp.from(expiresAt));
                statement.executeUpdate();
                log.info("Pairing PIN created (***{}), valid for {}s", pin.substring(4),
                        PIN_VALIDITY_SECONDS);
                return pin;
            } catch (SQLException e) {
                // Trùng PRIMARY KEY (PIN đang còn hạn) → thử mã khác.
                if (!isConstraintViolation(e)) {
                    log.error("Failed to create pairing PIN: {}", e.getMessage());
                    return null;
                }
            }
        }
        log.error("Cannot generate a unique active PIN after 20 attempts");
        return null;
    }

    /**
     * Xác thực và tiêu thụ mã PIN: đúng mã + còn hạn + chưa dùng → đánh dấu
     * {@code is_used = 1} và trả {@code true}. Mỗi PIN chỉ dùng được một lần;
     * PIN sai / hết hạn / đã dùng đều trả {@code false} (PAIR_RESPONSE trả
     * lý do tương ứng {@code INVALID_PIN} / {@code PIN_EXPIRED} /
     * {@code PIN_ALREADY_USED} qua {@link #getPinState(String)}).
     */
    public boolean validateAndConsumePin(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            return false;
        }
        // SELECT rồi UPDATE giữ ngữ nghĩa "xác thực rồi mới tiêu thụ" — bảng
        // pairing_sessions chỉ có phụ huynh GUI sinh PIN (một phiên mỗi lần),
        // xung đột hai thiết bị nhập cùng lúc là biên hiếm và chấp nhận được
        // (SQLite serialize write per-database).
        Instant now = Instant.now();
        Integer state = getPinStateInternal(pin, now);
        if (state == null || state != PIN_STATE_ACTIVE) {
            return false;
        }
        String sql = "UPDATE pairing_sessions SET is_used = 1 WHERE pin = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pin);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to consume pairing PIN: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Trạng thái hiện tại của mã PIN — dùng để trả lý do từ chối chính xác
     * trong PAIR_RESPONSE: {@code ACTIVE} / {@code EXPIRED} / {@code USED} /
     * {@code NOT_FOUND}.
     */
    public PinState getPinState(String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            return PinState.NOT_FOUND;
        }
        Integer state = getPinStateInternal(pin, Instant.now());
        if (state == null) {
            return PinState.NOT_FOUND;
        }
        return switch (state) {
            case PIN_STATE_ACTIVE -> PinState.ACTIVE;
            case PIN_STATE_EXPIRED -> PinState.EXPIRED;
            default -> PinState.USED;
        };
    }

    /** Phiên ghép đôi theo PIN (null nếu không tồn tại). */
    public PairingSession get(String pin) {
        String sql = "SELECT pin, created_at, expires_at, is_used FROM pairing_sessions WHERE pin = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pin);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new PairingSession(
                            rs.getString("pin"),
                            toInstant(rs.getTimestamp("created_at")),
                            toInstant(rs.getTimestamp("expires_at")),
                            rs.getInt("is_used") == 1);
                }
                return null;
            }
        } catch (SQLException e) {
            log.error("Failed to read pairing session: {}", e.getMessage());
            return null;
        }
    }

    /** Xóa toàn bộ mã PIN đã hết hạn khỏi bảng. Trả về số dòng đã xóa. */
    public int purgeExpiredPins() {
        // So sánh bằng tham số do driver ghi (KHÔNG dùng CURRENT_TIMESTAMP của
        // SQLite — UTC) để không lệch múi giờ với expires_at do driver ghi.
        String sql = "DELETE FROM pairing_sessions WHERE expires_at < ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            return statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to purge expired pairing PINs: {}", e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private static final int PIN_STATE_ACTIVE = 0;
    private static final int PIN_STATE_EXPIRED = 1;
    private static final int PIN_STATE_USED = 2;

    /** Trạng thái nội bộ: ACTIVE/EXPIRED/USED, hoặc null nếu không có dòng. */
    private Integer getPinStateInternal(String pin, Instant now) {
        String sql = "SELECT expires_at, is_used FROM pairing_sessions WHERE pin = ?";
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, pin);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                boolean expired = expiresAt == null || expiresAt.toInstant().isBefore(now);
                if (rs.getInt("is_used") == 1) {
                    return PIN_STATE_USED;
                }
                return expired ? PIN_STATE_EXPIRED : PIN_STATE_ACTIVE;
            }
        } catch (SQLException e) {
            log.error("Failed to read pairing PIN state: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isConstraintViolation(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return message.contains("unique") || message.contains("primary key")
                || message.contains("constraint");
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : null;
    }
}
