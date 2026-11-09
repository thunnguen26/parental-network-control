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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.client.activity.ActivityCollector;
import com.parental.client.activity.WindowsActivityCollector;
import com.parental.client.config.ClientConfig;
import com.parental.client.enforcement.ActionDispatcher;
import com.parental.client.enforcement.HostsFileManager;
import com.parental.client.enforcement.ProcessBlockerManager;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.client.screen.ScreenCaptureService;
import com.parental.client.identity.ClientIdentityManager;
import com.parental.client.input.RemoteInputController;
import com.parental.client.metrics.NetworkMetricsCollector;
import com.parental.client.metrics.OshiNetworkMetricsCollector;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.ActivityReportPayload;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.common.protocol.payload.UnpairPayload;

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
    /** Bộ thu thập metrics traffic (Task 3.1) — nullable: null = tắt gửi metrics. */
    private final NetworkMetricsCollector metricsCollector;
    /** Domain Blocking Engine (Task 3.2) — nullable: null = không can thiệp hosts. */
    private final HostsFileManager hostsFileManager;
    /** Action Dispatcher (Task 3.3) — nullable: null = COMMAND chỉ log, không thực thi. */
    private final ActionDispatcher actionDispatcher;
    /**
     * Bộ thu thập hoạt động (Activity Monitoring — PROTOCOL.md Mục 3.9) —
     * nullable: null = TẮT gửi ACTIVITY_REPORT (test/legacy).
     */
    private final ActivityCollector activityCollector;
    /**
     * Quản lý định danh bền vững — nullable. Khi nhận {@code UNPAIR} (thiết bị
     * bị xóa khỏi danh sách quản lý), file {@code client_identity.json} bị xóa
     * (mất deviceToken → hết quyền kết nối) và listener {@code onUnpaired}
     * được báo để app quay về màn hình ghép đôi.
     */
    private final ClientIdentityManager identityManager;
    /** Listener sự kiện bị thu hồi ghép đôi (UNPAIR) — nullable. */
    private final Runnable unpairListener;
    /**
     * Quản lý chặn tiến trình (Mở rộng 11) — nullable: null = không đồng bộ
     * danh sách chặn qua CONFIG_SYNC (vẫn kill qua lệnh BLOCK_PROCESS lẻ).
     */
    private final com.parental.client.enforcement.ProcessBlockerManager processBlockerManager;

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

    /**
     * Service chụp và nén màn hình remote (Live Screen Streaming — Mục 3.10).
     * Nullable: null = không có khả năng stream (môi trường không hỗ trợ Robot).
     */
    private final ScreenCaptureService screenCaptureService;

    /**
     * Phiên điều khiển chuột/bàn phím có đồng ý (Mục 3.11) — không bao giờ
     * tự bật; engine chỉ chuyển tiếp yêu cầu/sự kiện tới controller.
     */
    private final RemoteInputController remoteInputController;

    /** Scheduler riêng gửi STREAM_FRAME định kỳ khi stream đang hoạt động. */
    private volatile ScheduledExecutorService screenCaptureScheduler;
    private volatile ScheduledFuture<?> screenCaptureTask;

    private volatile Socket socket;
    private volatile Writer writer;
    private volatile BufferedReader reader;

    /** Bảo vệ ghi: handshake (connector thread) và heartbeat (scheduler thread). */
    private final Object writeLock = new Object();

    private volatile ScheduledExecutorService heartbeatScheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    /** Scheduler riêng cho METRICS_REPORT — không làm nghẽn heartbeat/watchdog (Task 3.1). */
    private volatile ScheduledExecutorService metricsScheduler;
    /** Scheduler riêng cho ACTIVITY_REPORT — thu thập có thể tốn ~3s (PowerShell). */
    private volatile ScheduledExecutorService activityScheduler;
    /** Gửi INPUT_ACK ngoài EDT (consent/stop callback có thể chạy trên EDT). */
    private volatile ExecutorService remoteInputAckExecutor;
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
        this(config, deviceInfoCollector, parser, commandHandler,
                new OshiNetworkMetricsCollector(), // mặc định: bật metrics qua OSHI
                null, // mặc định: không can thiệp hosts (UI/enforcement Phase sau quyết định)
                null); // mặc định: COMMAND chỉ log (enforcement tắt)
    }

    /** Constructor đầy đủ — test có thể inject collector/hosts/dispatcher giả lập (hoặc null để tắt). */
    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           JsonProtocolParser parser, ServerCommandHandler commandHandler,
                           NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                           ActionDispatcher actionDispatcher) {
        this(config, deviceInfoCollector, parser, commandHandler, metricsCollector,
                hostsFileManager, actionDispatcher, null, null, null);
    }

    /**
     * Constructor đầy đủ nhất (Unpair): kèm {@link ClientIdentityManager} +
     * listener {@code onUnpaired} — nhận {@code UNPAIR} → khôi phục mạng, xóa
     * {@code client_identity.json}, gọi listener (app quay về màn hình ghép
     * đôi) rồi ngừng kết nối. Truyền {@code null} cho cả 2 khi không dùng.
     */
    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           JsonProtocolParser parser, ServerCommandHandler commandHandler,
                           NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                           ActionDispatcher actionDispatcher, ClientIdentityManager identityManager,
                           Runnable unpairListener) {
        this(config, deviceInfoCollector, parser, commandHandler, metricsCollector,
                hostsFileManager, actionDispatcher, identityManager, unpairListener, null);
    }

    /**
     * Constructor đầy đủ nhất (Activity Monitoring): kèm
     * {@link ActivityCollector} — gửi ACTIVITY_REPORT định kỳ
     * {@code config.getActivityIntervalMs()} (0 = tắt). Truyền null = tắt.
     */
    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           JsonProtocolParser parser, ServerCommandHandler commandHandler,
                           NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                           ActionDispatcher actionDispatcher, ClientIdentityManager identityManager,
                           Runnable unpairListener, ActivityCollector activityCollector) {
        this(config, deviceInfoCollector, parser, commandHandler, metricsCollector,
                hostsFileManager, actionDispatcher, identityManager, unpairListener,
                activityCollector, null);
    }

    /**
     * Constructor đầy đủ nhất (Mở rộng 11): kèm {@code ProcessBlockerManager}
     * — nhận CONFIG_SYNC.blockedProcesses (REPLACE-ALL) đồng bộ danh sách chặn
     * tiến trình từ Server. Truyền null = bỏ qua đồng bộ (chỉ còn kill qua
     * lệnh BLOCK_PROCESS lẻ).
     */
    public TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                           JsonProtocolParser parser, ServerCommandHandler commandHandler,
                           NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                           ActionDispatcher actionDispatcher, ClientIdentityManager identityManager,
                           Runnable unpairListener, ActivityCollector activityCollector,
                           com.parental.client.enforcement.ProcessBlockerManager processBlockerManager) {
        // Mục 3.10: Live Screen Streaming cần một ScreenCaptureService thật — trước đây
        // field luôn null nên STREAM_CONTROL START bị bỏ qua và client không bao giờ gửi frame.
        this(config, deviceInfoCollector, parser, commandHandler, metricsCollector,
                hostsFileManager, actionDispatcher, identityManager, unpairListener,
                activityCollector, processBlockerManager, new ScreenCaptureService(),
                RemoteInputController.createDefault());
    }

    /**
     * Full constructor with injectable screen capture service (test seam).
     * Truyền {@code null} để engine tự tạo {@link ScreenCaptureService} mặc định.
     */
    TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                    JsonProtocolParser parser, ServerCommandHandler commandHandler,
                    NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                    ActionDispatcher actionDispatcher, ClientIdentityManager identityManager,
                    Runnable unpairListener, ActivityCollector activityCollector,
                    com.parental.client.enforcement.ProcessBlockerManager processBlockerManager,
                    ScreenCaptureService screenCaptureService) {
        this(config, deviceInfoCollector, parser, commandHandler, metricsCollector,
                hostsFileManager, actionDispatcher, identityManager, unpairListener,
                activityCollector, processBlockerManager, screenCaptureService,
                RemoteInputController.createDefault());
    }

    /**
     * Full constructor with injectable remote-input controller (test seam).
     * Truyền {@code null} cho controller để dùng controller production.
     */
    TcpClientEngine(ClientConfig config, DeviceInfoCollector deviceInfoCollector,
                    JsonProtocolParser parser, ServerCommandHandler commandHandler,
                    NetworkMetricsCollector metricsCollector, HostsFileManager hostsFileManager,
                    ActionDispatcher actionDispatcher, ClientIdentityManager identityManager,
                    Runnable unpairListener, ActivityCollector activityCollector,
                    com.parental.client.enforcement.ProcessBlockerManager processBlockerManager,
                    ScreenCaptureService screenCaptureService,
                    RemoteInputController remoteInputController) {
        this.config = Objects.requireNonNull(config, "config");
        this.deviceInfoCollector = Objects.requireNonNull(deviceInfoCollector, "deviceInfoCollector");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.commandHandler = Objects.requireNonNull(commandHandler, "commandHandler");
        this.metricsCollector = metricsCollector; // nullable — null = tắt gửi metrics
        this.hostsFileManager = hostsFileManager; // nullable — null = không can thiệp hosts
        this.actionDispatcher = actionDispatcher; // nullable — null = COMMAND chỉ log
        this.identityManager = identityManager;   // nullable — null = không xử lý UNPAIR file
        this.unpairListener = unpairListener;     // nullable — null = không callback UNPAIR
        this.activityCollector = activityCollector; // nullable — null = tắt ACTIVITY_REPORT
        this.processBlockerManager = processBlockerManager; // nullable — null = không sync qua CONFIG_SYNC
        this.screenCaptureService = screenCaptureService != null
                ? screenCaptureService : new ScreenCaptureService();
        this.remoteInputController = remoteInputController != null
                ? remoteInputController : RemoteInputController.createDefault();
        this.remoteInputController.setAckSender(this::sendRemoteInputAck);
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
            // Task 3.2: gỡ block hosts khi tắt app — tránh con cái bị chặn mạng
            // ngoài ý muốn sau khi parental control đã thoát.
            if (hostsFileManager != null) {
                hostsFileManager.clearBlocks();
            }
            // Task 3.3: luôn khôi phục mạng khi tắt — không để máy mất mạng vĩnh viễn.
            if (actionDispatcher != null) {
                actionDispatcher.cleanup();
            }
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
        stopScreenCaptureScheduler();
        shutdownMetricsScheduler();
        shutdownActivityScheduler();
        // Tắt stream (nếu đang bật): giải phóng Robot + dừng chụp màn hình khi engine dừng.
        if (screenCaptureService != null) {
            screenCaptureService.stop();
        }
        // Mục 3.11: dừng phiên điều khiển (nếu có) và nhả mọi phím/nút đang giữ.
        remoteInputController.stopForConnectionLoss();
        shutdownRemoteInputAckExecutor();
        // Đóng socket ĐẦU TIÊN để đánh thức readLine() đang block trên Windows.
        closeConnectionQuietly();
        // Task 3.3: luôn khôi phục mạng khi client tắt (xóa firewall rule) —
        // không để máy con trong trạng thái mất mạng vĩnh viễn.
        if (actionDispatcher != null) {
            actionDispatcher.cleanup();
        }
        // Engine đã dừng thì watchdog chặn app không được tiếp tục kill ngầm.
        // (UNPAIR còn xóa hẳn danh sách chặn ở handleUnpair.)
        if (processBlockerManager != null) {
            try {
                processBlockerManager.stopWatchdog();
            } catch (RuntimeException e) {
                log.warn("Stopping process watchdog failed: {}", e.getMessage());
            }
        }

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
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("Connection attempt to {}:{} failed: {} — retrying in {} ms",
                            config.getServerHost(), config.getServerPort(), e.getMessage(), delay);
                }
                try {
                    delay = sleepBackoff(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (!running.get()) {
                break;
            }
            if (performHandshake()) {
                // Kết nối + handshake thành công → reset backoff, bật heartbeat, đọc lệnh.
                onConnectionEstablished();
                delay = config.getInitialReconnectDelayMs();
                startHeartbeatScheduler();
                try {
                    spawnAndAwaitReader();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    connected.set(false);
                    break;
                }
                // isConnected() đã là false khi socket sập (closeConnectionQuietly
                // set false trước khi reader trả về null). Đây chỉ là no-op an toàn
                // vì AtomicBoolean thread-safe.
                connected.set(false);
                if (running.get()) {
                    log.warn("Connection lost (clientId={}), scheduling reconnect", config.getClientId());
                }
            } else {
                log.warn("HANDSHAKE could not be sent (clientId={}), will retry", config.getClientId());
            }
            if (!running.get()) {
                break;
            }
            closeConnectionQuietly();
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
        return nextReconnectDelay(currentDelay, config.getMaxReconnectDelayMs());
    }

    static long nextReconnectDelay(long currentDelay, long maxReconnectDelayMs) {
        if (currentDelay <= 0 || maxReconnectDelayMs <= 0) {
            throw new IllegalArgumentException("Reconnect delays must be positive");
        }
        if (currentDelay >= maxReconnectDelayMs / 2) {
            return maxReconnectDelayMs;
        }
        return Math.min(currentDelay * 2, maxReconnectDelayMs);
    }

    private void onConnectionEstablished() {
        connected.set(true);
        reconnectAttempt = 0;
        // Reset mốc hoạt động inbound: heartbeat cũ của phiên trước (stale,
        // nhỏ hơn mốc này) sẽ không bao giờ kích hoạt watchdog nhầm lẫn.
        lastServerActivityMs.set(System.currentTimeMillis());
        startMetricsScheduler();
        // Activity Monitoring (Mục 3.9): scheduler riêng, không block heartbeat.
        startActivityScheduler();
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

    /**
     * Thu thập DeviceInfo + {@code deviceToken} (Client Identity Persistence),
     * đóng gói và gửi HANDSHAKE ngay khi kết nối thành công. Token null khi
     * thiết bị chưa ghép đôi — server sẽ từ chối với UNAUTHORIZED_DEVICE.
     */
    private boolean performHandshake() {
        HandshakePayload collected = deviceInfoCollector.collect();
        if (collected == null) {
            log.error("DeviceInfoCollector returned null — cannot send HANDSHAKE");
            return false;
        }
        // Đính token bền vững (nếu đã ghép đôi) vào payload handshake.
        HandshakePayload payload = new HandshakePayload(
                collected.deviceName(), collected.deviceType(), collected.osVersion(),
                collected.ipAddress(), collected.macAddress(), config.getDeviceToken());
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
        log.info("HANDSHAKE sent (clientId={}, device={}, os={}, paired={})",
                config.getClientId(), payload.deviceName(), payload.osVersion(),
                payload.deviceToken() != null);
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

    // ------------------------------------------------------------------
    // Screen Capture scheduler (Live Screen Streaming — Mục 3.10): gửi
    // STREAM_FRAME định kỳ trên thread riêng, không block heartbeat/reader.
    // ------------------------------------------------------------------

    /** Khởi động scheduler riêng gửi STREAM_FRAME khi stream đang bật. */
    private void startScreenCaptureScheduler() {
        if (screenCaptureService == null || !screenCaptureService.isStarted()) {
            return;
        }
        // START lặp (mở lại dialog / server gửi lại lệnh) không được tạo thêm
        // scheduler thứ 2: scheduler cũ vẫn chạy và không còn reference để dừng
        // → client gửi frame trùng, tăng gấp đôi băng thông.
        stopScreenCaptureScheduler();
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("parental-client-screencapture"));
        screenCaptureScheduler = scheduler;
        ScreenCaptureService.ScreenControlRequest req = screenCaptureService.currentRequest();
        long periodMs = req != null && req.fps() > 0
                ? Math.max(1, (long) (1000.0 / req.fps()))
                : 200L;
        screenCaptureTask = scheduler.scheduleAtFixedRate(this::sendScreenFrame, periodMs, periodMs, TimeUnit.MILLISECONDS);
        if (!running.get()) {
            stopScreenCaptureScheduler();
        }
    }

    /** Dừng scheduler gửi STREAM_FRAME. */
    private void stopScreenCaptureScheduler() {
        ScheduledExecutorService scheduler = screenCaptureScheduler;
        screenCaptureScheduler = null;
        screenCaptureTask = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Gửi 1 frame STREAM_FRAME đến server khi có phiên stream đang hoạt động.
     * Gọi từ scheduler riêng — không block heartbeat/reader.
     */
    private void sendScreenFrame() {
        if (!running.get() || !connected.get() || screenCaptureService == null
                || !screenCaptureService.isStarted()) {
            return;
        }
        ScreenFramePayload frame = screenCaptureService.captureFrame();
        if (frame == null) {
            return;
        }
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.STREAM_FRAME,
                    config.getClientId(), frame));
        } catch (ProtocolParseException e) {
            log.error("Serialize STREAM_FRAME failed: {}", e.getMessage());
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
            }
            log.debug("STREAM_FRAME sent (seq={} {}x{} quality={} tsMs={})",
                    frame.seqNo(), frame.width(), frame.height(),
                    frame.quality(), frame.tsMs());
        } catch (IOException e) {
            log.warn("STREAM_FRAME write failed (clientId={}): {}",
                    config.getClientId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Metrics scheduler (Task 3.1): gửi METRICS_REPORT định kỳ, scheduler
    // RIÊNG để không làm nghẽn heartbeat/watchdog
    // ------------------------------------------------------------------

    /**
     * Khởi động scheduler riêng gửi METRICS_REPORT mỗi
     * {@code metricsIntervalMs} (mặc định 5s). Chỉ chạy khi
     * {@code metricsCollector != null} (test có thể tắt bằng null).
     */
    private void startMetricsScheduler() {
        if (metricsCollector == null || metricsScheduler != null) {
            return;
        }
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("parental-client-metrics"));
        metricsScheduler = scheduler;
        long period = config.getMetricsIntervalMs();
        scheduler.scheduleAtFixedRate(this::sendMetricsReport, period, period, TimeUnit.MILLISECONDS);
        if (!running.get()) { // race với stop(): dọn ngay
            shutdownMetricsScheduler();
        }
    }

    private void shutdownMetricsScheduler() {
        ScheduledExecutorService scheduler = metricsScheduler;
        metricsScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Activity scheduler (Activity Monitoring — PROTOCOL.md Mục 3.9): gửi
    // ACTIVITY_REPORT định kỳ, scheduler RIÊNG — PowerShell có thể tốn tới 3s
    // nên tuyệt đối không chạy chung heartbeat/reader (không block lệnh).
    // ------------------------------------------------------------------

    /** Khởi động scheduler ACTIVITY_REPORT (chỉ khi collector != null và interval > 0). */
    private void startActivityScheduler() {
        if (activityCollector == null || activityScheduler != null
                || config.getActivityIntervalMs() <= 0) {
            return;
        }
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("parental-client-activity"));
        activityScheduler = scheduler;
        long period = config.getActivityIntervalMs();
        scheduler.scheduleAtFixedRate(this::sendActivityReport, period, period, TimeUnit.MILLISECONDS);
        if (!running.get()) { // race với stop(): dọn ngay
            shutdownActivityScheduler();
        }
    }

    private void shutdownActivityScheduler() {
        ScheduledExecutorService scheduler = activityScheduler;
        activityScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Thu thập hoạt động từ {@link ActivityCollector} rồi đẩy gói
     * ACTIVITY_REPORT lên Server (ghi đồng bộ qua {@code writeLock} cùng
     * heartbeat/metrics — không hỏng khung LDJSON). Collector rỗng (không xác
     * định được) → bỏ qua chu kỳ, không gửi tin rỗng làm nhiễu nhật ký.
     */
    private void sendActivityReport() {
        if (!running.get() || !connected.get() || activityCollector == null) {
            return;
        }
        ActivityReportPayload activity;
        try {
            activity = activityCollector.collect();
        } catch (RuntimeException e) {
            // Defensive: collector lỗi không được làm chết scheduler (AGENTS.md)
            log.error("Activity collector threw: {}", e.getMessage());
            return;
        }
        if (activity == null
                || (activity.activeProcessName().isEmpty() && activity.topProcesses().isEmpty())) {
            return; // không thu được gì (headless/khóa màn hình) — không gửi rỗng
        }
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.ACTIVITY_REPORT,
                    config.getClientId(), activity));
        } catch (ProtocolParseException e) {
            log.error("Serialize ACTIVITY_REPORT failed: {}", e.getMessage());
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
            }
            log.debug("ACTIVITY_REPORT sent (process={}, title={})",
                    activity.activeProcessName(), activity.activeWindowTitle());
        } catch (IOException e) {
            log.warn("ACTIVITY_REPORT write failed (clientId={}): {}",
                    config.getClientId(), e.getMessage());
        }
    }

    /**
     * Lấy metrics từ {@link NetworkMetricsCollector} rồi đẩy gói
     * METRICS_REPORT lên Server qua luồng TCP (ghi đồng bộ qua writeLock cùng
     * heartbeat/handshake — không hỏng khung LDJSON). Trạng thái
     * {@code networkStatus} mặc định "ACTIVE" (cờ cắm cho Task 3.3).
     */
    private void sendMetricsReport() {
        if (!running.get() || !connected.get() || metricsCollector == null) {
            return;
        }
        MetricsPayload metrics;
        try {
            metrics = metricsCollector.collect();
        } catch (RuntimeException e) {
            // Defensive: collector lỗi không được làm chết scheduler
            log.error("Metrics collector threw: {}", e.getMessage());
            return;
        }
        if (metrics == null) {
            return;
        }
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.METRICS_REPORT, config.getClientId(), metrics));
        } catch (ProtocolParseException e) {
            log.error("Serialize METRICS_REPORT failed: {}", e.getMessage());
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
            }
            log.debug("METRICS_REPORT sent (down={}B, up={}B)",
                    metrics.downloadBytes(), metrics.uploadBytes());
        } catch (IOException e) {
            log.warn("METRICS_REPORT write failed (clientId={}): {}", config.getClientId(), e.getMessage());
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
                lastAckTimestampMs.set(
                        System.currentTimeMillis());
                log.debug("ACK received (clientId={})", config.getClientId());
            }
            case COMMAND -> handleCommand(message);
            case CONFIG_SYNC -> handleConfigSync(message);
            case UNPAIR -> handleUnpair(message);
            case STREAM_CONTROL -> handleStreamControl(message);
            case STREAM_FRAME -> handleStreamFrame(message);
            case INPUT_CONTROL -> handleRemoteInputControl(message);
            case INPUT_EVENT -> handleRemoteInputEvent(message);
            case INPUT_ACK -> log.debug("INPUT_ACK from server ignored (client sends only)");
            case HANDSHAKE, HEARTBEAT, METRICS_REPORT, PAIR_REQUEST, PAIR_RESPONSE ->
                    log.debug("Unexpected {} from server, ignored", message.getType());
            case ACTIVITY_REPORT -> log.debug("ACTIVITY_REPORT from server ignored (client does not expect)");
        }
    }

    /**
     * Xử lý UNPAIR (PROTOCOL.md Mục 3.8 — thiết bị bị phụ huynh xóa khỏi
     * danh sách quản lý): khôi phục mạng bình thường (resume — gỡ firewall
     * rule + hosts block), xóa {@code client_identity.json} (mất deviceToken
     * → hết quyền kết nối), báo listener (app quay về màn hình ghép đôi) rồi
     * ngừng engine. Server đóng socket ngay sau khi gửi UNPAIR nên không cần
     * chủ động ngắt — connectLoop sẽ không reconnect được vì stop() đã chạy.
     */
    private void handleUnpair(BaseMessage<?> message) {
        String reason = message.getPayload() instanceof UnpairPayload unpair
                ? unpair.reason() : UnpairPayload.DEFAULT_REASON;
        log.warn("UNPAIR received: {} — restoring network and resetting identity", reason);

        // 1. Khôi phục mạng: xóa firewall rule (PAUSED → ACTIVE) + gỡ hosts block.
        if (actionDispatcher != null) {
            try {
                actionDispatcher.cleanup(); // resume network + cleanup notifier
            } catch (RuntimeException e) {
                log.error("UNPAIR cleanup (network restore) failed: {}", e.getMessage());
            }
        }
        if (hostsFileManager != null) {
            try {
                hostsFileManager.clearBlocks(); // gỡ domains block
            } catch (RuntimeException e) {
                log.error("UNPAIR clear hosts blocks failed: {}", e.getMessage());
            }
        }
        // Thiết bị đã bị gỡ khỏi quản lý: dừng watchdog + xóa danh sách chặn
        // tiến trình. Nếu chỉ dừng engine mà giữ file, watchdog cũ vẫn kill
        // app và lần ghép đôi sau sẽ áp lại chính sách cũ trước khi sync.
        if (processBlockerManager != null) {
            try {
                processBlockerManager.stopWatchdog();
                processBlockerManager.clearAll();
            } catch (RuntimeException e) {
                log.error("UNPAIR clear blocked processes failed: {}", e.getMessage());
            }
        }

        // 2. Xóa định danh bền vững — mất deviceToken → handshake sau bị từ chối.
        if (identityManager != null) {
            try {
                java.nio.file.Files.deleteIfExists(identityManager.getIdentityFilePath());
                log.warn("Identity file deleted (device unpaired): {}",
                        identityManager.getIdentityFilePath());
            } catch (Exception e) {
                log.error("Cannot delete identity file {}: {}",
                        identityManager.getIdentityFilePath(), e.getMessage());
            }
        }

        // 3. Dừng engine — KHÔNG reconnect nữa (đã mất quyền kết nối).
        stop();

        // 4. Báo listener trên thread riêng — app quyết định quay về màn hình
        // ghép đôi (không bao giờ block luồng đọc socket).
        Runnable listener = unpairListener;
        if (listener != null) {
            Thread notify = new Thread(() -> {
                try {
                    listener.run();
                } catch (RuntimeException e) {
                    log.error("Unpair listener threw: {}", e.getMessage(), e);
                }
            }, "parental-client-unpair-notify");
            notify.setDaemon(true);
            notify.start();
        }
    }

    /**
     * Xử lý STREAM_CONTROL (Mục 3.10 — Live Screen Streaming): server ra lệnh
     * bật/tắt stream màn hình. Client cập nhật {@link ScreenCaptureService}
     * theo yêu cầu (START → bắt đầu capture thread riêng; STOP → dừng).
     * Không có ACK bắt buộc — xử lý best-effort, không ảnh hưởng heartbeat/reader.
     */
    private void handleStreamControl(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof StreamControlPayload control)) {
            log.warn("STREAM_CONTROL with unexpected payload: {}", message.getPayload());
            return;
        }
        log.debug("STREAM_CONTROL received: action={} width={} height={} quality={} fps={}",
                control.action(), control.width(), control.height(),
                control.quality(), control.fps());
        if (screenCaptureService != null) {
            if (StreamControlPayload.ACTION_START.equalsIgnoreCase(control.action())) {
                screenCaptureService.start(
                        ScreenCaptureService.ScreenControlRequest.fromPayload(control));
                startScreenCaptureScheduler();
            } else {
                screenCaptureService.stop();
                stopScreenCaptureScheduler();
            }
        }
    }

    /**
     * Xử lý STREAM_FRAME (Mục 3.10 — Live Screen Streaming): frame ảnh từ
     * client (khi server đang xem màn hình). Hiện tại client không xử lý
     * frame nguồn (chỉ gửi), nên log debug mỗi frame nếu có.
     */
    private void handleStreamFrame(BaseMessage<?> message) {
        if (message.getPayload() instanceof ScreenFramePayload frame) {
            log.debug("STREAM_FRAME received (ignored on client): {}x{} seq={} quality={} tsMs={}",
                    frame.width(), frame.height(), frame.seqNo(), frame.quality(), frame.tsMs());
        } else {
            log.warn("STREAM_FRAME with unexpected payload: {}", message.getPayload());
        }
    }

    /**
     * INPUT_CONTROL (Mục 3.11): REQUEST → hỏi người dùng tại máy Client; STOP
     * → thu hồi đúng phiên hiện tại. Không tự chấp nhận, không bật điều khiển
     * khi thiếu hộp thoại/không có GUI.
     */
    private void handleRemoteInputControl(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof RemoteInputControlPayload control)) {
            log.warn("INPUT_CONTROL with unexpected payload: {}", message.getPayload());
            return;
        }
        if (RemoteInputControlPayload.ACTION_REQUEST.equalsIgnoreCase(control.action())) {
            RemoteInputController.RequestResult result = remoteInputController.handleStartRequest(
                    control.sessionId(), control.effectiveConsentTimeoutSeconds(),
                    control.effectiveMaxSessionSeconds());
            log.info("INPUT_CONTROL REQUEST {} -> {}", control.sessionId(), result);
        } else if (RemoteInputControlPayload.ACTION_STOP.equalsIgnoreCase(control.action())) {
            remoteInputController.handleStopRequest(control.sessionId());
        } else {
            log.warn("INPUT_CONTROL action không hợp lệ: {}", control.action());
        }
    }

    /**
     * INPUT_EVENT (Mục 3.11): controller kiểm tra phiên đã được chấp thuận,
     * đúng sessionId/seqNo/tọa độ/loại/tốc độ trước khi inject. Sự kiện bị từ
     * chối không chạm tới Robot.
     */
    private void handleRemoteInputEvent(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof RemoteInputEventPayload event)) {
            log.warn("INPUT_EVENT with unexpected payload: {}", message.getPayload());
            return;
        }
        RemoteInputController.EventResult result = remoteInputController.handleEvent(event);
        if (result != RemoteInputController.EventResult.APPLIED) {
            log.debug("INPUT_EVENT rejected ({}): {}", result, event.eventType());
        }
    }

    /**
     * Gửi INPUT_ACK (Mục 3.11) trên thread nền — không chứa nội dung người
     * dùng gõ, không chặn EDT khi callback đồng ý/dừng chạy trên Swing.
     */
    private void sendRemoteInputAck(RemoteInputAckPayload ack) {
        if (ack == null) {
            return;
        }
        try {
            remoteInputAckExecutor().execute(() -> sendRemoteInputAckNow(ack));
        } catch (RejectedExecutionException e) {
            log.debug("INPUT_ACK dropped (executor stopping): {}", ack.status());
        }
    }

    private synchronized ExecutorService remoteInputAckExecutor() {
        ExecutorService executor = remoteInputAckExecutor;
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(
                    namedDaemonFactory("parental-client-input-ack"));
            remoteInputAckExecutor = executor;
        }
        return executor;
    }

    private void shutdownRemoteInputAckExecutor() {
        ExecutorService executor;
        synchronized (this) {
            executor = remoteInputAckExecutor;
            remoteInputAckExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void sendRemoteInputAckNow(RemoteInputAckPayload ack) {
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.INPUT_ACK,
                    config.getClientId(), ack));
        } catch (ProtocolParseException e) {
            log.error("Serialize INPUT_ACK failed ({}): {}", ack.status(), e.getMessage());
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
            }
            log.info("INPUT_ACK sent: status={} (sessionId={})",
                    ack.status(), ack.sessionId());
        } catch (IOException e) {
            log.warn("Failed to send INPUT_ACK ({}): {}", ack.status(), e.getMessage());
        }
    }

    /**
     * Xử lý COMMAND (Task 3.3 + Task 2.2): điều phối qua {@link ActionDispatcher}
     * (PAUSE/RESUME/ALERT — thực thi enforcer, sync networkStatus, hiện alert)
     * rồi gửi ACK kèm {@code commandId} về Server (PROTOCOL.md Mục 3.5) để
     * Server biết lệnh đã được tiếp nhận. Enforcer lỗi → không ACK (Server
     * có thể retry). Không bao giờ ném exception làm chết luồng đọc socket.
     */
    private void handleCommand(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof CommandPayload command)) {
            log.warn("COMMAND with unexpected payload: {}", message.getPayload());
            return;
        }
        log.info("COMMAND received: id={}, action={}, reason={}",
                command.commandId(), command.action(), command.reason());

        String ackedCommandId = null;
        if (actionDispatcher != null) {
            try {
                ackedCommandId = actionDispatcher.dispatch(command);
            } catch (RuntimeException e) {
                log.error("ActionDispatcher threw for command {}: {}", command.commandId(), e.getMessage());
            }
        }
        if (ackedCommandId != null) {
            sendCommandAck(ackedCommandId); // lệnh thực thi thành công → ACK kèm commandId
        } else if (actionDispatcher == null) {
            sendCommandAck(command.commandId()); // dispatcher tắt: vẫn ACK để không treo Server
        }

        try {
            commandHandler.onCommand(command);
        } catch (RuntimeException e) {
            log.error("ServerCommandHandler.onCommand threw: {}", e.getMessage());
        }
    }

    /** Gửi ACK cho lệnh COMMAND — payload chứa commandId (PROTOCOL.md Mục 3.5). */
    private void sendCommandAck(String commandId) {
        sendAckPayload(java.util.Map.of("commandId", String.valueOf(commandId)),
                "command " + commandId);
    }

    /** Gửi ACK chung (command/config) — một nơi khóa writeLock, không ném. */
    private void sendAckPayload(Map<String, Object> payload, String description) {
        String line;
        try {
            line = parser.toLine(BaseMessage.of(MessageType.ACK, config.getClientId(), payload));
        } catch (ProtocolParseException e) {
            log.error("Serialize ACK failed ({}): {}", description, e.getMessage());
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
            }
            log.info("ACK sent for {}", description);
        } catch (IOException e) {
            log.warn("Failed to send ACK for {}: {}", description, e.getMessage());
        }
    }

    /**
     * Xử lý CONFIG_SYNC (Task 3.2 + Task 2.2): đọc {@code blockedDomains} và
     * áp vào file hosts qua {@link HostsFileManager} (idempotent, defensive —
     * thiếu quyền chỉ log WARN, không crash); sau đó gọi listener stub.
     * Việc ngắt card mạng qua netsh thuộc Task 3.3.
     */
    private void handleConfigSync(BaseMessage<?> message) {
        if (!(message.getPayload() instanceof ConfigSyncPayload configSync)) {
            log.warn("CONFIG_SYNC with unexpected payload: {}", message.getPayload());
            return;
        }
        log.info("CONFIG_SYNC received: {} blocked domains, {} blocked processes, usage limit {}s (rev={})",
                configSync.blockedDomains().size(), configSync.blockedProcesses().size(),
                configSync.dailyUsageLimitSeconds(), configSync.configRevision());

        boolean hostsApplied = true;
        boolean bypassLocked = true;
        String hostsMessage = "hosts=disabled";
        if (hostsFileManager != null) {
            try {
                HostsFileManager.ApplyResult result =
                        hostsFileManager.applyBlockedDomainsDetailed(configSync.blockedDomains());
                hostsApplied = result.applied();
                bypassLocked = result.bypassFullyLocked();
                hostsMessage = result.message();
                if (!hostsFileManager.isBypassConfigured()
                        && !configSync.blockedDomains().isEmpty()) {
                    bypassLocked = false;
                    hostsMessage += "; bypass=NOT-CONFIGURED";
                }
            } catch (RuntimeException e) {
                // tuyệt đối không để lỗi hosts làm chết luồng đọc socket
                hostsApplied = false;
                bypassLocked = false;
                hostsMessage = "hosts: exception " + e.getMessage();
                log.error("Applying blocked domains failed: {}", e.getMessage());
            }
        } else if (!configSync.blockedDomains().isEmpty()) {
            hostsApplied = false;
            hostsMessage = "hosts: manager chưa cấu hình — không có lớp chặn domain";
        }

        // Mở rộng 11: đồng bộ danh sách chặn tiến trình (REPLACE-ALL) — app bị
        // gỡ khỏi danh sách = bỏ chặn (ngừng kill, mở lại bình thường).
        boolean processApplied = true;
        String processMessage = "processes=applied";
        if (processBlockerManager != null) {
            try {
                ProcessBlockerManager.ReplaceResult result =
                        processBlockerManager.replaceBlockedProcessesDetailed(
                                configSync.blockedProcesses());
                processApplied = result.applied();
                if (result.anyAccessDenied()) {
                    processMessage = "processes=PARTIAL (taskkill Access Denied — app elevated vẫn chạy)";
                }
            } catch (RuntimeException e) {
                processApplied = false;
                processMessage = "processes=FAILED (" + e.getMessage() + ")";
                log.error("Applying blocked processes failed: {}", e.getMessage());
            }
        } else if (!configSync.blockedProcesses().isEmpty()) {
            processApplied = false;
            processMessage = "processes=FAILED (manager chưa cấu hình)";
        }

        boolean fullyApplied = hostsApplied && processApplied && bypassLocked;
        String status = fullyApplied ? "APPLIED"
                : (hostsApplied || processApplied) ? "PARTIAL" : "FAILED";
        String configMessage = hostsMessage + "; " + processMessage;
        if (configMessage.length() > 400) {
            configMessage = configMessage.substring(0, 397) + "...";
        }
        if (!fullyApplied) {
            log.warn("CONFIG_SYNC rev={} NOT fully applied: {}",
                    configSync.configRevision(), configMessage);
        }
        Map<String, Object> ack = new java.util.LinkedHashMap<>();
        ack.put("configRevision", configSync.configRevision());
        ack.put("configStatus", status);
        ack.put("configMessage", configMessage);
        sendAckPayload(ack, "config rev " + configSync.configRevision() + " (" + status + ")");

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
        shutdownMetricsScheduler();
        shutdownActivityScheduler();
        // Mất kết nối khi đang stream: dừng chụp luôn. Nếu để scheduler sống,
        // sau khi reconnect client sẽ gửi STREAM_FRAME tiếp dù server đã đóng
        // dialog / không còn ai xem (dialog không gửi được STOP khi session null).
        stopScreenCaptureScheduler();
        if (screenCaptureService != null) {
            screenCaptureService.stop();
        }
        // Mục 3.11: mất kết nối → thu hồi phiên điều khiển + nhả phím/nút.
        remoteInputController.stopForConnectionLoss();
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
