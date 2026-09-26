package com.parental.server.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.JsonProtocolParser;

/**
 * TCP Server Engine: lắng nghe {@code ServerSocket} trên background thread,
 * nhận connection và giao cho thread pool ({@link ExecutorService}).
 *
 * <p>Mặc định chạy trên cổng {@value #DEFAULT_PORT}. Hỗ trợ graceful shutdown
 * qua {@link #stop()}. Dead-session (quá lâu không HEARTBEAT) được quét định
 * kỳ bởi scheduled sweeper.</p>
 */
public class TcpServerEngine {

    public static final int DEFAULT_PORT = 8888;

    private static final Logger log = LoggerFactory.getLogger(TcpServerEngine.class);
    private static final int BACKLOG = 50;

    private final int port;
    private final int poolSize;
    private final long heartbeatTimeoutSeconds;
    private final ClientSessionManager sessionManager = new ClientSessionManager();
    private final JsonProtocolParser parser = new JsonProtocolParser();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private ExecutorService handlerPool;
    private java.util.concurrent.ScheduledExecutorService sweeper;

    public TcpServerEngine() {
        this(DEFAULT_PORT);
    }

    public TcpServerEngine(int port) {
        this(port, Math.max(4, Runtime.getRuntime().availableProcessors() * 2), 30);
    }

    public TcpServerEngine(int port, int poolSize, long heartbeatTimeoutSeconds) {
        this.port = port;
        this.poolSize = poolSize;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    /** Mở cổng lắng nghe và khởi động accept loop trên background thread. */
    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port), BACKLOG);

        handlerPool = Executors.newFixedThreadPool(poolSize, namedThreadFactory("parental-client-handler"));
        sweeper = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("parental-session-sweeper"));
        long sweepPeriod = Math.max(1, heartbeatTimeoutSeconds / 2);
        sweeper.scheduleAtFixedRate(this::sweep, Math.max(1, heartbeatTimeoutSeconds), sweepPeriod, TimeUnit.SECONDS);

        Thread acceptor = new Thread(this::acceptLoop, "parental-server-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        log.info("TcpServerEngine started on port {} (pool size {}, heartbeat timeout {}s)",
                getPort(), poolSize, heartbeatTimeoutSeconds);
    }

    /**
     * Graceful shutdown (Task 2.3): đóng listener → dừng sweeper → chủ động
     * đóng TOÀN BỘ client socket (reason SERVER_SHUTDOWN, fire callback
     * {@code onClientDisconnected}) → shutdown executor với
     * {@code awaitTermination} có timeout → shutdownNow khi vượt hạn. Thứ tự
     * đóng session TRƯỚC khi await pool là bắt buộc: handler threads đang
     * block trong {@code readLine()} chỉ thoát được khi socket của chúng bị
     * đóng — await trước đó sẽ luôn chờ đủ 5s.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.debug("ServerSocket close ignored: {}", e.getMessage());
        }
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        // Đóng toàn bộ phiên TRƯỚC để đánh thức handler threads đang block trong readLine().
        sessionManager.closeAll(DisconnectReason.SERVER_SHUTDOWN);
        if (handlerPool != null) {
            handlerPool.shutdown();
            try {
                if (!handlerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Handler pool did not terminate in 5s — forcing shutdownNow");
                    handlerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                handlerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("TcpServerEngine stopped");
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                handlerPool.execute(new ClientHandler(socket, sessionManager, parser));
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Accept loop I/O error: {}", e.getMessage());
                }
            }
        }
        log.info("Acceptor thread exited");
    }

    private void sweep() {
        sessionManager.sweepDeadSessions(heartbeatTimeoutSeconds);
    }

    /** Cổng thực tế đang lắng nghe (hữu ích khi khởi tạo với port 0). */
    public int getPort() {
        return serverSocket != null && serverSocket.isBound() ? serverSocket.getLocalPort() : port;
    }

    public ClientSessionManager getSessionManager() {
        return sessionManager;
    }

    public boolean isRunning() {
        return running.get();
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
