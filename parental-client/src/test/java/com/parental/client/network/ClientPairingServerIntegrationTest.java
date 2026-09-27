package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.parental.client.identity.PairingClient;
import com.parental.client.identity.PairingClient.PairingResult;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.PairRequestPayload;
import com.parental.server.db.DatabaseManager;
import com.parental.server.network.TcpServerEngine;

/**
 * Integration test luồng ghép đôi END-TO-END qua TCP thật (server thật + DB
 * file @TempDir WAL): PAIR_REQUEST → PAIR_RESPONSE cấp token → HANDSHAKE kèm
 * token thành công. Dùng {@link PairingClient} thật của client module gọi
 * thẳng vào {@link TcpServerEngine} thật của server module.
 */
@Timeout(90)
class ClientPairingServerIntegrationTest {

    private DatabaseManager database;
    private TcpServerEngine engine;
    private JsonProtocolParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("pairing_integration.db"));
        parser = new JsonProtocolParser();
        engine = new TcpServerEngine(0, 8, 30, database);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PairingClient: PIN hợp lệ → success + deviceToken (luồng ghép đôi thật)")
    void pairingClientSucceedsWithValidPin() {
        String pin = engine.getPairingDao().createPin();
        PairRequestPayload request = new PairRequestPayload(
                pin, "Laptop-ConTrai", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-FF");

        PairingResult result = new PairingClient().pair(
                "127.0.0.1", engine.getPort(), "client-pair-1", pin, request);

        assertTrue(result.success(), "Ghép đôi phải thành công: " + result.message());
        assertEquals("PAIRED", result.message());
        assertNotNull(result.deviceToken());
        assertTrue(engine.getClientDao().isPaired("client-pair-1"));
        assertTrue(engine.getClientDao().isValidPairing("client-pair-1", result.deviceToken()));
        assertEquals(com.parental.server.db.PairingDao.PinState.USED,
                engine.getPairingDao().getPinState(pin));
    }

    @Test
    @DisplayName("PairingClient: PIN sai → success=false INVALID_PIN, không cấp token")
    void pairingClientRejectsWrongPin() {
        engine.getPairingDao().createPin(); // PIN thật là mã khác
        PairRequestPayload request = new PairRequestPayload(
                "000000", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA");

        PairingResult result = new PairingClient().pair(
                "127.0.0.1", engine.getPort(), "client-pair-2", "000000", request);

        assertFalse(result.success());
        assertEquals("INVALID_PIN", result.message());
        assertNotNull(result, "result không null");
        assertTrue(engine.getSessionManager().count() == 0,
                "Luồng ghép đôi không đăng ký phiên");
    }

    @Test
    @DisplayName("FULL FLOW: PAIR → lưu token → HANDSHAKE kèm token → session ONLINE trong DB")
    void fullPairThenHandshakeFlow() throws Exception {
        String pin = engine.getPairingDao().createPin();
        String clientId = "client-full-flow";
        PairRequestPayload request = new PairRequestPayload(
                pin, "Laptop-Full", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB-CC-DD-EE-01");

        // Bước 1+2: ghép đôi qua PairingClient thật
        PairingResult result = new PairingClient().pair(
                "127.0.0.1", engine.getPort(), clientId, pin, request);
        assertTrue(result.success());

        // Bước 3: HANDSHAKE bằng token vừa cấp qua TCP thật
        Socket socket = new Socket("127.0.0.1", engine.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15_000);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, clientId,
                new HandshakePayload("Laptop-Full", "DESKTOP", "Windows 11",
                        "127.0.0.1", "AA-BB-CC-DD-EE-01", result.deviceToken()))));
        writer.flush();

        awaitCondition("session đăng ký sau ghép đôi", Duration.ofSeconds(10),
                () -> engine.getSessionManager().get(clientId) != null);
        awaitCondition("client ONLINE trong DB", Duration.ofSeconds(10),
                () -> "ONLINE".equals(engine.getClientDao().getStatus(clientId)));

        socket.close();
    }

    @Test
    @DisplayName("Server trả PAIR_RESPONSE trước khi đóng kết nối ghép đôi (không treo client)")
    void pairResponseArrivesBeforeClose() throws Exception {
        String pin = engine.getPairingDao().createPin();

        Socket socket = new Socket("127.0.0.1", engine.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer.write(parser.toLine(BaseMessage.of(MessageType.PAIR_REQUEST, "client-race",
                new PairRequestPayload(pin, "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA"))));
        writer.flush();

        // Phản hồi phải đến (không null, không timeout) — server trả lời rồi mới đóng
        String line = reader.readLine();
        assertNotNull(line, "PAIR_RESPONSE phải được ghi và flush trước khi đóng socket");
        assertEquals(MessageType.PAIR_RESPONSE, parser.fromLine(line).getType());
        socket.close();
    }

    @Test
    @DisplayName("2 thiết bị ghép đôi với 2 PIN khác nhau → 2 dòng clients riêng biệt")
    void twoDevicesPairIndependently() {
        String pin1 = engine.getPairingDao().createPin();
        String pin2 = engine.getPairingDao().createPin();

        PairingResult r1 = new PairingClient().pair("127.0.0.1", engine.getPort(),
                "device-1", pin1, new PairRequestPayload(
                        pin1, "PC-1", "DESKTOP", "Windows 11", "127.0.0.1", "AA-01"));
        PairingResult r2 = new PairingClient().pair("127.0.0.1", engine.getPort(),
                "device-2", pin2, new PairRequestPayload(
                        pin2, "PC-2", "DESKTOP", "Windows 11", "127.0.0.1", "AA-02"));

        assertTrue(r1.success());
        assertTrue(r2.success());
        assertTrue(!r1.deviceToken().equals(r2.deviceToken()), "Mỗi thiết bị một token riêng");
        assertTrue(engine.getClientDao().isValidPairing("device-1", r1.deviceToken()));
        assertTrue(engine.getClientDao().isValidPairing("device-2", r2.deviceToken()));
    }

    @Test
    @DisplayName("Token cấp cho thiết bị này không dùng được cho clientId khác ( enrollment nhân bản bị chặn)")
    void tokenIsBoundToClientId() throws Exception {
        String pin = engine.getPairingDao().createPin();
        PairingResult result = new PairingClient().pair("127.0.0.1", engine.getPort(),
                "device-owner", pin, new PairRequestPayload(
                        pin, "PC-Owner", "DESKTOP", "Windows 11", "127.0.0.1", "AA-77"));

        assertTrue(result.success());

        // Thiết bị khác (clientId khác) mượn token → handshake bị từ chối
        Socket socket = new Socket("127.0.0.1", engine.getPort());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, "device-thief",
                new HandshakePayload("PC-Thief", "DESKTOP", "Windows 11",
                        "127.0.0.1", "AA-78", result.deviceToken()))));
        writer.flush();

        awaitCondition("không đăng ký session cho token mượn", Duration.ofSeconds(3),
                () -> engine.getSessionManager().count() == 0);
        socket.close();
        awaitCondition("device-thief không có trong DB", Duration.ofSeconds(2),
                () -> !engine.getClientDao().exists("device-thief"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
}
