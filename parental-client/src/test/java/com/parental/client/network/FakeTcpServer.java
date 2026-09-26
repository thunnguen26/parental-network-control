package com.parental.client.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Server ảo dùng chung cho integration test của {@link TcpClientEngine}.
 *
 * <p>Chạy {@link ServerSocket} trên dynamic port (0), accept trên background
 * thread, đọc LDJSON và: HANDSHAKE → ghi nhận; HEARTBEAT → trả ACK (giống hành
 * vi {@code ClientHandler} thật). Hỗ trợ {@link #close()} đột ngột và
 * {@link #reopen()} trên CÙNG port để test auto-reconnect.</p>
 */
final class FakeTcpServer implements AutoCloseable {

    final JsonProtocolParser parser = new JsonProtocolParser();

    private ServerSocket serverSocket;
    private final int boundPort;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger connectionsAccepted = new AtomicInteger(0);
    private volatile Socket currentClientSocket;
    /** Writer của phiên hiện hành — cho test đẩy gói tin chủ động xuống client. */
    private volatile BufferedWriter currentWriter;

    /** Các gói tin nhận được (thread-safe). */
    final List<BaseMessage<?>> received = new CopyOnWriteArrayList<>();
    /** Mở khi handshake đầu tiên được ghi nhận. */
    final CountDownLatch handshakeLatch = new CountDownLatch(1);
    /** Mở khi heartbeat đầu tiên được ghi nhận. */
    final CountDownLatch firstHeartbeatLatch = new CountDownLatch(1);
    /** Mở khi client kết nối lần 2 (test auto-reconnect). */
    final CountDownLatch secondConnectionLatch = new CountDownLatch(1);

    FakeTcpServer() throws IOException {
        serverSocket = new ServerSocket(0); // dynamic port
        serverSocket.setReuseAddress(true);
        boundPort = serverSocket.getLocalPort();
        start();
    }

    int getPort() {
        return boundPort;
    }

    private void start() {
        Thread acceptor = new Thread(this::acceptLoop, "fake-server-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionsAccepted.incrementAndGet();
                if (connectionsAccepted.get() == 2) {
                    secondConnectionLatch.countDown();
                }
                currentClientSocket = socket;
                socket.setTcpNoDelay(true);
                handle(socket);
            } catch (IOException e) {
                if (!closed.get()) {
                    return; // lỗi thật khi chưa đóng — dừng accept loop
                }
            }
        }
    }

    /** Xử lý một kết nối client: đọc LDJSON tới khi đứt. */
    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            currentWriter = writer;

            String line;
            while ((line = reader.readLine()) != null) {
                BaseMessage<?> message;
                try {
                    message = parser.fromLine(line);
                } catch (ProtocolParseException e) {
                    continue; // bỏ qua dòng hỏng
                }
                received.add(message);

                if (message.getType() == MessageType.HANDSHAKE) {
                    handshakeLatch.countDown();
                }
                if (message.getType() == MessageType.HEARTBEAT) {
                    firstHeartbeatLatch.countDown();
                    // Trả ACK giống ClientHandler thật của server
                    try {
                        writer.write(parser.toLine(BaseMessage.of(MessageType.ACK, "fake-server", Map.of())));
                        writer.flush();
                    } catch (ProtocolParseException e) {
                        // không thể xảy ra với payload rỗng — bỏ qua
                    }
                }
            }
        } catch (IOException ignored) {
            // client ngắt đột ngột — hành vi bình thường trong test reconnect
        } finally {
            currentWriter = null;
        }
    }

    /**
     * Đẩy một dòng LDJSON xuống client hiện hành (mô phỏng server chủ động
     * gửi COMMAND/CONFIG_SYNC). Trả về false nếu chưa có kết nối nào.
     */
    boolean pushLine(String line) {
        BufferedWriter writer = currentWriter;
        if (writer == null) {
            return false;
        }
        try {
            writer.write(line);
            writer.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Đóng server đột ngột (mô phỏng server chết) — idempotent, không quăng exception. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(currentClientSocket);
        currentWriter = null;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    /**
     * Mở lại server trên CÙNG port sau khi {@link #close()} — mô phỏng server
     * phục hồi để test auto-reconnect của client. Trạng thái latch/giữ nguyên
     * để test assert số handshake tích lũy được.
     */
    void reopen() throws IOException {
        closed.set(false);
        connectionsAccepted.set(0);
        ServerSocket fresh = new ServerSocket(boundPort);
        fresh.setReuseAddress(true);
        serverSocket = fresh;
        start();
    }

    /** Chờ handshake đến trong thời hạn cho trước. */
    boolean awaitHandshake(long timeoutMs) throws InterruptedException {
        return handshakeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean awaitFirstHeartbeat(long timeoutMs) throws InterruptedException {
        return firstHeartbeatLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean awaitSecondConnection(long timeoutMs) throws InterruptedException {
        return secondConnectionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Đếm số gói tin theo loại đã nhận. */
    long count(MessageType type) {
        return received.stream().filter(m -> m.getType() == type).count();
    }

    /** HandshakePayload đầu tiên nhận được (null nếu chưa có). */
    HandshakePayload firstHandshakePayload() {
        return received.stream()
                .filter(m -> m.getType() == MessageType.HANDSHAKE)
                .map(BaseMessage::getPayload)
                .filter(HandshakePayload.class::isInstance)
                .map(HandshakePayload.class::cast)
                .findFirst().orElse(null);
    }

    /** clientId của handshake đầu tiên. */
    String firstHandshakeClientId() {
        return received.stream()
                .filter(m -> m.getType() == MessageType.HANDSHAKE)
                .map(BaseMessage::getClientId)
                .findFirst().orElse(null);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
