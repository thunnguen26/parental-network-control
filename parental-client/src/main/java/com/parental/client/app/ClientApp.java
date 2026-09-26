package com.parental.client.app;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DefaultDeviceInfoCollector;
import com.parental.client.enforcement.ActionDispatcher;
import com.parental.client.enforcement.HostsFileManager;
import com.parental.client.enforcement.SystemTrayAlertNotifier;
import com.parental.client.enforcement.WindowsNetshEnforcer;
import com.parental.client.metrics.OshiNetworkMetricsCollector;
import com.parental.client.network.ServerCommandHandler;
import com.parental.client.network.TcpClientEngine;
import com.parental.common.protocol.JsonProtocolParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientApp {
    private static final Logger log = LoggerFactory.getLogger(ClientApp.class);

    public static void main(String[] args) {
        log.info("Starting Parental Control Client Agent...");

        // 1. Cấu hình kết nối tới Server (mặc định localhost:8888)
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8888;

        // Builder chỉ tạo được qua factory ClientConfig.builder() (ctor private)
        ClientConfig config = ClientConfig.builder()
                .serverHost(host)
                .serverPort(port)
                .heartbeatIntervalMs(5000)
                .metricsIntervalMs(5000)
                .build();

        // 2. Khởi tạo các module thực thi (Enforcement & Metrics)
        WindowsNetshEnforcer enforcer = new WindowsNetshEnforcer();
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier();
        OshiNetworkMetricsCollector metricsCollector = new OshiNetworkMetricsCollector();
        HostsFileManager hostsFileManager = new HostsFileManager();

        ActionDispatcher actionDispatcher = new ActionDispatcher(enforcer, notifier, metricsCollector);

        // 3. Khởi tạo TCP Client Engine — dùng constructor ĐẦY ĐỦ 7 tham số:
        // (config, deviceInfoCollector, parser, commandHandler, metricsCollector,
        //  hostsFileManager, actionDispatcher). Parser/commandHandler không truyền
        // thì dùng mặc định qua ctor 5 tham số KHÔNG TỒN TẠI trong TcpClientEngine.
        TcpClientEngine clientEngine = new TcpClientEngine(
                config,
                new DefaultDeviceInfoCollector(),
                new JsonProtocolParser(),
                new ServerCommandHandler() { }, // listener mặc định: chỉ log
                metricsCollector,
                hostsFileManager,
                actionDispatcher
        );

        // 4. Bắt sự kiện tắt ứng dụng (Ctrl+C hoặc đóng tiến trình) để dọn dẹp sạch sẽ
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down client agent...");
            clientEngine.stop();
        }, "client-shutdown-hook"));

        // 5. Khởi động kết nối
        clientEngine.start();
        log.info("Parental Control Client is running in background. Connected/connecting to {}:{}", host, port);

        // Giữ luồng main không bị thoát
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}