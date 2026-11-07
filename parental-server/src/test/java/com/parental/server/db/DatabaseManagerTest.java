package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test cho {@link DatabaseManager} (Task 4.1): schema bảng tạo tự
 * động, migration idempotent, FK enforcement hoạt động, WAL pragma cho file
 * DB. Toàn bộ chạy trên :memory: hoặc temp file — không đụng DB thật.
 */
class DatabaseManagerTest {

    private static final String[] TABLES = {
            "clients", "metrics_logs", "metrics_daily_usage", "metrics_client_state",
            "schema_migrations", "client_rules", "blocked_domains", "command_logs"
    };

    @Test
    @DisplayName("In-memory DB riêng cho từng instance: tạo bảng và migration metrics")
    void memoryDbCreatesAllTables() throws Exception {
        DatabaseManager database = new DatabaseManager();
        assertTrue(database.getJdbcUrl().contains("mode=memory&cache=shared"),
                "In-memory manager phải dùng shared cache URI");

        try (Connection connection = database.newConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }

            for (String table : TABLES) {
                assertTrue(tables.contains(table), "Thiếu bảng: " + table);
            }
        }

        // Hai manager no-args phải là 2 DB in-memory ĐỘC LẬP (không nhiễm dữ liệu chéo)
        DatabaseManager other = new DatabaseManager();
        try (Connection connection = other.newConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM clients")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getLong(1), "Manager khác phải là DB rỗng hoàn toàn");
        }
    }

    @Test
    @DisplayName("Upgrade migrates existing cumulative metric snapshots to daily deltas once")
    void legacyMetricsAreBackfilledWithoutDoubleCounting(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("legacy-metrics.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath().normalize();
        String today = java.time.LocalDate.now().toString();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE clients (client_id VARCHAR PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE metrics_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        client_id VARCHAR NOT NULL,
                        download_bytes INTEGER NOT NULL DEFAULT 0,
                        upload_bytes INTEGER NOT NULL DEFAULT 0,
                        download_speed_bps INTEGER NOT NULL DEFAULT 0,
                        upload_speed_bps INTEGER NOT NULL DEFAULT 0,
                        session_duration_seconds INTEGER NOT NULL DEFAULT 0,
                        network_status VARCHAR NOT NULL DEFAULT 'ACTIVE',
                        recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("INSERT INTO clients(client_id) VALUES ('legacy-client')");
            statement.execute("INSERT INTO metrics_logs(client_id, download_bytes, upload_bytes, "
                    + "session_duration_seconds, recorded_at) VALUES "
                    + "('legacy-client', 100, 50, 60, '" + today + " 10:00:00'), "
                    + "('legacy-client', 200, 90, 120, '" + today + " 10:00:05')");
        }

        DatabaseManager database = new DatabaseManager(dbFile);
        MetricsDao metricsDao = new MetricsDao(database);
        assertEquals(290, metricsDao.getTodayTrafficBytes("legacy-client"));
        assertEquals(120, metricsDao.getTodayUsageSeconds("legacy-client"));
    }

    @Test
    @DisplayName("Migration idempotent: gọi createSchema 2 lần trên cùng DB không lỗi, dữ liệu giữ nguyên")
    void migrationIsIdempotent() throws Exception {
        DatabaseManager database = new DatabaseManager();
        try (Connection connection = database.newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO clients (client_id, status) VALUES ('c1', 'ONLINE')")) {
            statement.executeUpdate();
        }

        // Khởi tạo manager thứ 2 trên CÙNG URL — IF NOT EXISTS không lỗi, dữ liệu giữ nguyên
        DatabaseManager same = new DatabaseManager(database.getJdbcUrl());
        try (Connection connection = same.newConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM clients")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong(1), "Dữ liệu phải giữ nguyên sau re-migrate");
        }
    }

    @Test
    @DisplayName("FK enforcement: INSERT metrics cho client không tồn tại bị từ chối")
    void foreignKeyEnforcementRejectsOrphanMetrics() throws Exception {
        DatabaseManager database = new DatabaseManager();
        try (Connection connection = database.newConnection()) {
            // Đảm bảo foreign_keys=ON được bật trên connection này
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON;");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO metrics_logs (client_id) VALUES ('ghost')")) {
                assertThrows(java.sql.SQLException.class, statement::executeUpdate,
                        "FK phải từ chối metrics của client không tồn tại");
            }
        }
    }

    @Test
    @DisplayName("File DB temp: tạo file, bật WAL, ghi đọc được sau khi đóng connection")
    void fileDbWorksAndEnablesWal(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test-db.sqlite");
        DatabaseManager database = new DatabaseManager(dbFile);
        assertTrue(Files.exists(dbFile), "File DB phải được tạo");

        try (Connection connection = database.newConnection()) {
            try (ResultSet rs = connection.createStatement().executeQuery("PRAGMA journal_mode;")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase(),
                        "File DB phải bật WAL mode");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO clients (client_id, status) VALUES ('file-client', 'ONLINE')")) {
                statement.executeUpdate();
            }
        }
        // Connection mới (mô phỏng restart) vẫn đọc được dữ liệu
        try (Connection connection = database.newConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT status FROM clients WHERE client_id = 'file-client'")) {
            assertTrue(rs.next());
            assertEquals("ONLINE", rs.getString(1));
        }
        assertFalse(Files.notExists(dbFile));
    }

    @Test
    @DisplayName("DEFAULT_DB_FILE là parental_control.db")
    void defaultDbFileName() {
        assertEquals("parental_control.db", DatabaseManager.DEFAULT_DB_FILE);
    }
}
