package com.parental.client.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.parental.client.identity.ClientIdentityManager.ClientIdentity;

/**
 * Unit test {@link ClientIdentityManager} — đọc/ghi {@code client_identity.json}
 * trên {@code @TempDir} (không đụng file thật trong working directory):
 * gọi lại nhiều lần trả về CÙNG một clientId, lưu/đọc deviceToken,
 * defensive với file hỏng, atomic save.
 */
class ClientIdentityManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("loadOrCreate lần đầu: sinh UUID mới và ghi file ngay")
    void firstCallCreatesAndPersistsUuid() throws IOException {
        Path file = tempDir.resolve("client_identity.json");
        ClientIdentityManager manager = new ClientIdentityManager(file);

        ClientIdentity identity = manager.loadOrCreate();

        assertNotNull(identity.clientId());
        assertDoesNotThrowParseUuid(identity.clientId());
        assertNull(identity.deviceToken(), "Mới tạo chưa ghép đôi → token null");
        assertTrue(Files.exists(file), "File định danh phải được ghi ngay");
        assertFalse(Files.readString(file).isBlank());
    }

    @Test
    @DisplayName("Gọi lại nhiều lần (mô phỏng bật lại máy) → CÙNG một clientId")
    void repeatedCallsReturnSameClientId() {
        Path file = tempDir.resolve("client_identity.json");
        ClientIdentityManager manager = new ClientIdentityManager(file);

        String first = manager.loadOrCreate().clientId();
        String second = manager.loadOrCreate().clientId();
        // Manager instance mới (mô phỏng tiến trình mới) vẫn đọc đúng file cũ
        String third = new ClientIdentityManager(file).loadOrCreate().clientId();

        assertEquals(first, second);
        assertEquals(second, third, "clientId phải bền vững qua các lần khởi động");
    }

    @Test
    @DisplayName("Hai máy khác nhau (2 file) → hai clientId khác nhau")
    void differentFilesGetDifferentUuids() {
        ClientIdentity a = new ClientIdentityManager(
                tempDir.resolve("a.json")).loadOrCreate();
        ClientIdentity b = new ClientIdentityManager(
                tempDir.resolve("b.json")).loadOrCreate();
        assertNotEquals(a.clientId(), b.clientId());
    }

    @Test
    @DisplayName("saveDeviceToken → load lại thấy token; isPaired=true")
    void deviceTokenRoundTrip() {
        ClientIdentityManager manager = new ClientIdentityManager(
                tempDir.resolve("client_identity.json"));
        String clientId = manager.loadOrCreate().clientId();

        assertTrue(manager.saveDeviceToken("token-123"));
        assertTrue(manager.isPaired());

        ClientIdentity reloaded = manager.load();
        assertEquals(clientId, reloaded.clientId(), "clientId không đổi khi lưu token");
        assertEquals("token-123", reloaded.deviceToken());
    }

    @Test
    @DisplayName("save giữ nguyên serverHost/serverPort (zero-config lần bật sau)")
    void saveRemembersServerAddress() {
        ClientIdentityManager manager = new ClientIdentityManager(
                tempDir.resolve("client_identity.json"));
        manager.loadOrCreate();
        manager.save(new ClientIdentity("cid", "tok", "192.168.1.10", 9999));

        ClientIdentity reloaded = manager.load();
        assertEquals("192.168.1.10", reloaded.serverHost());
        assertEquals(9999, reloaded.serverPort());
    }

    @Test
    @DisplayName("File hỏng (JSON sai) → defensive: coi như chưa có, tạo định danh mới")
    void corruptedFileStartsFresh() throws IOException {
        Path file = tempDir.resolve("client_identity.json");
        Files.writeString(file, "{ not valid json !!!");

        ClientIdentity identity = new ClientIdentityManager(file).loadOrCreate();

        assertNotNull(identity.clientId());
        // File đã được tự ghi lại với định danh mới hợp lệ
        assertNotNull(new ClientIdentityManager(file).load());
    }

    @Test
    @DisplayName("File rỗng → coi như chưa có định danh")
    void emptyFileTreatedAsMissing() throws IOException {
        Path file = tempDir.resolve("client_identity.json");
        Files.writeString(file, "   ");

        assertNull(new ClientIdentityManager(file).load());
        assertNotNull(new ClientIdentityManager(file).loadOrCreate().clientId());
    }

    @Test
    @DisplayName("save không có clientId → từ chối (false), không ghi file rác")
    void saveWithoutClientIdRejected() {
        Path file = tempDir.resolve("sub/client_identity.json");
        ClientIdentityManager manager = new ClientIdentityManager(file);

        assertFalse(manager.save(new ClientIdentity(null, null, null, 0)));
        assertFalse(manager.save(null));
    }

    @Test
    @DisplayName("save vào thư mục chưa tồn tại → tự tạo thư mục cha")
    void saveCreatesParentDirectories() {
        Path file = tempDir.resolve("deep/nested/dir/client_identity.json");
        ClientIdentityManager manager = new ClientIdentityManager(file);

        assertTrue(manager.save(ClientIdentity.fresh("cid-1")));
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("ClientIdentity.fresh: chỉ có clientId, token null")
    void freshFactory() {
        ClientIdentity fresh = ClientIdentity.fresh("abc");
        assertEquals("abc", fresh.clientId());
        assertNull(fresh.deviceToken());
        assertNull(fresh.serverHost());
        assertEquals(0, fresh.serverPort());
    }

    private static void assertDoesNotThrowParseUuid(String value) {
        UUID.fromString(value); // ném IllegalArgumentException nếu sai định dạng
    }
}
