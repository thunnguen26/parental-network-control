package com.parental.client.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.parental.client.config.ClientConfig;
import com.parental.client.identity.ClientIdentityManager;

/**
 * Regression test zero-config serverHost — IP/port người dùng nhập trên
 * {@code ClientPairingFrame} phải được lưu vào {@code client_identity.json}
 * và được {@code ClientApp} dùng khi khởi động engine (KHÔNG fallback về
 * {@code 127.0.0.1} khi identity đã có địa chỉ đã lưu).
 */
class ClientAppServerAddressTest {

    @TempDir
    Path tempDir;

    private ClientIdentityManager newManager() {
        return new ClientIdentityManager(tempDir.resolve("client_identity.json"));
    }

    @Test
    @DisplayName("Ghép đôi với IP tùy chỉnh → identity lưu đúng IP/port đã nhập")
    void pairingSavesUserEnteredHostIntoIdentity() {
        ClientIdentityManager manager = newManager();
        var identity = manager.loadOrCreate();
        assertTrue(manager.save(new ClientIdentityManager.ClientIdentity(
                identity.clientId(), "token-abc", "192.168.1.15", 8888)),
            "save phải thành công");

        var reloaded = manager.load();
        assertEquals("192.168.1.15", reloaded.serverHost(), "IP người dùng nhập phải được lưu");
        assertEquals(8888, reloaded.serverPort());
    }

    @Test
    @DisplayName("resolveHost ưu tiên serverHost đã lưu (IP nhập lúc ghép đôi)")
    void resolveHostPrefersSavedIdentityOverDefault() {
        var identity = new ClientIdentityManager.ClientIdentity(
                "client-1", "token-1", "192.168.1.15", 8888);
        assertEquals("192.168.1.15", ClientApp.resolveHost(identity),
            "Phải dùng IP đã lưu, KHÔNG phải 127.0.0.1");
        assertEquals(8888, ClientApp.resolvePort(identity));
    }

    @Test
    @DisplayName("Identity rỗng → resolveHost/resolvePort fallback đúng default")
    void resolveFallsBackToDefaultsWhenIdentityEmpty() {
        var fresh = ClientIdentityManager.ClientIdentity.fresh("client-2");
        assertEquals(ClientConfig.DEFAULT_SERVER_HOST, ClientApp.resolveHost(fresh));
        assertEquals(ClientConfig.DEFAULT_SERVER_PORT, ClientApp.resolvePort(fresh));
    }

    @Test
    @DisplayName("resolveHost null-safe: identity null / host rỗng → default")
    void resolveHostIsDefensive() {
        assertEquals(ClientConfig.DEFAULT_SERVER_HOST, ClientApp.resolveHost(null));
        assertEquals(ClientConfig.DEFAULT_SERVER_PORT, ClientApp.resolvePort(null));
        var blankHost = new ClientIdentityManager.ClientIdentity(
                "client-3", "token-3", "   ", 0);
        assertEquals(ClientConfig.DEFAULT_SERVER_HOST, ClientApp.resolveHost(blankHost));
        assertEquals(ClientConfig.DEFAULT_SERVER_PORT, ClientApp.resolvePort(blankHost));
    }

    @Test
    @DisplayName("ClientConfig.DEFAULT_SERVER_HOST là 127.0.0.1 (hợp đồng protocol)")
    void defaultHostContract() {
        assertEquals("127.0.0.1", ClientConfig.DEFAULT_SERVER_HOST);
        assertEquals(8888, ClientConfig.DEFAULT_SERVER_PORT);
    }
}
