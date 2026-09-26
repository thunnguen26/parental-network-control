package com.parental.client.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;

/**
 * TCP Client Engine (Task 2.2): quản lý vòng đời kết nối tới Server.
 *
 * <p><b>Fail-Safe Networking</b> (AGENTS.md Mục 2):
 * <ul>
 *   <li>Kết nối, handshake, đọc và heartbeat đều chạy trên background daemon
 *       thread — không bao giờ block main thread.</li>
 *   <li>Auto-Reconnect bằng <b>exponential backoff</b>: 1s, 2s, 4s, 8s... tối đa
 *       {@code maxReconnectDelayMs}; reset về {@code initialReconnectDelayMs}
 *       khi kết nối lại thành công.</li>
 *   <li>Khi kết nối thành công: gửi HANDSHAKE ngay, khởi động
 *       {@link ScheduledExecutorService} gửi HEARTBEAT định kỳ
 *       ({@code heartbeatIntervalMs}, mặc định 5s), và luồng Inbound Reader
 *       đọc LDJSON liên tục: ACK → cập nhật thời gian phản hồi cuối;
 *       COMMAND/CONFIG_SYNC → gọi {@link ServerCommandHandler}.</li>
 *   <li><b>Watchdog — Silent Server Detection</b> (Task 2.3): cùng scheduler
 *       heartbeat kiểm tra {@code lastServerActivityMs}; nếu quá
 *       {@code heartbeatTimeoutMs} (mặc định 15s = 3 nhịp tim) không nhận
 *       ACK/bản tin nào từ server → coi là half-open socket / zombie
 *       connection: chủ động đóng socket → connectLoop tự reconnect với
 *       exponential backoff.</li>
 *   <li>{@link #stop()} / {@link #close()} clean shutdown: hủy scheduler,
 *       đóng socket (đánh thức {@code readLine()} đang block), chờ thread kết
 *       thúc — không quăng uncaught exception, không treo tiến trình. JVM
 *       shutdown hook đảm bảo dọn dẹp cả khi tiến trình bị SIGINT/SIGTERM.</li>
 * </ul></p>
 *
 * <p>Chống deadlock trên Windows (bài học từ Task 2.1): socket read không phản
 * ứng với {@code interrupt()} — phải {@code socket.close()} để đánh thức
 * {@code readLine()}; ngoài ra {@code soTimeout} 30s đảm bảo reader luôn thức
 * dậy ngay cả khi close bị trì hoãn; mọi thao tác close đều nuốt exception.</p>
 */
public class TcpClientEngine implements AutoCloseable {

    /** Timeout kết nối TCP (ms). */
    public static final int CONNECT_TIMEOUT_MS = 5_000;
    /** soTimeout cho socket read: reader luôn thức dậy nếu server im lặng. */
    public static final int SO_TIMEOUT_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(TcpClientEngine.class);

    private final ClientConfig config;
    private final DeviceInfoCollector deviceInfoCollector;
    private final JsonProtocolParser parser;
    private final ServerCommandHandler commandHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** Epoch millis của phản hồi (ACK) gần nhất từ server — 0 nếu chưa nhận. */
    private final AtomicLong lastAckTimestampMs = new AtomicLong(0);
    /** Epoch millis của HANDSHAKE gửi thành công gần nhất — 0 nếu chưa gửi. */
    private final AtomicLong lastHandshakeTimestampMs = new AtomicLong(0);
    /** Epoch millis của HOẠT ĐỘNG INBOUND gần nhất từ server (ACK/COMMAND/...). */
    private final AtomicLong lastServerActivityMs = new AtomicLong(0);
    /** Epoch millis của HEARTBEAT gửi thành công gần nhất — phục vụ watchdog. */
    private final AtomicLong lastHeartbeatSentMs = new AtomicLong(0);
    /** Số lần watchdog phát hiện server im lặng và chủ động đóng kết nối. */
    private final AtomicLong watchdogTimeoutCount = new AtomicLong(0);

    private volatile Socket socket;
    private volatile Writer writer;
    private volatile BufferedReader reader;

    /** Bảo vệ ghi: handshake (connector thread) và heartbeat (scheduler thread). */
    private final Object writeLock = new Object();

    private volatile ScheduledExecutorService heartbeatScheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Thread connectorThread;
    private volatile Thread readerThread;
    /** JVM shutdown hook — đăng ký khi start(), hủy khi stop() để không rò rỉ. */
    private volatile Thread shutdownHook;

    /** Số lần reconnect liên tiếp kể từ lần kết nối thành công gần nhất. */
    private volatile int reconnectAttempt = 0;

    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector) {
        this(config, deviceInfoCollector, new JsonProtocolParser(), new ServerCommandHandler() { });
    }

    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           ServerCommandHandler commandHandler) {
        this(config, deviceInfoCollector, new JsonProtocolParser(), commandHandler);
    }

    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           JsonProtocolParser parser, ServerCommandHandler commandHandler) {
        this.config = Objects.requireNonNull(config, "config");
        this.deviceInfoCollector = Objects.requireNonNull(deviceInfoCollector, "deviceInfoCollector");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
    }

    // ------------------------------------------------------------------
    // Vòng đời
    // ------------------------------------------------------------------

    /**
     * Khởi động client: spawn background daemon thread thực hiện kết nối
     * (và auto-reconnect nếu thất bại). Không block thread gọi; an toàn khi
     * gọi nhiều lần (lần thứ 2 là no-op).
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        reconnectAttempt = 0;
        watchdogTimeoutCount.set(0);
        registerShutdownHook();
        Thread thread = new Thread(this::connectLoop, "parental-client-connector-1");
        thread.setDaemon(true);
        connectorThread = thread;
        thread.start();
        log.info("TcpClientEngine started (target {}:{}, clientId={})",
                config.getServerHost(), config.getServerPort(), config.getClientId());
    }

    /**
     * Đăng ký JVM shutdown hook: khi tiến trình bị tắt (SIGINT/SIGTERM hoặc
     * System.exit), hook gọi {@link #stop()} để đóng socket/scheduler sạch
     * sẽ, không rò rỉ tài nguyên. Hook là no-op nếu client đã stop.
     */
    private void registerShutdownHook() {
        Thread hook = new Thread(() -> {
            log.info("JVM shutdown detected — cleaning up client (clientId={})", config.getClientId());
            stop();
        }, "parental-client-shutdown-hook");
        shutdownHook = hook;
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException | SecurityException e) {
            // JVM đang shutdown hoặc không có quyền — bỏ qua, các thread là daemon.
            log.debug("Shutdown hook not registered: {}", e.getMessage());
            shutdownHook = null;
        }
    }

    /**
     * Clean shutdown: hủy heartbeat scheduler, đóng socket (đánh thức
     * {@code readLine()} đang block), interrupt connector nếu đang ngủ backoff,
     * chờ thread kết thúc (có giới hạn). An toàn khi gọi nhiều lần, không quăng
     * exception, không treo tiến trình.
     */
    /**
     * Clean shutdown (Task 2.3): hủy heartbeat scheduler + watchdog, đóng
     * socket (đánh thức {@code readLine()} đang block), interrupt connector,
     * gỡ JVM shutdown hook, chờ thread kết thúc (có giới hạn). An toàn khi
     * gọi nhiều lần, không quăng exception, không treo tiến trình.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("TcpClientEngine stopping (clientId={})", config.getClientId());

        unregisterShutdownHook();
        shutdownHeartbeatScheduler();
        // Đóng socket ĐẦU TIÊN để đánh thức readLine() đang block trên Windows.
        closeConnectionQuietly();

        Thread connector = connectorThread;
        if (connector != null && connector.isAlive() && connector != Thread.currentThread()) {
            connector.interrupt(); // đánh thức sleep trong backoff
            try {
                connector.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Thread reader = readerThread;
        if (reader != null && reader.isAlive() && reader != Thread.currentThread()) {
            try {
                reader.join(1_000); // socket đã đóng → reader sẽ tự thoát rất nhanh
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("TcpClientEngine stopped (clientId={})", config.getClientId());
    }

    /**
     * Gỡ JVM shutdown hook (khi stop() chủ động) — nếu JVM đang shutdown,
     * remove là no-op/haky an toàn vì hook chỉ gọi stop() vốn idempotent.
     */
    private void unregisterShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | SecurityException e) {
                // JVM đang shutdown — bỏ qua.
            }
        }
    }

    /** Phương thức {@code close()} rõ ràng theo yêu cầu Task 2.3 — tương đương {@link #stop()}. */
    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** Epoch millis của ACK gần nhất từ server (0 = chưa nhận ACK nào). */
    public long getLastAckTimestampMs() {
        return lastAckTimestampMs.get();
    }

    /** Epoch millis của HANDSHAKE gửi thành công gần nhất (0 = chưa gửi). */
    public long getLastHandshakeTimestampMs() {
        return lastHandshakeTimestampMs.get();
    }

    /** Số lần reconnect liên tiếp kể từ lần kết nối thành công gần nhất. */
    public int getReconnectAttempt() {
        return reconnectAttempt;
    }

    /** Số lần watchdog phát hiện server im lặng và chủ động đóng kết nối. */
    public long getWatchdogTimeoutCount() {
        return watchdogTimeoutCount.get();
    }

    public ClientConfig getConfig() {
        return config;
    }

    // ------------------------------------------------------------------
    // Connect loop + auto-reconnect (background thread)
    // ------------------------------------------------------------------

    private void connectLoop() {
        long delay = config.getInitialReconnectDelayMs();
        while (running.get()) {
            try {
                openConnection();
                if (!running.get()) {
                    break;
                }
                if (performHandshake()) {
                    // Kết nối + handshake thành công → reset backoff, bật heartbeat, đọc lệnh.
                    onConnectionEstablished();
                    delay = config.getInitialReconnectDelayMs();
                    startHeartbeatScheduler();
                    spawnAndAwaitReader();
                    if (running.get()) {
                        log.warn("Connection lost (clientId={}), scheduling reconnect", config.getClientId());
                    }
                } else {
                    log.warn("HANDSHAKE could not be sent (clientId={}), will retry", config.getClientId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // stop() được gọi trong lúc ngủ backoff/chờ reader
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Connection attempt to {}:{} failed: {} — retrying in {} ms",
                            config.getServerHost(), config.getServerPort(), e.getMessage(), delay);
                }
            } finally {
                closeConnectionQuietly();
            }
            if (!running.get()) {
                break;
            }
            try {
                delay = sleepBackoff(delay); // exponential backoff; InterruptedException khi stop()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // stop() được gọi trong lúc ngủ backoff
            }
        }
        log.info("Connector loop exited (clientId={})", config.getClientId());
    }

    /**
     * Exponential backoff: ngủ {@code currentDelay} ms rồi trả về delay kế
     * tiếp (x2, chặn tối đa {@code maxReconnectDelayMs}) — chuỗi 1s, 2s, 4s,
     * 8s... Được reset về giá trị ban đầu khi kết nối thành công.
     *
     * @throws InterruptedException khi {@link #stop()} được gọi trong lúc ngủ
     */
    private long sleepBackoff(long currentDelay) throws InterruptedException {
        if (running.get()) {
            log.info("Reconnect attempt #{} in {} ms (clientId={})",
                    reconnectAttempt + 1, currentDelay, config.getClientId());
        }
        TimeUnit.MILLISECONDS.sleep(currentDelay);
        reconnectAttempt++;
        return Math.min(currentDelay * 2, config.getMaxReconnectDelayMs());
    }

    private void onConnectionEstablished() {
        connected.set(true);
        reconnectAttempt = 0;
        // Reset mốc hoạt động inbound: heartbeat cũ của phiên trước (stale,
        // nhỏ hơn mốc này) sẽ không bao giờ kích hoạt watchdog nhầm lẫn.
        lastServerActivityMs.set(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Kết nối + HANDSHAKE
    // ------------------------------------------------------------------

    private void openConnection() throws IOException {
        Socket newSocket = new Socket();
        try {
            newSocket.connect(
                    new InetSocketAddress(config.getServerHost(), config.getServerPort()), CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);
            newSocket.setSoTimeout(SO_TIMEOUT_MS);
            writer = new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8);
            reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
            socket = newSocket;
        } catch (IOException e) {
            closeSocketQuietly(newSocket);
            throw e;
        }
        log.info("Connected to {}:{} (local port {})",
                config.getServerHost(), config.getServerPort(), newSocket.getLocalPort());
    }

    /** Thu thập DeviceInfo, đóng gói và gửi HANDSHAKE ngay khi kết nối thành công. */
    private boolean performHandshake() {
        HandshakePayload payload = deviceInfoCollector.collect();
        if (payload == null) {
            log.error("DeviceInfoCollector returned null — cannot send HANDSHAKE");
            return false;
        }
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, config.getClientId(), payload));
        } catch (ProtocolParseException e) {
            log.error("Serialize HANDSHAKE failed: {}", e.getMessage());
            return false;
        }
        try {
            synchronized (writeLock) {
                Writer w = writer;
                if (w == null) {
                    return false;
                }
                w.write(line);
                w.flush();
            }
        } catch (IOException e) {
            log.warn("Failed to send HANDSHAKE: {}", e.getMessage());
            return false;
        }
        lastHandshakeTimestampMs.set(System.currentTimeMillis());
        log.info("HANDSHAKE sent (clientId={}, device={}, os={})",
                config.getClientId(), payload.deviceName(), payload.osVersion());
        return true;
    }

    // ------------------------------------------------------------------
    // Heartbeat scheduler (ScheduledExecutorService, định kỳ heartbeatIntervalMs)
    // ------------------------------------------------------------------

    /**
     * Khởi động scheduler dùng chung: (1) gửi HEARTBEAT định kỳ
     * {@code heartbeatIntervalMs} và (2) Watchdog quét server im lặng mỗi
     * {@code heartbeatTimeoutMs / 4} — phát hiện half-open socket/zombie
     * connection khi đã gửi HEARTBEAT mà không nhận phản hồi nào quá
     * {@code heartbeatTimeoutMs}.
     */
    private void startHeartbeatScheduler() {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("parental-client-heartbeat"));
        heartbeatScheduler = scheduler;
        long period = config.getHeartbeatIntervalMs();
        heartbeatTask = scheduler.scheduleAtFixedRate(this::sendHeartbeat, period, period, TimeUnit.MILLISECONDS);
        long watchdogPeriod = Math.max(1, config.getHeartbeatTimeoutMs() / 4);
        scheduler.scheduleAtFixedRate(this::checkServerSilence, watchdogPeriod, watchdogPeriod, TimeUnit.MILLISECONDS);
        if (!running.get()) { // race với stop(): dọn ngay, không để scheduler sống sót
            shutdownHeartbeatScheduler();
        }
    }

    /**
     * <b>Watchdog — Silent Server Detection</b> (Task 2.3): đo khoảng im lặng
     * TỪ BẢN TIN INBOUND CUỐI CÙNG của server ({@code lastServerActivityMs} —
     * mọi byte nhận được đều làm mới mốc này). Khi server ngừng phản hồi
     * (half-open socket / zombie connection) trong khoảng
     * {@code heartbeatTimeoutMs} dù client vẫn gửi HEARTBEAT bình thường →
     * chủ động đóng socket để connectLoop lập tức reconnect theo exponential
     * backoff.
     *
     * <p>Lưu ý: KHÔNG đo từ {@code lastHeartbeatSentMs} — mốc này được reset
     * mỗi chu kỳ gửi (300ms/5s) nên khoảng im lặng không bao giờ vượt timeout
     * nếu đo từ đó (bug đã gặp và sửa trong Task 2.3). Mốc activity được
     * reset về thời điểm hiện tại ngay khi kết nối/handshake thành công nên
     * không có false positive ở đầu phiên.</p>
     */
    private void checkServerSilence() {
        if (!running.get() || !connected.get()) {
            return;
        }
        long lastActivity = lastServerActivityMs.get();
        long silence = System.currentTimeMillis() - lastActivity;
        if (silence <= config.getHeartbeatTimeoutMs()) {
            return;
        }
        watchdogTimeoutCount.incrementAndGet();
        log.warn("Silent server detected (clientId={}): no inbound message for {} ms > {} ms "
                        + "— closing zombie connection to trigger reconnect",
                config.getClientId(), silence, config.getHeartbeatTimeoutMs());
        Socket s = socket; // chỉ đóng socket HIỆN TẠI — không đụng kết nối mới sau reconnect
        if (s != null && !s.isClosed()) {
            closeSocketQuietly(s);
        }
    }

    private void shutdownHeartbeatScheduler() {
        ScheduledExecutorService scheduler = heartbeatScheduler;
        heartbeatScheduler = null;
        heartbeatTask = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void sendHeartbeat() {
        if (!running.get() || !connected.get()) {
            return;
        }
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.HEARTBEAT, config.getClientId(), Map.of()));
        } catch (ProtocolParseException e) {
            log.error("Serialize HEARTBEAT failed: {}", e.getMessage());
            return;
        }
        try {
            synchronized (writeLock) {
                Writer w = writer;
                Socket s = socket;
                if (w == null || s == null || s.isClosed()) {
                    return;
                }
                w.write(line);
                w.flush();
                lastHeartbeatSentMs.set(System.currentTimeMillis());
            }
        } catch (IOException e) {
            log.warn("HEARTBEAT write failed (clientId={}): {} — closing socket to trigger reconnect",
                    config.getClientId(), e.getMessage());
            Socket s = socket;
            if (s != null) {
                closeSocketQuietly(s); // đánh thức reader đang block → connectLoop reconnect
            }
        }
    }

    // ------------------------------------------------------------------
    // Inbound Reader Thread: đọc LDJSON liên tục từ server
    // ------------------------------------------------------------------

    /** Spawn reader thread rồi block connector cho tới khi reader kết thúc. */
    private void spawnAndAwaitReader() throws InterruptedException {
        Thread io = new Thread(this::runInboundLoop, "parental-client-reader-1");
        io.setDaemon(true);
        readerThread = io;
        io.start();
        while (io.isAlive() && running.get()) {
            io.join(200);
        }
    }

    private void runInboundLoop() {
        BufferedReader localReader = reader;
        if (localReader == null) {
            return;
        }
        try {
            String line;
            while (running.get() && (line = localReader.readLine()) != null) {
                // Mọi byte từ server (kể cả dòng lỗi) đều là dấu hiệu đường truyền sống.
                lastServerActivityMs.set(System.currentTimeMillis());
                if (!line.isBlank()) {
                    dispatchInbound(line);
                }
            }
            if (running.get()) {
                log.info("Server closed the stream (EOF) (clientId={})", config.getClientId());
            }
        } catch (IOException e) {
            if (running.get()) {
                log.warn("Connection read error (clientId={}): {}", config.getClientId(), e.getMessage());
            }
        }
    }

    private void dispatchInbound(String line) {
        BaseMessage<?> message;
        try {
            message = parser.fromLine(line);
        } catch (ProtocolParseException e) {
            log.warn("Dropped malformed inbound frame: {}", e.getMessage());
            return;
        }
        switch (message.getType()) {
            case ACK -> {
                lastAckTimestampMs.set(System.currentTimeMillis());
                log.debug("ACK received (clientId={})", config.getClientId());
            }
            case COMMAND -> handleCommand(message);
            case CONFIG_SYNC -> handleConfigSync(message);
            case HANDSHAKE, HEARTBEAT, METRICS_REPORT ->
                    log.debug("Unexpected {} from server, ignored", message.getType());
        }
    }

    /** Phase 2: chỉ log và gọi listener stub — KHÔNG can thiệp OS (thuộc Phase 3). */
    private void handleCommand(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof CommandPayload command)) {
            log.warn("COMMAND with unexpected payload: {}", message.getPayload());
            return;
        }
        log.info("COMMAND received: id={}, action={}, reason={}",
                command.commandId(), command.action(), command.reason());
        try {
            commandHandler.onCommand(command);
        } catch (RuntimeException e) {
            log.error("ServerCommandHandler.onCommand threw: {}", e.getMessage());
        }
    }

    /** Phase 2: chỉ log và gọi listener stub — việc chặn hosts/netsh thuộc Phase 3. */
    private void handleConfigSync(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof ConfigSyncPayload configSync)) {
            log.warn("CONFIG_SYNC with unexpected payload: {}", message.getPayload());
            return;
        }
        log.info("CONFIG_SYNC received: {} blocked domains, usage limit {}s",
                configSync.blockedDomains().size(), configSync.dailyUsageLimitSeconds());
        try {
            commandHandler.onConfigSync(configSync);
        } catch (RuntimeException e) {
            log.error("ServerCommandHandler.onConfigSync threw: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Đóng kết nối sạch sẽ
    // ------------------------------------------------------------------

    /**
     * Đóng kết nối hiện tại: hủy heartbeat scheduler, đóng socket (đánh thức
     * reader), xóa tham chiếu stream. Không đóng reader/writer trực tiếp từ
     * thread khác (tránh deadlock monitor như Task 2.1) — socket.close() đủ
     * giải phóng tài nguyên và reader thread sẽ tự thoát. Idempotent.
     */
    private void closeConnectionQuietly() {
        connected.set(false);
        shutdownHeartbeatScheduler();
        Socket s = socket;
        socket = null;
        writer = null;
        reader = null;
        if (s != null) {
            closeSocketQuietly(s);
        }
    }

    private void closeSocketQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException | RuntimeException e) {
            log.debug("Socket close ignored: {}", e.getMessage());
        }
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
