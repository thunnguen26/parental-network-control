package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test cho {@link DatabaseManager} (Task 4.1): schema 5 bảng tạo tự
 * động, migration idempotent, FK enforcement hoạt động, WAL pragma cho file
 * DB. Toàn bộ chạy trên :memory: hoặc temp file — không đụng DB thật.
 */
class DatabaseManagerTest {

    private static final String[] TABLES = {
            "clients", "metrics_logs", "client_rules", "blocked_domains", "command_logs"
    };

    @Test
    @DisplayName("In-memory DB riêng cho từng instance: tạo đủ 5 bảng, không nhiễm chéo giữa 2 manager")
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
