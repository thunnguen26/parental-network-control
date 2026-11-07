package com.parental.client.enforcement;

import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triển khai {@link AlertNotifier} (Task 3.3) với chuỗi fallback:
 * <ol>
 *   <li>{@link SystemTray} + {@link TrayIcon#displayMessage} nếu máy có tray —
 *       dạng balloon notification (không cướp focus);</li>
 *   <li><b>Popup JDialog ALWAYS-ON-TOP trên EDT</b> (hotfix "popup ẩn ngầm sau
 *       cửa sổ khác"): dialog {@code setAlwaysOnTop(true)} + giữ cửa sổ bật
 *       bằng {@code toFront()/requestFocus()} trên EDT, tự đóng sau 15s;</li>
 *   <li>log WARN (headless / không có quyền tray).</li>
 * </ol>
 *
 * <p><b>Hotfix hiển thị:</b> mọi thao tác Swing CHỈ chạy trên Event Dispatch
 * Thread qua {@code SwingUtilities.invokeLater(...)} (trước đây popup được tạo
 * từ thread {@code parental-client-alert} — vi phạm EDT, không có focus và bị
 * chôn dưới các cửa sổ khác). Thread gọi notify() (socket reader) vẫn không
 * bao giờ bị block — chỉ xếp hàng chạy trên EDT.</p>
 *
 * <p>Không bao giờ ném exception ra ngoài (AGENTS.md Fail-Safe).</p>
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
        // Chạy trên thread riêng — socket reader không bị nghẽn; hiển thị Swing
        // được đẩy tiếp lên EDT bên trong showSafely()
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
            // Fallback: popup JDialog ALWAYS-ON-TOP trên EDT — hotfix "popup ẩn
            // ngầm sau cửa sổ khác" (yêu cầu task)
            SwingUtilities.invokeLater(() -> showAlwaysOnTopPopup(message));
        } catch (java.awt.AWTException | RuntimeException e) {
            log.warn("GUI alert failed ({}), falling back to log: {}", e.getClass().getSimpleName(), message);
        }
    }

    /**
     * Popup cảnh báo từ phụ huynh — CHẠY TRÊN EDT (được invoke từ
     * {@link #showSafely} qua {@code SwingUtilities.invokeLater}):
     * <ul>
     *   <li>{@code setAlwaysOnTop(true)} — luôn nổi trên mọi cửa sổ;</li>
     *   <li>{@code toFront()} + {@code requestFocus()} — ép focus lên màn hình;</li>
     *   <li>tự đóng sau 15s (Swing Timer) — dispose ĐÚNG dialog này, không
     *       đụng cửa sổ khác của app (fix hành vi cũ dispose TOÀN BỘ windows).</li>
     * </ul>
     */
    private void showAlwaysOnTopPopup(String message) {
        try {
            String text = message != null && !message.isBlank()
                    ? message
                    : "Bạn nhận được một thông báo từ phụ huynh!";
            // Dialog vô hình chỉ làm parent — JOptionPane trả modal dialog con
            JDialog dialog = new JDialog();
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setModal(false);
            dialog.setTitle("Cảnh báo từ phụ huynh");

            // Tự động đóng sau 15s nếu người dùng không bấm — chỉ dispose
            // ĐÚNG dialog này (không đụng window khác của app)
            Timer autoClose = new Timer(POPUP_AUTO_CLOSE_MS, event -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window == dialog || window.getOwner() == dialog) {
                        window.dispose();
                    }
                    // JOptionPane modal tạo dialog con của dialog — dispose cùng
                    if (window instanceof JDialog jd && jd.getParent() != null
                            && jd.getParent() == dialog) {
                        window.dispose();
                    }
                }
            });
            autoClose.setRepeats(false);
            autoClose.start();

            dialog.setVisible(true);
            dialog.toFront();
            dialog.requestFocus();

            JOptionPane.showMessageDialog(
                    dialog,
                    text,
                    "Cảnh báo từ phụ huynh",
                    JOptionPane.WARNING_MESSAGE
            );
            dialog.dispose();
        } catch (RuntimeException e) {
            log.warn("Popup alert failed: {}", e.getMessage());
        }
    }
}
