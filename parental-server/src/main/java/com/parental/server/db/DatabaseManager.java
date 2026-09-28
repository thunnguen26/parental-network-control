package com.parental.server.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quản lý kết nối SQLite (Task 4.1).
 *
 * <p><b>Đặc điểm SQLite quan trọng</b> (đã kiểm chứng qua test): DB
 * {@code :memory:} tồn tại theo EACH CONNECTION — 2 connection là 2 DB riêng.
 * Vì DAO mở connection cho từng thao tác, manager phải:</p>
 * <ol>
 *   <li>Dùng URI {@code file:<tên>?mode=memory&cache=shared} để các connection
 *       trong cùng process chia sẻ CÙNG một DB in-memory;</li>
 *   <li>Giữ một <b>anchor connection</b> mở suốt vòng đời manager — DB sống
 *       chừng nào còn ít nhất 1 connection mở.</li>
 * </ol>
 *
 * <p>Sử dụng: {@code new DatabaseManager(Path.of("parental_control.db"))} cho
 * runtime; {@code new DatabaseManager()} tạo DB in-memory RIÊNG cho từng
 * instance (tên unique — các test không nhiễm dữ liệu chéo nhau và không đụng
 * file thật); hoặc truyền JDBC URL trực tiếp khi cần chia sẻ giữa nhiều
 * manager trong test.</p>
 */
public class DatabaseManager {

    /** URL in-memory chia sẻ toàn JVM (dùng khi cần nhiều manager cùng DB). */
    public static final String MEMORY_URL = "jdbc:sqlite:file:parental_shared_mem?mode=memory&cache=shared";
    public static final String DEFAULT_DB_FILE = "parental_control.db";

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String jdbcUrl;
    private final boolean memoryDatabase;
    /** Connection giữ DB in-memory sống (null với file DB). Không bao giờ đóng. */
    private Connection anchorConnection;

    /** DB in-memory riêng cho instance này (tên unique — không nhiễm chéo). */
    public DatabaseManager() {
        this("jdbc:sqlite:file:parental-mem-" + UUID.randomUUID() + "?mode=memory&cache=shared");
    }

    /** DB file tại đường dẫn chỉ định (runtime). */
    public DatabaseManager(Path dbFilePath) {
        this("jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize());
    }

    /** JDBC URL trực tiếp — memory cần dạng {@code file:<tên>?mode=memory&cache=shared}. */
    public DatabaseManager(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.memoryDatabase = jdbcUrl.contains("mode=memory") || jdbcUrl.contains(":memory:");
        initialize();
    }

    /** URL JDBC hiện hành (dùng cho log/test). */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /** Mở một Connection mới (DAO tự đóng sau khi dùng — try-with-resources). */
    public Connection newConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            // foreign_keys phải bật trên MỖI connection (SQLite per-connection flag)
            statement.execute("PRAGMA foreign_keys=ON;");
            // Chờ lock tối đa 5s thay vì fail ngay SQLITE_BUSY khi nhiều thread
            // ghi/đọc đồng thời (DAO mở connection per-operation, handler chạy
            // song song trên pool). Với DB in-memory cache=shared không dùng WAL,
            // busy_timeout không áp dụng cho table-lock — vì vậy test tích hợp
            // đa luồng phải dùng file DB (có WAL, reader không chặn writer).
            statement.execute("PRAGMA busy_timeout=5000;");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Khởi tạo: bật WAL (file DB) + tạo/migrate schema. Idempotent. */
    private void initialize() {
        try {
            if (memoryDatabase) {
                // Anchor giữ DB sống: với cache=shared, DB mất khi connection cuối đóng
                anchorConnection = DriverManager.getConnection(jdbcUrl);
            }
            try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
                try (Statement statement = connection.createStatement()) {
                    if (!memoryDatabase) {
                        // WAL chỉ áp dụng cho file DB; :memory: mặc định đã tối ưu
                        statement.execute("PRAGMA journal_mode=WAL;");
                    }
                }
                createSchema(connection);
            }
            log.info("Database initialized at {} (schema ready)", jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database: " + jdbcUrl, e);
        }
    }

    /**
     * Tạo schema theo Task 4.1 + Device Pairing (Identity Persistence) —
     * idempotent (IF NOT EXISTS). Migration cột mới trên DB cũ dùng
     * ALTER TABLE ... ADD COLUMN được bọc riêng vì SQLite không hỗ trợ
     * "ADD COLUMN IF NOT EXISTS" (cột tồn tại → báo lỗi, được nuốt an toàn).
     */
    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS clients (
                        client_id     VARCHAR PRIMARY KEY,
                        device_name   VARCHAR,
                        device_type   VARCHAR,
                        os_version    VARCHAR,
                        ip_address    VARCHAR,
                        mac_address   VARCHAR,
                        status        VARCHAR NOT NULL DEFAULT 'OFFLINE',
                        device_token  VARCHAR,
                        is_paired     BOOLEAN NOT NULL DEFAULT 0,
                        alias         VARCHAR(100),
                        created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_seen_at  TIMESTAMP
                    )
                    """);

            // Migration cho DB tạo từ Task 4.1 (chưa có cột token/paired/alias).
            addColumnIfMissing(connection, "clients", "device_token", "VARCHAR");
            addColumnIfMissing(connection, "clients", "is_paired",
                    "BOOLEAN NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "clients", "alias", "VARCHAR(100)");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pairing_sessions (
                        pin         VARCHAR(6) PRIMARY KEY,
                        created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at  TIMESTAMP NOT NULL,
                        is_used     BOOLEAN NOT NULL DEFAULT 0
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metrics_logs (
                        id                        INTEGER PRIMARY KEY AUTOINCREMENT,
                        client_id                 VARCHAR NOT NULL,
                        download_bytes            INTEGER NOT NULL DEFAULT 0,
                        upload_bytes              INTEGER NOT NULL DEFAULT 0,
                        download_speed_bps        INTEGER NOT NULL DEFAULT 0,
                        upload_speed_bps          INTEGER NOT NULL DEFAULT 0,
                        session_duration_seconds  INTEGER NOT NULL DEFAULT 0,
                        network_status            VARCHAR NOT NULL DEFAULT 'ACTIVE',
                        recorded_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS client_rules (
                        client_id                  VARCHAR PRIMARY KEY,
                        daily_usage_limit_seconds  INTEGER NOT NULL DEFAULT 0,
                        daily_traffic_quota_bytes  INTEGER NOT NULL DEFAULT 0,
                        allowed_start_time         VARCHAR,
                        allowed_end_time           VARCHAR,
                        updated_at                 TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS blocked_domains (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        client_id VARCHAR NOT NULL,
                        domain    VARCHAR NOT NULL,
                        UNIQUE(client_id, domain),
                        FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
                    )
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS command_logs (
                        command_id  VARCHAR PRIMARY KEY,
                        client_id   VARCHAR NOT NULL,
                        action      VARCHAR NOT NULL,
                        reason      VARCHAR,
                        status      VARCHAR NOT NULL DEFAULT 'SENT',
                        sent_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        acked_at    TIMESTAMP,
                        FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
                    )
                    """);

            // Activity Monitoring (PROTOCOL.md Mục 3.9): nhật ký hoạt động —
            // mỗi dòng = cửa sổ active của client tại 1 thời điểm.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS activity_logs (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        client_id     VARCHAR(36) NOT NULL,
                        process_name  VARCHAR(100),
                        window_title  TEXT,
                        recorded_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
                    )
                    """);

            // Mở rộng 10: URL/domain bóc tách từ thanh địa chỉ trình duyệt —
            // dùng cho menu "Chặn nhanh" 1-click điền sẵn domain.
            addColumnIfMissing(connection, "activity_logs", "active_url_or_domain",
                    "VARCHAR(500)");

            statement.execute("CREATE INDEX IF NOT EXISTS idx_metrics_client_time "
                    + "ON metrics_logs(client_id, recorded_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_metrics_recorded_at "
                    + "ON metrics_logs(recorded_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_activity_client_time "
                    + "ON activity_logs(client_id, recorded_at)");
        }
    }

    /** Đóng tài nguyên im lặng (defensive — dùng trong catch/finally của DAO). */
    static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.debug("Resource close ignored: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * SQLite không có {@code ADD COLUMN IF NOT EXISTS} — thử ALTER TABLE
     * rồi nuốt trầm cảm lỗi "duplicate column" (cột đã tồn tại từ lần migrate
     * trước). Idempotent trên cả DB cũ lẫn DB mới.
     */
    private static void addColumnIfMissing(Connection connection, String table,
                                           String column, String definition) {
        String sql = "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            log.info("Migrated {} : added column {}", table, column);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("duplicate") && !message.contains("exists")) {
                log.warn("Migration {} on {} ignored: {}", column, table, e.getMessage());
            }
        }
    }
}
