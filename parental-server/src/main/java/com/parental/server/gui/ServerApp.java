package com.parental.server.gui;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatLightLaf;
import com.parental.server.db.DatabaseManager;
import com.parental.server.network.TcpServerEngine;

/**
 * Lớp chạy ứng dụng Server chính (Task 5.1): FlatLaf Light theme → khởi tạo
 * DatabaseManager (SQLite file {@code parental_control.db}) → khởi động
 * {@link TcpServerEngine} (mặc định port 8888, cấu hình qua {@code args[0]})
 * → mở {@link ServerDashboardFrame}.
 *
 * <p>Đóng cửa sổ = graceful shutdown: dừng UI refresher → dừng Rule Engine +
 * TCP Engine (giải phóng port, scheduler, rule scheduler) → dispose frame →
 * {@code System.exit(0)}. Quy tắc Swing: mọi thao tác UI chạy trên EDT.</p>
 */
public final class ServerApp {

    private static final Logger log = LoggerFactory.getLogger(ServerApp.class);

    /** Port lắng nghe mặc định (cấu hình được qua args[0] tùy chọn). */
    static final int DEFAULT_PORT = 8888;

    private ServerApp() {
        // main class — không instantiable
    }

    public static void main(String[] args) {
        int port = parsePort(args);

        // Theme hiện đại FlatLaf Light — đặt TRƯỚC khi tạo Swing component nào
        FlatLightLaf.setup();

        SwingUtilities.invokeLater(() -> startServerAndShowDashboard(port));
    }

    /** args[0] là port tùy chọn; không hợp lệ → fallback mặc định. */
    static int parsePort(String[] args) {
        if (args != null && args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("Invalid port argument '{}', using default {}",
                        args[0], DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    private static void startServerAndShowDashboard(int port) {
        try {
            // DatabaseManager file DB runtime (Task 4.1 — migration idempotent).
            // PHẢI truyền DB vào engine (ctor 2 tham số) — nếu không, toàn bộ
            // DAO (ClientDao/MetricsDao/RuleDao/...) trả về null và Dashboard
            // văng NullPointerException khi load danh sách thiết bị.
            DatabaseManager database = new DatabaseManager(Path.of("parental_control.db"));
            TcpServerEngine engine = new TcpServerEngine(port, database);
            engine.start();
            log.info("Server started on port {} (rule engine interval 30s)", engine.getPort());

            ServerDashboardFrame dashboard = new ServerDashboardFrame(engine,
                    engine.getClientDao(), engine.getMetricsDao(), engine.getRuleDao(),
                    engine.getSessionManager(), engine.getRuleEvaluationEngine());
            dashboard.showFrame();

            registerWindowShutdown(dashboard, engine);
        } catch (Exception e) {
            log.error("Failed to start server on port {}: {}", port, e.getMessage(), e);
            JOptionPane.showMessageDialog(null,
                    "Không thể khởi động server trên port " + port + "\n(" + e.getMessage() + ")",
                    "Lỗi khởi động", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * Đóng cửa sổ → graceful shutdown engine (giải phóng port + toàn bộ
     * scheduler) rồi mới thoát JVM. {@code DO_NOTHING_ON_CLOSE} đã được đặt
     * trong {@link ServerDashboardFrame#initUi()}.
     */
    private static void registerWindowShutdown(ServerDashboardFrame dashboard, TcpServerEngine engine) {
        dashboard.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dashboard.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                try {
                    dashboard.stopRefresher();
                    engine.stop(); // dừng rule engine + sweeper + accept loop + đóng session
                } catch (RuntimeException e) {
                    log.error("Graceful shutdown failed: {}", e.getMessage(), e);
                } finally {
                    dashboard.dispose();
                    System.exit(0);
                }
            }
        });
    }
}
