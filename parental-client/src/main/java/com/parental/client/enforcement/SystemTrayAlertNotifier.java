package com.parental.client.enforcement;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triển khai {@link AlertNotifier} (Task 3.3) với chuỗi fallback:
 * <ol>
 *   <li>{@link SystemTray} + {@link TrayIcon#displayMessage} nếu máy có tray;</li>
 *   <li>{@link JOptionPane#showMessageDialog} popup tự đóng sau 15s (GUI có
 *       sẵn — dùng Timer để không treo chương trình khi không ai bấm);</li>
 *   <li>log WARN (headless / không có quyền tray).</li>
 * </ol>
 *
 * <p>Mọi popup chạy trên thread daemon riêng ({@code parental-client-alert})
 * — <b>không bao giờ nghẽn socket reader thread</b>; không bao giờ ném
 * exception ra ngoài.</p>
 */
public class SystemTrayAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SystemTrayAlertNotifier.class);
    private static final int POPUP_AUTO_CLOSE_MS = 15_000;

    private final ExecutorService alertExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "parental-client-alert");
                thread.setDaemon(true);
                return thread;
            });
    private final boolean headless;

    public SystemTrayAlertNotifier() {
        this(GraphicsEnvironment.isHeadless());
    }

    /** Cho test inject headless flag. */
    SystemTrayAlertNotifier(boolean headless) {
        this.headless = headless;
    }

    @Override
    public boolean notify(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (headless) {
            log.warn("[ALERT] {}", message);
            return false;
        }
        // Chạy trên thread riêng — socket reader không bị nghẽn
        alertExecutor.execute(() -> showSafely(message));
        return true; // đã xếp hàng hiển thị
    }

    private void showSafely(String message) {
        try {
            if (SystemTray.isSupported()) {
                // (AWTException từ tray.add() được bắt ở catch bên dưới)
                SystemTray tray = SystemTray.getSystemTray();
                TrayIcon trayIcon = new TrayIcon(
                        Toolkit.getDefaultToolkit().getImage("parental-alert-icon"));
                trayIcon.setImageAutoSize(true);
                tray.add(trayIcon);
                trayIcon.displayMessage("Parental Control", message, TrayIcon.MessageType.WARNING);
                // Tray icon tạm: gỡ sau 15s để không rác system tray
                Timer cleanupTimer = new Timer(POPUP_AUTO_CLOSE_MS, event -> {
                    try {
                        tray.remove(trayIcon);
                    } catch (RuntimeException ignored) {
                        // tray đã bị ứng dụng khác thay đổi — bỏ qua
                    }
                });
                cleanupTimer.setRepeats(false);
                cleanupTimer.start();
                return;
            }
            // Fallback: JOptionPane popup tự đóng sau 15s (chạy trên EDT)
            SwingUtilities.invokeLater(() -> showAutoClosingPopup(message));
        } catch (java.awt.AWTException | RuntimeException e) {
            log.warn("GUI alert failed ({}), falling back to log: {}", e.getClass().getSimpleName(), message);
        }
    }

    private void showAutoClosingPopup(String message) {
        try {
            Timer timer = new Timer(POPUP_AUTO_CLOSE_MS, event -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    window.dispose();
                }
            });
            timer.setRepeats(false);
            timer.start();
            JOptionPane.showMessageDialog(null, message, "Parental Control Alert",
                    JOptionPane.WARNING_MESSAGE);
            timer.stop();
        } catch (RuntimeException e) {
            log.warn("Popup alert failed: {}", e.getMessage());
        }
    }
}
