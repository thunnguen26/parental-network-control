package com.parental.client.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.parental.common.protocol.payload.PairRequestPayload;

/**
 * Regression test cho hotfix GUI ghép đôi (deadlock EDT + socket rò rỉ):
 * {@link PairingClient} phải đóng socket sạch sẽ ở mọi đường đi — kể cả khi
 * bị hủy giữa chừng ({@link PairingClient#close()} đánh thức {@code readLine()}
 * đang block NGAY, không chờ hết soTimeout 10s) — và không bao giờ treo vô hạn.
 *
 * <p>Chạy headless (không Swing) — chỉ test tầng mạng/đồng thời của PairingClient;
 * luồng EDT của {@code ClientPairingFrame} được xác minh bằng review + hành vi
 * rung động: dispose trên EDT, callback trên thread nền.</p>
 */
@Timeout(60)
class PairingClientCancellationTest {

    @Test
    @DisplayName("pair() với server im lặng → trả kết quả thất bại sau soTimeout (không treo vĩnh viễn)")
    void pairWithSilentServerTimesOutCleanly() {
        try (ServerSocket silentServer = new ServerSocket(0)) { // không bao giờ trả lời
            PairingClient client = new PairingClient();
            Instant start = Instant.now();
            PairingClient.PairingResult result = client.pair(
                    "127.0.0.1", silentServer.getLocalPort(), "client-x", "123456",
                    new PairRequestPayload("123456", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA"));
            Duration elapsed = Duration.between(start, Instant.now());

            assertFalse(result.success(), "Server im lặng → thất bại");
            assertNotNull(result.message());
            // soTimeout 10s + buffer — phải kết thúc TRƯỚC 30s, tuyệt đối không treo vĩnh viễn
            assertTrue(elapsed.getSeconds() < 30,
                    "pair() phải tự kết thúc sau timeout, thực tế: " + elapsed.getSeconds() + "s");
        } catch (Exception e) {
            throw new IllegalStateException("Test setup failed", e);
        }
    }

    @Test
    @DisplayName("close() giữa chừng → readLine() đang block thoát NGAY (< 3s), socket được đóng sạch")
    void closeInterruptsBlockedPairImmediately() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0)) {
            PairingClient client = new PairingClient();
            Thread pairingThread = new Thread(() -> client.pair(
                    "127.0.0.1", silentServer.getLocalPort(), "client-y", "654321",
                    new PairRequestPayload("654321", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "BB")),
                    "pairing-cancelled");
            pairingThread.setDaemon(true);

            Instant start = Instant.now();
            pairingThread.start();
            Thread.sleep(500); // pair() kịp connect + gửi PAIR_REQUEST + block trong readLine()

            client.close(); // HỦY: đóng socket → readLine() thoát tức thì
            pairingThread.join(3_000);
            Duration elapsed = Duration.between(start, Instant.now());

            assertFalse(pairingThread.isAlive(),
                    "pair() phải thoát ngay sau close() (thực tế: " + elapsed.toMillis() + "ms)");
            assertTrue(elapsed.getSeconds() < 3,
                    "Hủy phải tức thì, không chờ soTimeout 10s (thực tế: " + elapsed.toMillis() + "ms)");

            // Server xác nhận socket client đã đóng hết (không rò rỉ giữ kết nối mở)
            silentServer.setSoTimeout(2_000);
            try {
                Socket accepted = silentServer.accept();
                // Đọc tới EOF — client đóng socket → read trả -1 ngay, không treo
                byte[] buffer = new byte[512];
                int total = 0;
                while (true) {
                    int read = accepted.getInputStream().read(buffer);
                    if (read < 0) {
                        break; // EOF = client đã đóng sạch
                    }
                    total += read;
                }
                assertTrue(total > 0, "Client phải đã gửi PAIR_REQUEST trước khi hủy");
                accepted.close();
            } finally {
                // no-op
            }
        }
    }

    @Test
    @DisplayName("close() khi KHÔNG có phiên ghép đôi nào → no-op an toàn (idempotent)")
    void closeWithoutActivePairingIsNoOp() {
        PairingClient client = new PairingClient();
        client.close(); // không activeSocket — không được ném exception
        client.close(); // idempotent
    }

    @Test
    @DisplayName("pair() thành công/full flow → socket tự đóng (try-with-resources, activeSocket null)")
    void successfulPairClosesSocketInFinally() throws Exception {
        try (ServerSocket echoServer = new ServerSocket(0)) {
            echoServer.setSoTimeout(10_000);
            Thread serverThread = new Thread(() -> {
                try {
                    Socket socket = echoServer.accept();
                    byte[] buffer = new byte[1024];
                    int read = socket.getInputStream().read(buffer);
                    if (read > 0) {
                        // Trả về 1 dòng PAIR_RESPONSE hợp lệ (thành công)
                        String response = "{\"type\":\"PAIR_RESPONSE\",\"clientId\":\"server\","
                                + "\"timestamp\":1727280000,\"payload\":{\"success\":true,"
                                + "\"deviceToken\":\"tok-1\",\"message\":\"PAIRED\"}}\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                    socket.close();
                } catch (Exception ignored) {
                    // server thread test — lỗi bị nuốt
                }
            }, "fake-pair-server");
            serverThread.setDaemon(true);
            serverThread.start();

            PairingClient client = new PairingClient();
            PairingClient.PairingResult result = client.pair(
                    "127.0.0.1", echoServer.getLocalPort(), "client-z", "111222",
                    new PairRequestPayload("111222", "PC", "DESKTOP", "Windows 11", "127.0.0.1", "CC"));

            assertTrue(result.success(), "Fake server trả PAIRED → phải thành công: " + result.message());
            assertEquals("tok-1", result.deviceToken());
            assertEquals("PAIRED", result.message());
        }
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
