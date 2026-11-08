package com.parental.client.app;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

import javax.swing.SwingUtilities;

import com.parental.client.activity.ActivityCollector;
import com.parental.client.activity.LinuxActivityCollector;
import com.parental.client.activity.WindowsActivityCollector;
import com.parental.client.config.ClientConfig;
import com.parental.client.device.DefaultDeviceInfoCollector;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.client.enforcement.ActionDispatcher;
import com.parental.client.enforcement.HostsFileManager;
import com.parental.client.enforcement.SystemTrayAlertNotifier;
import com.parental.client.enforcement.WindowsNetshEnforcer;
import com.parental.client.identity.ClientIdentityManager;
import com.parental.client.identity.ClientIdentityManager.ClientIdentity;
import com.parental.client.identity.ClientPairingFrame;
import com.parental.client.metrics.NetworkMetricsCollector;
import com.parental.client.metrics.OshiNetworkMetricsCollector;
import com.parental.client.metrics.ProcNetMetricsCollector;
import com.parental.client.network.ServerCommandHandler;
import com.parental.client.network.TcpClientEngine;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.ui.DesktopTheme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Luồng khởi động Client (Client Identity Persistence + Zero-Config Setup):
 * <ol>
 *   <li>Đọc {@code client_identity.json} qua {@link ClientIdentityManager} —
 *       {@code clientId} sinh UUID MỘT LẦN DUY NHẤT và lưu bền vững (không còn
 *       nhân bản thiết bị Offline trên Dashboard mỗi lần bật lại).</li>
 *   <li>Đã có {@code deviceToken} → dựng {@link ClientConfig} (clientId + token
 *       bền vững) và chạy ngầm {@link TcpClientEngine} ngay: HANDSHAKE kèm
 *       token được server xác thực.</li>
 *   <li>CHƯA có token → mở {@link ClientPairingFrame}: nhập IP máy chủ + mã PIN
 *       6 số → PAIR_REQUEST → PAIR_RESPONSE thành công → lưu token xuống file
 *       → đóng frame → chuyển sang chạy ngầm (bước 2).</li>
 * </ol>
 *
 * <p>Headless (không có màn hình/Swing) mà chưa ghép đôi → exit mã 2 kèm
 * hướng dẫn.</p>
 */
public class ClientApp {

    private static final Logger log = LoggerFactory.getLogger(ClientApp.class);

    /** Exit code khi chạy headless mà thiết bị chưa ghép đôi. */
    static final int EXIT_NOT_PAIRED_HEADLESS = 2;

    /**
     * Có đang chạy trên Linux không (theo {@code os.name}) — quyết định chọn
     * collector metrics/activity bản Linux ({@code /proc/net/dev} + xdotool)
     * hay bản Windows (OSHI + PowerShell UIA).
     */
    static boolean isLinux() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return osName.contains("linux");
    }

    public static void main(String[] args) {
        log.info("Starting Parental Control Client Agent...");
        if (!isHeadless()) {
            try {
                SwingUtilities.invokeAndWait(DesktopTheme::install);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while installing desktop theme", e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Could not install desktop theme", e.getCause());
            }
        }

        // Args tùy chọn vẫn được ưu tiên (khởi chạy thủ công), nhưng khi thiếu
        // thì dùng serverHost/serverPort ghi nhớ trong client_identity.json.
        String argHost = args.length > 0 ? args[0] : null;
        int argPort = args.length > 1 ? parsePort(args[1]) : -1;

        ClientIdentityManager identityManager = new ClientIdentityManager(
                Path.of(ClientIdentityManager.DEFAULT_IDENTITY_FILE));
        ClientIdentity identity = identityManager.loadOrCreate();
        log.info("Persistent identity: clientId={} (paired={})",
                identity.clientId(), identity.deviceToken() != null);

        // ƯU TIÊN NGUỒN serverHost/serverPort:
        //   1. Args dòng lệnh: java -jar client.jar [serverHost] [serverPort]
        //   2. serverHost/serverPort GHI NHỚ trong client_identity.json — chính là
        //      IP người dùng đã nhập trên màn hình ghép đôi (ClientPairingFrame).
        //   3. Mặc định 127.0.0.1:8888 (chạy cùng máy với server).
        String host = argHost != null ? argHost
                : identity.serverHost() != null && !identity.serverHost().isBlank()
                ? identity.serverHost()
                : ClientConfig.DEFAULT_SERVER_HOST;
        int port = argPort > 0 ? argPort
                : identity.serverPort() > 0 ? identity.serverPort()
                : ClientConfig.DEFAULT_SERVER_PORT;
        log.info("Server address resolved: host={} port={} (source: {})", host, port,
                argHost != null ? "command-line args"
                : identity.serverHost() != null && !identity.serverHost().isBlank()
                        ? "client_identity.json (saved from pairing UI)"
                        : "default (" + ClientConfig.DEFAULT_SERVER_HOST + ")");

        if (identity.deviceToken() == null) {
            // Chưa ghép đôi → giao diện ghép đôi (GUI) hoặc thoát có hướng dẫn (headless).
            if (isHeadless()) {
                log.error("Device is not paired yet and no display is available. "
                        + "Run the pairing UI on a machine with a display, then start again.");
                System.exit(EXIT_NOT_PAIRED_HEADLESS);
            }
            startWithPairingUi(identityManager, identity, host, port);
        } else {
            startBackgroundEngine(identityManager, identity, host, port);
        }
    }

    /** Đã ghép đôi: dựng config bền vững và chạy engine ngầm. */
    private static void startBackgroundEngine(ClientIdentityManager identityManager,
                                              ClientIdentity identity,
                                              String host, int port) {
        ClientConfig config = ClientConfig.builder()
                .serverHost(host)
                .serverPort(port)
                .clientId(identity.clientId())       // ĐỊNH DANH BỀN VỮNG — không sinh mới
                .deviceToken(identity.deviceToken()) // đính vào HANDSHAKE
                .heartbeatIntervalMs(5000)
                .metricsIntervalMs(5000)
                .build();

        // Watchdog chặn app (Mở rộng 12) tạo TRƯỚC engine để shutdown hook dừng
        // được (không còn kill khi app client đã tắt)
        com.parental.client.enforcement.ProcessBlockerManager processBlocker =
                new com.parental.client.enforcement.ProcessBlockerManager();
        processBlocker.startRescanScheduler();

        // Bug B: dọn firewall rule SÓT từ lần chạy trước bị kill cứng — chỉ đụng
        // đúng 3 tên rule của project, TRƯỚC khi engine kết nối/áp chính sách.
        // Thiếu quyền Administrator chỉ log, không chặn Client khởi động.
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer();
        enforcer.cleanupStaleRulesOnStartup();

        TcpClientEngine clientEngine = buildEngine(config, identityManager, () ->
                SwingUtilities.invokeLater(() -> showPairingUiAfterUnpair(identityManager)),
                processBlocker, enforcer);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down client agent...");
            processBlocker.stopWatchdog(); // dừng watchdog quét kill
            clientEngine.stop();
        }, "client-shutdown-hook"));

        clientEngine.start();
        log.info("Parental Control Client is running in background. Connecting to {}:{} (clientId={})",
                host, port, identity.clientId());

        keepMainThreadAlive();
    }

    /**
     * Bị server thu hồi ghép đôi (UNPAIR) — hiện lại màn hình ghép đôi lần
     * đầu (người dùng nhập lại IP + PIN mới từ phụ huynh). Gọi trên EDT.
     *
     * <p>Host/port hiển thị sẵn và khởi động engine sau ghép đôi ĐỌC LẠI từ
     * {@code client_identity.json} — người dùng có thể nhập IP khác lần này;
     * KHÔNG còn dùng closure host/port cũ (nguồn lỗi kết nối 127.0.0.1).</p>
     */
    private static void showPairingUiAfterUnpair(ClientIdentityManager identityManager) {
        log.warn("Device was unpaired by server — showing pairing UI again");
        DeviceInfoCollector collector = new DefaultDeviceInfoCollector();
        identityManager.loadOrCreate();
        ClientIdentity saved = identityManager.load();
        String defaultHost = resolveHost(saved);
        int defaultPort = resolvePort(saved);
        ClientPairingFrame frame = new ClientPairingFrame(
                identityManager, collector, defaultHost,
                () -> {
                    // Được gọi trên thread nền sau khi token mới đã lưu (hotfix EDT).
                    // Host/port đọc LẠI identity — dùng đúng IP người dùng vừa nhập.
                    ClientIdentity paired = identityManager.load();
                    if (paired == null || paired.deviceToken() == null) {
                        log.error("Re-paired but token missing — exiting");
                        System.exit(1);
                    }
                    startBackgroundEngine(identityManager, paired,
                            resolveHost(paired), resolvePort(paired));
                });
        frame.setVisible(true);
    }

    /** Chưa ghép đôi: hiện giao diện ghép đôi; thành công → chuyển sang chạy ngầm. */
    private static void startWithPairingUi(ClientIdentityManager identityManager,
                                           ClientIdentity identity,
                                           String host, int port) {
        DeviceInfoCollector deviceInfoCollector = new DefaultDeviceInfoCollector();

        SwingUtilities.invokeLater(() -> {
            ClientPairingFrame frame = new ClientPairingFrame(
                    identityManager, deviceInfoCollector, host,
                    () -> {
                        // Contract (hotfix deadlock EDT): callback CHẠY TRÊN THREAD NỀN
                        // ("parental-client-engine-bootstrap"), KHÔNG BAO GIỜ trên EDT —
                        // hàm này kết thúc bằng keepMainThreadAlive() (park vô hạn) và
                        // nếu chạy trên EDT sẽ block toàn bộ UI vĩnh viễn.
                        log.info("Pairing completed — switching to background engine");
                        // Host/port PHẢI đọc lại identity: handlePairingResult vừa lưu
                        // IP/port người dùng nhập trên ClientPairingFrame — nếu dùng
                        // closure cũ (pre-fill 127.0.0.1) engine sẽ kết nối SAI server.
                        ClientIdentity paired = identityManager.load();
                        if (paired == null || paired.deviceToken() == null) {
                            log.error("Paired but token missing from identity file — aborting");
                            System.exit(1);
                        }
                        startBackgroundEngine(identityManager, paired,
                                resolveHost(paired), resolvePort(paired));
                    });
            frame.setVisible(true);
        });

        // Main thread chờ vô hạn — Swing có EDT riêng; shutdown hook dọn dẹp.
        keepMainThreadAlive();
    }

    /**
     * Dựng TcpClientEngine đầy đủ 11 tham số: kèm {@link ClientIdentityManager}
     * + listener UNPAIR (thiết bị bị xóa → khôi phục mạng, xóa identity,
     * quay về màn hình ghép đôi qua EDT) + {@link WindowsActivityCollector}
     * (Activity Monitoring — gửi ACTIVITY_REPORT mỗi 10s) +
     * {@code ProcessBlockerManager} (Mở rộng 10/12 — watchdog 2s).
     */
    private static TcpClientEngine buildEngine(ClientConfig config,
                                               ClientIdentityManager identityManager,
                                               Runnable unpairListener,
                                               com.parental.client.enforcement.ProcessBlockerManager processBlocker,
                                               WindowsNetshEnforcer enforcer) {
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier();
        // Chọn collector THEO HỆ ĐIỀU HÀNH (Linux chạy được cả khi OSHI JNI lỗi):
        //   - Linux   → /proc/net/dev (bỏ lo, delta mỗi chu kỳ) + xdotool/fallback ps.
        //   - Windows → OSHI NIC counters + PowerShell UIA (hành vi cũ giữ nguyên).
        NetworkMetricsCollector metricsCollector = isLinux()
                ? new ProcNetMetricsCollector()
                : new OshiNetworkMetricsCollector();
        ActivityCollector activityCollector = isLinux()
                ? new LinuxActivityCollector()
                : new WindowsActivityCollector();
        // Vô hiệu hóa DoH/QUIC của trình duyệt — hosts enforcement có hiệu lực thật:
        // mỗi lần áp/clear danh sách chặn, khóa/mở đường lách (DoH registry + QUIC UDP 443)
        // rồi flush DNS/NetBIOS cache để kết nối MỚI giải mã lại qua hosts.
        com.parental.client.enforcement.BrowserBypassEnforcer bypassEnforcer =
                new com.parental.client.enforcement.BrowserBypassEnforcer();
        // Constructor có enforcer: apply/clear tự khóa/mở DoH + QUIC +
        // URLBlocklist và trả kết quả thật (không còn anonymous override bỏ
        // qua kết quả khóa bypass).
        HostsFileManager hostsFileManager = new HostsFileManager(
                HostsFileManager.defaultHostsPath(), bypassEnforcer);
        if (!hostsFileManager.canWrite()) {
            log.error("Client KHÔNG có quyền ghi hosts ({}) — chạy Client bằng 'Run as Administrator'; "
                    + "nếu không, chặn domain sẽ chỉ báo PARTIAL và không có hiệu lực.",
                    hostsFileManager.getHostsFilePath());
        }
        ActionDispatcher actionDispatcher = new ActionDispatcher(
                enforcer, notifier, metricsCollector, processBlocker,
                // Tính năng 3.4: allowlist LOCK/LOGOFF/RESTART/SHUTDOWN — không
                // nhận shell/command tùy ý; lệnh từ Server phải qua xác nhận GUI.
                new com.parental.client.power.WindowsPowerCommandExecutor());

        return new TcpClientEngine(
                config,
                new DefaultDeviceInfoCollector(),
                new JsonProtocolParser(),
                new ServerCommandHandler() { }, // listener mặc định: chỉ log
                metricsCollector,
                hostsFileManager,
                actionDispatcher,
                identityManager,
                unpairListener,
                activityCollector, // Linux: xdotool/ps — Windows: PowerShell UIA (Mục 3.9)
                processBlocker // Mở rộng 11: đồng bộ blockedProcesses qua CONFIG_SYNC
        );
    }

    /** True khi JVM không có môi trường đồ họa (Swing không dùng được). */
    private static boolean isHeadless() {
        try {
            return java.awt.GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("Invalid port argument '{}', falling back to saved/default port", raw);
            return -1;
        }
    }

    /** Host kết nối: giá trị đã lưu (IP nhập lúc ghép đôi) → mặc định 127.0.0.1. */
    static String resolveHost(ClientIdentity identity) {
        if (identity != null && identity.serverHost() != null
                && !identity.serverHost().isBlank()) {
            return identity.serverHost();
        }
        return ClientConfig.DEFAULT_SERVER_HOST;
    }

    /** Port kết nối: giá trị đã lưu (> 0) → mặc định 8888. */
    static int resolvePort(ClientIdentity identity) {
        if (identity != null && identity.serverPort() > 0) {
            return identity.serverPort();
        }
        return ClientConfig.DEFAULT_SERVER_PORT;
    }

    /** Giữ luồng main không thoát (engine/Swing chạy thread riêng). */
    private static void keepMainThreadAlive() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
