package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.client.enforcement.ActionDispatcher;
import com.parental.client.enforcement.MockNetworkEnforcer;
import com.parental.client.enforcement.ProcessBlockerManager;
import com.parental.client.identity.ClientIdentityManager;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.UnpairPayload;

/**
 * Integration test luồng UNPAIR phía client (PROTOCOL.md Mục 3.8): server
 * đẩy gói UNPAIR → engine khôi phục mạng (enforcer cleanup), xóa
 * {@code client_identity.json}, dừng engine (không reconnect) và báo listener
 * để app quay về màn hình ghép đôi.
 */
@Timeout(90)
class ClientUnpairIntegrationTest {

    private FakeTcpServer server;
    private TcpClientEngine engine;
    private DeviceInfoCollector deviceInfoCollector;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeTcpServer();
        deviceInfoCollector = () -> new com.parental.common.protocol.payload.HandshakePayload(
                "PC-Test", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-FF");
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
        server.close();
    }

    /** Config timings ngắn cho test nhanh (như TcpClientEngineIntegrationTest). */
    private ClientConfig shortTimingsConfig() {
        return ClientConfig.builder()
                .serverPort(server.getPort())
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(600)
                .metricsIntervalMs(200)
                .initialReconnectDelayMs(100)
                .maxReconnectDelayMs(500)
                .build();
    }

    private static void awaitCondition(String description, Duration timeout,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
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
    @DisplayName("UNPAIR → enforcer cleanup (khôi phục mạng) + identity file bị xóa + engine dừng")
    void unpairRestoresNetworkAndDeletesIdentity() throws Exception {
        // Identity file đã tồn tại (đã ghép đôi)
        Path identityFile = tempDir.resolve("client_identity.json");
        ClientIdentityManager identityManager = new ClientIdentityManager(identityFile);
        identityManager.loadOrCreate();
        assertTrue(Files.exists(identityFile), "Precondition: identity file tồn tại");

        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        List<String> unpairReasons = new CopyOnWriteArrayList<>();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, msg -> true, null);
        ProcessBlockerManager processBlocker = new ProcessBlockerManager(
                tempDir.resolve("blocked-unpair.json"), false);
        assertTrue(processBlocker.blockProcess("somegame.exe"));

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { }, null, null, dispatcher,
                identityManager, () -> unpairReasons.add("notified"), null, processBlocker);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS), "Handshake phải thành công");

        // Server đẩy UNPAIR (mô phỏng phụ huynh xóa thiết bị khi client online)
        assertTrue(server.pushLine(server.parser.toLine(BaseMessage.of(MessageType.UNPAIR,
                "server", new UnpairPayload("Thiết bị đã bị xóa khỏi danh sách quản lý")))));

        // 1. Mạng được khôi phục (enforcer.cleanup() = resume + gỡ rule)
        awaitCondition("enforcer cleanup gọi sau UNPAIR", Duration.ofSeconds(10),
                () -> enforcer.cleanupCount.get() >= 1);

        // 2. Identity file bị xóa (mất deviceToken → hết quyền kết nối)
        awaitCondition("identity file bị xóa", Duration.ofSeconds(10),
                () -> !Files.exists(identityFile));

        // 3. Engine dừng — không reconnect nữa
        awaitCondition("engine dừng sau UNPAIR", Duration.ofSeconds(10),
                () -> !engine.isRunning());
        assertFalse(engine.isConnected());

        // 4. Listener được báo (app quay về màn hình ghép đôi)
        awaitCondition("unpair listener được gọi", Duration.ofSeconds(10),
                () -> !unpairReasons.isEmpty());

        // 5. UNPAIR phải xóa chính sách chặn tiến trình — không tiếp tục kill
        // app sau khi thiết bị đã bị gỡ khỏi quản lý.
        assertTrue(processBlocker.getBlockedProcesses().isEmpty(),
                "UNPAIR phải xóa danh sách chặn tiến trình");
        assertFalse(Files.exists(processBlocker.getListFilePath()),
                "File blocked_processes.json phải bị xóa khi unpair");
    }

    @Test
    @DisplayName("UNPAIR khi không có identityManager/listener → vẫn cleanup mạng + dừng engine an toàn")
    void unpairWithoutIdentityManagerIsSafe() throws Exception {
        MockNetworkEnforcer enforcer = new MockNetworkEnforcer();
        ActionDispatcher dispatcher = new ActionDispatcher(enforcer, msg -> true, null);

        engine = new TcpClientEngine(shortTimingsConfig(), deviceInfoCollector,
                new JsonProtocolParser(), new ServerCommandHandler() { }, null, null, dispatcher);
        engine.start();
        assertTrue(server.awaitHandshake(AWAIT_TIMEOUT_MS));

        assertTrue(server.pushLine(server.parser.toLine(BaseMessage.of(MessageType.UNPAIR,
                "server", new UnpairPayload(UnpairPayload.DEFAULT_REASON)))));

        awaitCondition("enforcer cleanup gọi", Duration.ofSeconds(10),
                () -> enforcer.cleanupCount.get() >= 1);
        awaitCondition("engine dừng", Duration.ofSeconds(10), () -> !engine.isRunning());
    }

    private static final long AWAIT_TIMEOUT_MS = 10_000;
}
