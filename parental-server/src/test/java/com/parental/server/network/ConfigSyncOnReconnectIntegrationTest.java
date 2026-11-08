package com.parental.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.server.db.DatabaseManager;
import com.parental.server.db.PairingDao;

/**
 * Regression test: chính sách phải được đẩy xuống client NGAY sau HANDSHAKE
 * (kể cả khi cấu hình đổi lúc client offline hoặc client vừa restart).
 *
 * <p><b>Lỗi đã sửa:</b> trước đây server chỉ gửi {@code CONFIG_SYNC} khi phụ
 * huynh bấm "Lưu" trên GUI. Client restart (shutdown hook dọn hosts) hoặc
 * nhận cấu hình lúc offline sẽ chạy với hosts trắng cho tới lần lưu kế tiếp —
 * domain chặn/quota không được áp dụng.</p>
 */
@Timeout(60)
class ConfigSyncOnReconnectIntegrationTest {

    private DatabaseManager database;
    private TcpServerEngine engine;
    private final JsonProtocolParser parser = new JsonProtocolParser();
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("config_sync_on_connect.db"));
        engine = new TcpServerEngine(0, 8, 30, database);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        closeClient();
        engine.stop();
    }

    private void connectWithHandshake(String clientId, String token) throws IOException {
        socket = new Socket("127.0.0.1", engine.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15_000);
        reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        write(BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                "PC-Config", "DESKTOP", "Windows 11", "127.0.0.1", clientId, token)));
    }

    private void write(BaseMessage<?> message) throws IOException {
        try {
            writer.write(parser.toLine(message));
        } catch (ProtocolParseException e) {
            throw new IOException("Serialize failed in test", e);
        }
        writer.flush();
    }

    private BaseMessage<?> awaitConfigSync() throws IOException, ProtocolParseException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Server closed the stream while waiting for CONFIG_SYNC");
            }
            BaseMessage<?> message = parser.fromLine(line);
            if (message.getType() == MessageType.CONFIG_SYNC) {
                return message;
            }
        }
        throw new AssertionError("Timeout waiting for CONFIG_SYNC after HANDSHAKE");
    }

    private void closeClient() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
        socket = null;
    }

    private static void awaitCondition(String description, Duration timeout,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Timeout while waiting: " + description);
    }

    @Test
    @DisplayName("Cấu hình đổi khi client OFFLINE → client reconnect nhận CONFIG_SYNC mới ngay")
    void reconnectReceivesUpdatedConfigWithoutManualPush() throws Exception {
        String clientId = "config-client-1";
        String token = "config-token-1";
        engine.getClientDao().saveDeviceToken(clientId, token);

        // Lần kết nối đầu: chưa có rule → vẫn nhận CONFIG_SYNC rỗng (idempotent)
        connectWithHandshake(clientId, token);
        BaseMessage<?> firstSync = awaitConfigSync();
        assertEquals(0, ((ConfigSyncPayload) firstSync.getPayload()).blockedDomains().size());

        // Client offline trong khi phụ huynh đổi cấu hình
        closeClient();
        awaitCondition("session offline", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get(clientId) == null);

        engine.getBlockedDomainDao().setBlockedDomains(clientId, List.of("tiktok.com"));
        engine.getRuleDao().upsertRule(clientId, new ConfigSyncPayload(
                List.of("tiktok.com"), 7_200, 1_073_741_824L, "06:00", "22:00"));

        // Reconnect → server phải tự đẩy cấu hình mới nhất (không cần GUI push)
        connectWithHandshake(clientId, token);
        BaseMessage<?> sync = awaitConfigSync();
        ConfigSyncPayload payload = (ConfigSyncPayload) sync.getPayload();

        assertNotNull(payload);
        assertTrue(payload.blockedDomains().contains("tiktok.com"),
                "Domain chặn phải được tự đồng bộ sau reconnect");
        assertEquals(7_200, payload.dailyUsageLimitSeconds());
        assertEquals(1_073_741_824L, payload.dailyTrafficQuotaBytes());
        assertEquals("06:00", payload.allowedStartTime());
        assertEquals("22:00", payload.allowedEndTime());
    }

    @Test
    @DisplayName("Client ONLINE nhận cấu hình ngay sau HANDSHAKE (không đợi chu kỳ quét)")
    void firstHandshakeReceivesConfigImmediately() throws Exception {
        String clientId = "config-client-2";
        String token = "config-token-2";
        engine.getClientDao().saveDeviceToken(clientId, token);
        engine.getBlockedDomainDao().setBlockedDomains(clientId, List.of("facebook.com"));
        engine.getRuleDao().upsertRule(clientId, new ConfigSyncPayload(
                List.of("facebook.com"), 0, 0, null, null));

        // Chưa từng gọi pushConfigSync — chỉ HANDSHAKE
        connectWithHandshake(clientId, token);
        ConfigSyncPayload payload = (ConfigSyncPayload) awaitConfigSync().getPayload();

        assertTrue(payload.blockedDomains().contains("facebook.com"));
    }

    @Test
    @DisplayName("PIN hết hạn vẫn bị từ chối sau khi thêm sync-on-connect (không ảnh hưởng pairing)")
    void pairingBehaviourUnchanged() {
        String pin = engine.getPairingDao().createPin();
        assertNotNull(pin);
        assertEquals(6, pin.length());
        assertEquals(PairingDao.PinState.ACTIVE, engine.getPairingDao().getPinState(pin));
    }
}
