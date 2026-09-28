package com.parental.client.app;

import java.nio.file.Path;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.parental.client.activity.WindowsActivityCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.parental.client.metrics.OshiNetworkMetricsCollector;
import com.parental.client.network.ServerCommandHandler;
import com.parental.client.network.TcpClientEngine;
import com.parental.common.protocol.JsonProtocolParser;

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

    public static void main(String[] args) {
        log.info("Starting Parental Control Client Agent...");

        // Args tùy chọn vẫn được ưu tiên (khởi chạy thủ công), nhưng khi thiếu
        // thì dùng serverHost/serverPort ghi nhớ trong client_identity.json.
        String argHost = args.length > 0 ? args[0] : null;
        int argPort = args.length > 1 ? parsePort(args[1]) : -1;

        ClientIdentityManager identityManager = new ClientIdentityManager(
                Path.of(ClientIdentityManager.DEFAULT_IDENTITY_FILE));
        ClientIdentity identity = identityManager.loadOrCreate();
        log.info("Persistent identity: clientId={} (paired={})",
                identity.clientId(), identity.deviceToken() != null);

        String host = argHost != null ? argHost
                : identity.serverHost() != null ? identity.serverHost()
                : "127.0.0.1";
        int port = argPort > 0 ? argPort
                : identity.serverPort() > 0 ? identity.serverPort()
                : 8888;

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

        TcpClientEngine clientEngine = buildEngine(config, identityManager, () ->
                SwingUtilities.invokeLater(() -> showPairingUiAfterUnpair(identityManager, host, port)));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down client agent...");
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
     */
    private static void showPairingUiAfterUnpair(ClientIdentityManager identityManager,
                                                 String host, int port) {
        log.warn("Device was unpaired by server — showing pairing UI again");
        DeviceInfoCollector collector = new DefaultDeviceInfoCollector();
        ClientIdentity fresh = identityManager.loadOrCreate();
        ClientPairingFrame frame = new ClientPairingFrame(
                identityManager, collector, host,
                () -> {
                    // Được gọi trên thread nền sau khi token mới đã lưu (hotfix EDT).
                    ClientIdentity paired = identityManager.load();
                    if (paired == null || paired.deviceToken() == null) {
                        log.error("Re-paired but token missing — exiting");
                        System.exit(1);
                    }
                    startBackgroundEngine(identityManager, paired, host, port);
                });
        frame.setVisible(true);
    }

    /** Chưa ghép đôi: hiện giao diện ghép đôi; thành công → chuyển sang chạy ngầm. */
    private static void startWithPairingUi(ClientIdentityManager identityManager,
                                           ClientIdentity identity,
                                           String host, int port) {
        DeviceInfoCollector deviceInfoCollector = new DefaultDeviceInfoCollector();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                log.debug("System look-and-feel unavailable: {}", e.getMessage());
            }
            ClientPairingFrame frame = new ClientPairingFrame(
                    identityManager, deviceInfoCollector, host,
                    () -> {
                        // Contract (hotfix deadlock EDT): callback CHẠY TRÊN THREAD NỀN
                        // ("parental-client-engine-bootstrap"), KHÔNG BAO GIỜ trên EDT —
                        // hàm này kết thúc bằng keepMainThreadAlive() (park vô hạn) và
                        // nếu chạy trên EDT sẽ block toàn bộ UI vĩnh viễn.
                        log.info("Pairing completed — switching to background engine");
                        ClientIdentity paired = identityManager.load();
                        if (paired == null || paired.deviceToken() == null) {
                            log.error("Paired but token missing from identity file — aborting");
                            System.exit(1);
                        }
                        startBackgroundEngine(identityManager, paired, host, port);
                    });
            frame.setVisible(true);
        });

        // Main thread chờ vô hạn — Swing có EDT riêng; shutdown hook dọn dẹp.
        keepMainThreadAlive();
    }

    /**
     * Dựng TcpClientEngine đầy đủ 10 tham số: kèm {@link ClientIdentityManager}
     * + listener UNPAIR (thiết bị bị xóa → khôi phục mạng, xóa identity,
     * quay về màn hình ghép đôi qua EDT) + {@link WindowsActivityCollector}
     * (Activity Monitoring — gửi ACTIVITY_REPORT mỗi 10s).
     */
    private static TcpClientEngine buildEngine(ClientConfig config,
                                               ClientIdentityManager identityManager,
                                               Runnable unpairListener) {
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer();
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier();
        OshiNetworkMetricsCollector metricsCollector = new OshiNetworkMetricsCollector();
        HostsFileManager hostsFileManager = new HostsFileManager();
        ActionDispatcher actionDispatcher = new ActionDispatcher(enforcer, notifier, metricsCollector);

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
                new com.parental.client.activity.WindowsActivityCollector() // Activity Monitoring
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

    /** Giữ luồng main không thoát (engine/Swing chạy thread riêng). */
    private static void keepMainThreadAlive() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
