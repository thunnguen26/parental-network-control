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
import com.parental.server.db.ClientDao;
import com.parental.server.db.CommandLogDao;
import com.parental.server.db.DatabaseManager;
import com.parental.server.db.MetricsDao;
import com.parental.server.db.RuleDao;
import com.parental.server.rules.RuleEvaluationEngine;

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

    /** DB layer (Task 4.1) — nullable: null = chạy không database. */
    private final DatabaseManager databaseManager;
    private ClientDao clientDao;
    private MetricsDao metricsDao;
    private CommandLogDao commandLogDao;
    private RuleDao ruleDao;

    /** Rule Engine (Task 4.2) — null khi chạy không database. */
    private RuleEvaluationEngine ruleEvaluationEngine;

    /** Chu kỳ quét Rule Engine (giây) — cấu hình được qua setter, mặc định 30s. */
    private volatile long ruleScanIntervalSeconds = 30;

    private ServerSocket serverSocket;
    private ExecutorService handlerPool;
    private java.util.concurrent.ScheduledExecutorService sweeper;

    public TcpServerEngine() {
        this(DEFAULT_PORT);
    }

    public TcpServerEngine(int port) {
        this(port, Math.max(4, Runtime.getRuntime().availableProcessors() * 2), 30);
    }

    /**
     * Constructor tiện lợi cho ứng dụng thật (ServerApp): cấu hình pool/
     * heartbeat mặc định KÈM {@link DatabaseManager} — đảm bảo mọi DAO
     * ({@link ClientDao}, {@link MetricsDao}, {@link RuleDao}...) được khởi
     * tạo, Dashboard không bao giờ nhận DAO null.
     */
    public TcpServerEngine(int port, DatabaseManager databaseManager) {
        this(port, Math.max(4, Runtime.getRuntime().availableProcessors() * 2), 30,
                databaseManager);
    }

    public TcpServerEngine(int port, int poolSize, long heartbeatTimeoutSeconds) {
        this(port, poolSize, heartbeatTimeoutSeconds, null);
    }

    /**
     * Constructor đầy đủ (Task 4.1): gắn {@link DatabaseManager} để mọi sự
     * kiện (handshake/metrics/ACK/disconnect) được ghi vào SQLite. Truyền
     * {@code null} để chạy không DB (một số test cũ không đụng database).
     */
    public TcpServerEngine(int port, int poolSize, long heartbeatTimeoutSeconds,
                           DatabaseManager databaseManager) {
        this.port = port;
        this.poolSize = poolSize;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        this.databaseManager = databaseManager;
        if (databaseManager != null) {
            this.clientDao = new ClientDao(databaseManager);
            this.metricsDao = new MetricsDao(databaseManager);
            this.commandLogDao = new CommandLogDao(databaseManager);
            this.ruleDao = new RuleDao(databaseManager);
        }
    }

    /**
     * Đặt chu kỳ quét của Rule Engine (giây, mặc định 30s). Gọi TRƯỚC
     * {@link #start()}; nếu server đang chạy thì Rule Engine được thay thế
     * bằng engine mới với chu kỳ mới (restart trong suốt).
     */
    public synchronized void setRuleScanIntervalSeconds(long scanIntervalSeconds) {
        this.ruleScanIntervalSeconds = Math.max(1, scanIntervalSeconds);
        if (ruleEvaluationEngine != null) {
            ruleEvaluationEngine.stop();
            ruleEvaluationEngine = new RuleEvaluationEngine(sessionManager,
                    ruleDao, metricsDao, commandLogDao, ruleScanIntervalSeconds);
            if (isRunning()) {
                ruleEvaluationEngine.start();
            }
        }
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

        if (databaseManager != null) {
            // Task 4.1: client OFFLINE khi disconnect (mọi lý do: EOF, sweep,
            // server shutdown — đều đi qua ClientSessionManager callbacks).
            sessionManager.addConnectionListener(new ClientConnectionListener() {
                @Override
                public void onClientDisconnected(String clientId, DisconnectReason reason) {
                    clientDao.setStatus(clientId, "OFFLINE");
                }
            });
            // Task 4.2: Rule Engine tự phát COMMAND khi client vi phạm hạn mức
            // (khởi tạo muộn ở start() để kịp áp dụng chu kỳ quét từ setter)
            ruleEvaluationEngine = new RuleEvaluationEngine(sessionManager,
                    ruleDao, metricsDao, commandLogDao, ruleScanIntervalSeconds);
            ruleEvaluationEngine.start();
        }

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
        // Task 4.2: tắt Rule Engine TRƯỚC khi đóng session — tránh nó gửi
        // COMMAND xuống phiên đang bị đóng trong graceful shutdown.
        if (ruleEvaluationEngine != null) {
            ruleEvaluationEngine.stop();
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
                handlerPool.execute(new ClientHandler(socket, sessionManager, parser,
                        clientDao, metricsDao, commandLogDao));
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

    /** DAO lưu thông tin client (null nếu engine chạy không DB). */
    public ClientDao getClientDao() {
        return clientDao;
    }

    /** DAO lưu metrics (null nếu engine chạy không DB). */
    public MetricsDao getMetricsDao() {
        return metricsDao;
    }

    /** DAO log lệnh (null nếu engine chạy không DB). */
    public CommandLogDao getCommandLogDao() {
        return commandLogDao;
    }

    /** DAO cấu hình hạn mức (null nếu engine chạy không DB). */
    public RuleDao getRuleDao() {
        return ruleDao;
    }

    /** Rule Engine tự động thực thi hạn mức (null nếu engine chạy không DB). */
    public RuleEvaluationEngine getRuleEvaluationEngine() {
        return ruleEvaluationEngine;
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
