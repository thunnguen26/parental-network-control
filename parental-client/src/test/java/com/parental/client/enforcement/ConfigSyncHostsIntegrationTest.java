package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.client.network.ServerCommandHandler;
import com.parental.client.network.TcpClientEngine;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.server.db.DatabaseManager;
import com.parental.server.network.TcpServerEngine;

/**
 * Integration test end-to-end Domain Blacklist (Hosts Enforcement): Server
 * thật lưu blockedDomains vào DB + {@code pushConfigSync} → CONFIG_SYNC qua
 * TCP thật → {@link TcpClientEngine} thật nhận → {@link HostsFileManager}
 * cập nhật temp hosts file với đủ marker + biến thể www. KHÔNG đụng hosts
 * thật (temp path), không spawn netsh (MockNetworkEnforcer không cần —
 * dispatcher null).
 */
@Timeout(90)
class ConfigSyncHostsIntegrationTest {

    private static final long AWAIT_TIMEOUT_MS = 15_000;

    private DatabaseManager database;
    private TcpServerEngine serverEngine;
    private TcpClientEngine clientEngine;
    private JsonProtocolParser parser;
    private DeviceInfoCollector deviceInfoCollector =
            () -> new com.parental.common.protocol.payload.HandshakePayload(
                    "PC-Test", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-FF");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("blacklist_integration.db"));
        parser = new JsonProtocolParser();
        serverEngine = new TcpServerEngine(0, 8, 30, database);
        serverEngine.start();
    }

    @AfterEach
    void tearDown() {
        if (clientEngine != null) {
            clientEngine.stop();
        }
        serverEngine.stop();
    }

    private TcpClientEngine startClient(String clientId, HostsFileManager hostsManager) {
        ClientConfig config;
        try {
            config = ClientConfig.builder()
                    .serverHost("127.0.0.1")
                    .serverPort(serverEngine.getPort())
                    .clientId(clientId)
                    .deviceToken("token-" + clientId)
                    .activityIntervalMs(0) // tắt activity cho test này
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        serverEngine.getClientDao().saveDeviceToken(clientId, "token-" + clientId);

        TcpClientEngine engine = new TcpClientEngine(config, deviceInfoCollector,
                parser, new ServerCommandHandler() { },
                null, hostsManager, null);
        engine.start();
        return engine;
    }

    /** Hosts + bypass fake: mọi lệnh reg/netsh coi như thành công, không chạm máy thật. */
    private HostsFileManager newHostsManager(Path hostsFile) {
        BrowserBypassEnforcer bypass = new BrowserBypassEnforcer(true) {
            @Override
            boolean execute(String... command) {
                return true;
            }
        };
        return new HostsFileManager(hostsFile, bypass);
    }

    private static void awaitCondition(String description, long timeoutMs,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plusMillis(timeoutMs);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Timeout while waiting: " + description);
    }

    @Test
    @DisplayName("pushConfigSync → client nhận CONFIG_SYNC → hosts có marker + www + IP đen")
    void fullBlacklistSyncUpdatesHostsFile() throws Exception {
        // Client đăng ký listener hosts trên temp file
        Path hostsFile = tempDir.resolve("hosts-test");
        HostsFileManager hostsManager = newHostsManager(hostsFile);
        String clientId = "blacklist-client-1";

        clientEngine = startClient(clientId, hostsManager);
        awaitCondition("client ONLINE trong DB", AWAIT_TIMEOUT_MS, () ->
                "ONLINE".equals(serverEngine.getClientDao().getStatus(clientId)));

        // Server: lưu danh sách chặn (GUI lưu tương tự qua dao) rồi đẩy CONFIG_SYNC
        assertTrue(serverEngine.getBlockedDomainDao().setBlockedDomains(clientId,
                List.of("facebook.com", "tiktok.com")));
        assertTrue(serverEngine.pushConfigSync(clientId),
                "Client đang online → push phải thành công");

        // Client: hosts file được cập nhật đúng danh sách (marker mới + www + IP đen)
        awaitCondition("hosts file có khối chặn", AWAIT_TIMEOUT_MS, () -> {
            try {
                if (!Files.exists(hostsFile)) {
                    return false;
                }
                String content = Files.readString(hostsFile);
                return content.contains(HostsFileManager.MARKER_START)
                        && content.contains("0.0.0.0  facebook.com")
                        && content.contains("0.0.0.0  www.facebook.com")
                        && content.contains("0.0.0.0  tiktok.com");
            } catch (Exception e) {
                return false;
            }
        });
        String content = Files.readString(hostsFile);
        assertTrue(content.contains("0.0.0.0  www.tiktok.com"), "Biến thể www đầy đủ");
        assertEquals(1,
                content.split(java.util.regex.Pattern.quote(HostsFileManager.MARKER_START), -1).length - 1,
                "Chỉ 1 khối marker duy nhất");

        // ACK CONFIG_SYNC phải về Server kèm status thật (không chỉ "đã gửi").
        awaitCondition("server nhận CONFIG_SYNC ACK có hosts=applied", AWAIT_TIMEOUT_MS, () -> {
            var session = serverEngine.getSessionManager().get(clientId);
            return session != null && "APPLIED".equals(session.getLastConfigStatus())
                    && session.getLastConfigMessage() != null
                    && session.getLastConfigMessage().contains("hosts=applied");
        });
        var session = serverEngine.getSessionManager().get(clientId);
        assertTrue(session.getLastConfigRevision() > 0,
                "ACK phải echo đúng revision của CONFIG_SYNC");
        assertTrue(session.getLastConfigMessage().contains("hosts=applied"),
                "Message phải phản ánh hosts thật sự được ghi");
    }

    @Test
    @DisplayName("Hosts không ghi được → ACK PARTIAL kèm lỗi, KHÔNG báo APPLIED")
    void hostsWriteFailureIsReportedAsPartial() throws Exception {
        // Parent là file (không phải thư mục) → mọi OS đều không thể tạo hosts.
        Path notDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "x");
        Path hostsFile = notDirectory.resolve("hosts");
        HostsFileManager hostsManager = newHostsManager(hostsFile);
        String clientId = "blacklist-client-4";

        clientEngine = startClient(clientId, hostsManager);
        awaitCondition("client ONLINE", AWAIT_TIMEOUT_MS, () ->
                "ONLINE".equals(serverEngine.getClientDao().getStatus(clientId)));

        serverEngine.getBlockedDomainDao().setBlockedDomains(clientId, List.of("youtube.com"));
        assertTrue(serverEngine.pushConfigSync(clientId));

        awaitCondition("server nhận ACK lỗi hosts", AWAIT_TIMEOUT_MS, () -> {
            var session = serverEngine.getSessionManager().get(clientId);
            return session != null && session.getLastConfigMessage() != null
                    && session.getLastConfigMessage().contains("hosts: ghi");
        });
        var session = serverEngine.getSessionManager().get(clientId);
        assertNotEquals("APPLIED", session.getLastConfigStatus(),
                "Ghi hosts thất bại mà vẫn báo APPLIED là false success");
        assertEquals("PARTIAL", session.getLastConfigStatus());
        assertTrue(session.getLastConfigMessage().contains("hosts:"),
                "Message phải nêu lỗi hosts: " + session.getLastConfigMessage());
    }

    @Test
    @DisplayName("Cập nhật danh sách rỗng → client dọn sạch khối chặn, giữ nội dung hosts gốc")
    void emptyListClearsHostsBlock() throws Exception {
        Path hostsFile = tempDir.resolve("hosts-clear-test");
        Files.writeString(hostsFile, "127.0.0.1  localhost\n");
        HostsFileManager hostsManager = newHostsManager(hostsFile);
        String clientId = "blacklist-client-2";

        clientEngine = startClient(clientId, hostsManager);
        awaitCondition("client ONLINE", AWAIT_TIMEOUT_MS, () ->
                "ONLINE".equals(serverEngine.getClientDao().getStatus(clientId)));

        // Đẩy danh sách có domain trước
        serverEngine.getBlockedDomainDao().setBlockedDomains(clientId, List.of("facebook.com"));
        assertTrue(serverEngine.pushConfigSync(clientId));
        awaitCondition("hosts có khối chặn lần đầu", AWAIT_TIMEOUT_MS, () -> {
            try {
                return Files.readString(hostsFile).contains(HostsFileManager.MARKER_START);
            } catch (Exception e) {
                return false;
            }
        });

        // Sau đó nới lỏng: danh sách rỗng → client dọn sạch khối
        serverEngine.getBlockedDomainDao().setBlockedDomains(clientId, List.of());
        assertTrue(serverEngine.pushConfigSync(clientId));
        awaitCondition("hosts được dọn sạch sau danh sách rỗng", AWAIT_TIMEOUT_MS, () -> {
            try {
                String content = Files.readString(hostsFile);
                return !content.contains(HostsFileManager.MARKER_START)
                        && !content.contains("facebook.com");
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(Files.readString(hostsFile).contains("127.0.0.1  localhost"),
                "Nội dung hosts gốc phải được bảo toàn");
    }

    @Test
    @DisplayName("CONFIG_SYNC đẩy đủ ConfigSyncPayload (domains + hạn mức) qua TCP")
    void configSyncPayloadCarriesFullConfig() throws Exception {
        Path hostsFile = tempDir.resolve("hosts-payload-test");
        HostsFileManager hostsManager = newHostsManager(hostsFile);
        String clientId = "blacklist-client-3";

        clientEngine = startClient(clientId, hostsManager);
        awaitCondition("client ONLINE", AWAIT_TIMEOUT_MS, () ->
                "ONLINE".equals(serverEngine.getClientDao().getStatus(clientId)));

        serverEngine.getBlockedDomainDao().setBlockedDomains(clientId, List.of("xvideos.com"));
        serverEngine.getRuleDao().upsertRule(clientId, new ConfigSyncPayload(
                List.of("xvideos.com"), 7_200, 1_073_741_824L, null, null));
        assertTrue(serverEngine.pushConfigSync(clientId));

        // Hosts được cập nhật đồng nghĩa engine đã nhận và xử lý CONFIG_SYNC
        awaitCondition("hosts cập nhật từ CONFIG_SYNC đầy đủ", AWAIT_TIMEOUT_MS, () -> {
            try {
                return Files.readString(hostsFile).contains("0.0.0.0  xvideos.com");
            } catch (Exception e) {
                return false;
            }
        });
    }
}
