package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.Instant;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.server.db.PairingDao;

/**
 * Dialog ghép đôi thiết bị mới (Server GUI — Device Pairing): hiển thị mã
 * PIN 6 chữ số cỡ chữ to, thanh đếm ngược hiệu lực 5 phút, nút Đổi mã PIN,
 * và TỰ ĐỘNG ĐÓNG khi một client ghép đôi thành công với mã hiện hành.
 *
 * <p><b>Threading</b>: countdown dùng {@code javax.swing.Timer} (bắn trên
 * EDT); phát hiện ghép đôi thành công dùng một daemon thread poll
 * {@link PairingDao#getPinState} mỗi 500ms — thấy {@code USED} →
 * {@code invokeLater} đóng dialog + gọi callback thông báo.</p>
 *
 * <p>Dialog là modal nhưng việc đóng/tự động đóng xảy ra trên EDT an toàn;
 * poll thread daemon nên không cản trở JVM shutdown.</p>
 */
public class PairingDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(PairingDialog.class);

    /** Chu kỳ cập nhật đồng hồ đếm ngược (ms). */
    static final int COUNTDOWN_TICK_MS = 1_000;
    /** Chu kỳ poll DB phát hiện client đã ghép đôi (ms). */
    static final int PAIRED_POLL_MS = 500;

    private static final Font PIN_FONT = new Font(Font.MONOSPACED, Font.BOLD, 48);

    private final PairingDao pairingDao;
    private final Runnable onPairedCallback;

    private final JLabel pinLabel = new JLabel("—");
    private final JLabel countdownLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton regenerateButton = new JButton("\uD83D\uDD01 \u0110\u1ED5i m\u00E3 PIN");

    /** Mã PIN hiện hành đang hiển thị. */
    private volatile String currentPin;
    /** Thời điểm hết hạn của PIN hiện hành. */
    private volatile Instant currentExpiry = Instant.now();

    private javax.swing.Timer countdownTimer;
    private Thread pairedWatcher;

    /**
     * @param owner             frame cha (dashboard)
     * @param pairingDao        DAO sinh/kiểm tra PIN (không null)
     * @param onPairedCallback  gọi trên EDT khi client ghép đôi thành công
     *                          (có thể null)
     */
    public PairingDialog(JFrame owner, PairingDao pairingDao, Runnable onPairedCallback) {
        super(owner, "Ghép đôi thiết bị mới", true);
        this.pairingDao = pairingDao;
        this.onPairedCallback = onPairedCallback;

        initUi();
        createPinAndShow(); // sinh PIN đầu tiên ngay khi mở dialog
    }

    private void initUi() {
        setLayout(new BorderLayout(0, 10));

        // --- Khối PIN lớn giữa dialog ---
        JPanel pinPanel = new JPanel(new BorderLayout(0, 4));
        pinPanel.setBorder(BorderFactory.createEmptyBorder(18, 24, 8, 24));
        JLabel instructionLabel = new JLabel(
                "Nhập mã này trên máy con để ghép đôi:", SwingConstants.CENTER);
        pinLabel.setFont(PIN_FONT);
        pinLabel.setHorizontalAlignment(SwingConstants.CENTER);
        pinPanel.add(instructionLabel, BorderLayout.NORTH);
        pinPanel.add(pinLabel, BorderLayout.CENTER);

        // --- Thanh đếm ngược ---
        countdownLabel.setHorizontalAlignment(SwingConstants.CENTER);
        countdownLabel.setFont(countdownLabel.getFont().deriveFont(Font.BOLD, 13f));

        // --- Nút đổi PIN ---
        JPanel buttonPanel = new JPanel();
        regenerateButton.addActionListener(event -> createPinAndShow());
        buttonPanel.add(regenerateButton);

        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(countdownLabel, BorderLayout.NORTH);
        south.add(buttonPanel, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);

        add(pinPanel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        // Đóng dialog = hủy watcher (PIN không bị tiêu thụ — chỉ hết hạn tự nhiên).
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopWatchers();
            }
        });

        setSize(420, 300);
        setLocationRelativeTo(getOwner());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /** Sinh mã PIN mới qua {@link PairingDao#createPin()} và hiển thị + reset đếm ngược. */
    private void createPinAndShow() {
        stopWatchers();
        String pin = pairingDao.createPin();
        if (pin == null) {
            pinLabel.setText("Lỗi");
            countdownLabel.setText("Không sinh được mã PIN (lỗi cơ sở dữ liệu)");
            return;
        }
        currentPin = pin;
        com.parental.server.db.PairingDao.PairingSession session = pairingDao.get(pin);
        currentExpiry = session != null && session.expiresAt() != null
                ? session.expiresAt()
                : Instant.now().plusSeconds(PairingDao.PIN_VALIDITY_SECONDS);

        pinLabel.setText(formatGroupedPin(pin));
        statusLabel.setText(" ");
        startCountdown();
        startPairedWatcher();
        log.info("New pairing PIN generated (***{})", pin.substring(4));
    }

    /** Định dạng PIN thành nhóm 3-3 cho dễ đọc (vd "482 913"). */
    static String formatGroupedPin(String pin) {
        return pin != null && pin.length() == 6
                ? pin.substring(0, 3) + " " + pin.substring(3)
                : String.valueOf(pin);
    }

    /** javax.swing.Timer trên EDT cập nhật đếm ngược mỗi giây. */
    private void startCountdown() {
        if (countdownTimer != null) {
            countdownTimer.stop();
        }
        countdownTimer = new javax.swing.Timer(COUNTDOWN_TICK_MS, event -> updateCountdownLabel());
        countdownTimer.start();
        updateCountdownLabel();
    }

    private void updateCountdownLabel() {
        long remainingMs = Duration.between(Instant.now(), currentExpiry).toMillis();
        if (remainingMs <= 0) {
            countdownLabel.setText("Mã đã hết hạn — bấm Đổi mã PIN để tạo mã mới");
            pinLabel.setText("HẾT HẠN");
            return;
        }
        long totalSeconds = (remainingMs + 999) / 1_000; // làm tròn lên
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        countdownLabel.setText(String.format("Hiệu lực còn %d:%02d", minutes, seconds));
    }

    /** Daemon thread poll DB: PIN hiện hành bị tiêu thụ = client đã ghép đôi. */
    private void startPairedWatcher() {
        pairedWatcher = new Thread(() -> {
            String watchedPin = currentPin;
            while (watchedPin != null && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(PAIRED_POLL_MS);
                    if (pairingDao.getPinState(watchedPin) == PairingDao.PinState.USED) {
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            if (!isDisplayable() || !isVisible()) {
                                return; // dialog đã đóng bằng tay — bỏ qua
                            }
                            statusLabel.setText("✅ Thiết bị đã ghép đôi thành công!");
                            stopWatchers();
                            if (onPairedCallback != null) {
                                onPairedCallback.run();
                            }
                            // Tự động đóng sau khi thông báo (cho phụ huynh kịp thấy).
                            javax.swing.Timer closeTimer =
                                    new javax.swing.Timer(1_200, event -> dispose());
                            closeTimer.setRepeats(false);
                            closeTimer.start();
                        });
                        return;
                    }
                } catch (InterruptedException e) {
                    return; // dialog đóng — kết thúc watcher
                } catch (RuntimeException e) {
                    log.warn("Paired watcher error (ignored): {}", e.getMessage());
                }
            }
        }, "parental-server-pairing-watcher");
        pairedWatcher.setDaemon(true);
        pairedWatcher.start();
    }

    private void stopWatchers() {
        if (countdownTimer != null) {
            countdownTimer.stop();
        }
        Thread watcher = pairedWatcher;
        if (watcher != null) {
            watcher.interrupt();
            pairedWatcher = null;
        }
    }

    @Override
    public void dispose() {
        stopWatchers();
        super.dispose();
    }

    /** Hiển thị dialog giữa frame cha (tiện gọi từ dashboard). */
    public void open() {
        setVisible(true);
    }

    /** PIN đang hiển thị (dùng trong test headless). */
    String currentPinForTest() {
        return currentPin;
    }
}
