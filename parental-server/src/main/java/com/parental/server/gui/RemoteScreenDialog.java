package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientHandler;
import com.parental.server.network.ClientSessionManager;
import com.parental.server.stream.AdaptiveStreamController;
import java.util.Objects;

/**
 * Dialog xem màn hình remote realtime (Mục 3.10 của {@code docs/PROTOCOL.md},
 * Live Screen Streaming) — hiển thị từng {@link ScreenFramePayload} nhận được
 * từ client online, cập nhật hình ảnh trên EDT, tự động dọn dẹp khi đóng.
 *
 * <p>Luồng làm việc:
 * <ul>
 *   <li>Nếu session online: gửi {@code STREAM_CONTROL {action=START}} ngay,
 *       nhận {@code STREAM_FRAME} từ reader thread của {@link ClientSession}
 *       và decode/áp vào JDialog trên EDT.</li>
 *   <li>Khi người dùng đóng dialog hoặc stream ngắt kết nối: gửi
 *       {@code STREAM_CONTROL {action=STOP}} và dừng refresh.</li>
 * </ul></p>
 */
public final class RemoteScreenDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(RemoteScreenDialog.class);

    private final String clientId;
    private final ClientSessionManager sessionManager;
    private final Runnable onClosed;

    private final ScreenImagePanel imagePanel;
    private final JLabel statusLabel;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(false);

    /** Điều chỉnh FPS/JPEG theo băng thông/latency thực tế (Tính năng 3.2). */
    private final AdaptiveStreamController adaptiveController = new AdaptiveStreamController();
    /** seqNo frame đã hiển thị — tránh decode lại cùng frame mỗi 500ms. */
    private long lastRenderedSeq = -1;
    /** Thời điểm nhận frame gần nhất để hiển thị latency (ms). */
    private long lastFrameLatencyMs = -1;

    /** Chặn độ lớn frame Base64 để không overflow label/heap khi network congested. */
    private static final int MAX_DECODE_PIXELS = 1920 * 1080;

    public RemoteScreenDialog(JFrame owner, String clientId,
                              ClientSessionManager sessionManager, Runnable onClosed) {
        super(owner, "Xem màn hình remote - " + clientId, true);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");

        setSize(1000, 700);
        setMinimumSize(new Dimension(600, 420));
        setLocationRelativeTo(owner);
        // Mặc định JDialog là HIDE_ON_CLOSE: bấm X chỉ ẩn dialog → windowClosed
        // KHÔNG chạy → client tiếp tục chụp/gửi frame mãi. DISPOSE buộc dọn stream.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        imagePanel = new ScreenImagePanel(new Dimension(960, 540), "Đang chờ khung hình...");

        statusLabel = new JLabel("Phát hiện stream...", JLabel.CENTER);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(createEmptyBorder());
        content.add(statusLabel, BorderLayout.NORTH);
        content.add(imagePanel, BorderLayout.CENTER);

        JButton closeButton = new JButton("Đóng");
        closeButton.addActionListener(event -> dispose());
        JButton maximizeButton = new JButton("Phóng to");
        Rectangle restoreBounds = getBounds();
        maximizeButton.addActionListener(event -> {
            if (maximizeButton.getText().equals("Phóng to")) {
                restoreBounds.setBounds(getBounds());
                setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getMaximumWindowBounds());
                maximizeButton.setText("Khôi phục kích thước");
            } else {
                setBounds(restoreBounds);
                maximizeButton.setText("Phóng to");
            }
        });
        JPanel buttonRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttonRow.add(maximizeButton);
        buttonRow.add(closeButton);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(buttonRow, BorderLayout.CENTER);

        add(content, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                closeStream();
                if (onClosed != null) {
                    onClosed.run();
                }
                open.set(false);
            }
        });

        open.set(true);
        // PHẢI gửi START trước setVisible(true): dialog là MODAL nên setVisible
        // block constructor cho tới khi dialog đóng — đặt sau sẽ khiến stream
        // không bao giờ được bật, sau đó còn bật stream vào dialog đã dispose.
        startStreamIfOnline();
        setVisible(true);
    }

    private static javax.swing.border.Border createEmptyBorder() {
        return javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8);
    }

    /** Gửi lệnh START/STOP tùy trạng thái streaming hiện tại. */
    private void sendStreamControl(boolean start) {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || !session.isConnected()) {
            log.debug("[RemoteScreenDialog] client {} không trực tuyến — không gửi STREAM_CONTROL",
                    clientId);
            return;
        }
        String action = start ? StreamControlPayload.ACTION_START : StreamControlPayload.ACTION_STOP;
        AdaptiveStreamController.Settings settings = adaptiveController.currentSettings();
        try {
            BaseMessage<?> control = BaseMessage.of(MessageType.STREAM_CONTROL, ClientHandler.SERVER_ID,
                    new StreamControlPayload(action, settings.width(), settings.height(),
                            settings.quality(), settings.fps()));
            session.send(control);
            log.debug("[RemoteScreenDialog] Gửi STREAM_CONTROL {} tới {}", action, clientId);
        } catch (Exception e) {
            log.error("[RemoteScreenDialog] Lỗi gửi STREAM_CONTROL {}: {}", action, e.getMessage());
        }
    }

    /** Bật stream nếu session online; nếu ngắt kết nối giữa chừng, dừng và thông báo. */
    private void startStreamIfOnline() {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || !session.isConnected()) {
            setStatus("Client không trực tuyến", true);
            return;
        }
        if (!streaming.compareAndSet(false, true)) {
            return;
        }
        adaptiveController.reset();
        lastRenderedSeq = -1;
        lastFrameLatencyMs = -1;
        sendStreamControl(true);
        scheduleFramePoll();
    }

    /** Poll session.currentStreamFrame định kỳ và cập nhật hình ảnh trên EDT. */
    private void scheduleFramePoll() {
        if (!streaming.get() || !open.get()) {
            return;
        }
        try {
            ClientSession session = sessionManager.get(clientId);
            if (session == null || !session.isConnected()) {
                setStatus("Mất kết nối", true);
                closeStream();
                return;
            }
            ScreenFramePayload frame = session.getLastStreamFrame();
            if (frame != null && !frame.jpegBase64().isBlank()
                    && frame.seqNo() != lastRenderedSeq) {
                lastRenderedSeq = frame.seqNo();
                long now = System.currentTimeMillis();
                lastFrameLatencyMs = frame.tsMs() > 0 && now >= frame.tsMs()
                        ? now - frame.tsMs() : -1;
                adaptiveController.onFrame(now, frame.jpegBase64().length(), frame.tsMs());
                applyAdaptiveDecision(now);
                long latencyForFrame = lastFrameLatencyMs;
                SwingUtilities.invokeLater(() -> {
                    BufferedImage img = decodeFrame(frame);
                    if (img != null) {
                        imagePanel.setImage(img);
                        AdaptiveStreamController.Settings settings =
                                adaptiveController.currentSettings();
                        statusLabel.setText("LIVE • " + frame.width() + "x" + frame.height()
                                + " • seq=" + frame.seqNo()
                                + " • " + settings.fps() + "fps q="
                                + String.format(java.util.Locale.ROOT, "%.2f", settings.quality())
                                + (latencyForFrame >= 0
                                        ? " • " + latencyForFrame + "ms" : "")
                                + " • " + java.time.LocalTime.now());
                    }
                });
            }
        } catch (Exception e) {
            log.error("[RemoteScreenDialog] Lỗi decode frame {}: {}", clientId, e.getMessage());
        }
        // Poll tiếp theo — mỗi 500ms đủ nhanh để không delay nhiều, nhưng không
        // spam khi network chậm/đính kèm hình lớn.
        javax.swing.Timer pollTimer = new javax.swing.Timer(500, event -> {
            if (streaming.get() && open.get()) {
                scheduleFramePoll();
            }
        });
        pollTimer.setRepeats(false);
        pollTimer.start();
    }

    /** Giải mã Base64 → BufferedImage. Không vỡ ứng dụng khi Base64 hỏng. */
    private BufferedImage decodeFrame(ScreenFramePayload frame) {
        if (frame.width() <= 0 || frame.height() <= 0) {
            return null;
        }
        if (frame.width() * frame.height() > MAX_DECODE_PIXELS) {
            log.warn("[RemoteScreenDialog] Frame quá lớn {}x{} — bỏ qua", frame.width(), frame.height());
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(frame.jpegBase64());
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.error("[RemoteScreenDialog] Giải mã frame thất bại {}: {}", clientId, e.getMessage());
            return null;
        }
    }

    /**
     * Áp quyết định adaptive: nếu controller đổi cấu hình, gửi lại
     * {@code STREAM_CONTROL START} với FPS/quality/width mới. Client hiện tại
     * xử lý START lặp bằng cách thay scheduler (không nhân đôi tần suất).
     */
    private void applyAdaptiveDecision(long nowMs) {
        if (!streaming.get() || !open.get()) {
            return;
        }
        AdaptiveStreamController.Decision decision = adaptiveController.evaluate(nowMs);
        if (decision.changed()) {
            log.info("[RemoteScreenDialog] Adaptive stream [{}]: chuyển sang {} ({})",
                    clientId, decision.settings().describe(), decision.reason());
            sendStreamControl(true);
        }
    }

    private void setStatus(String text, boolean warn) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(warn ? java.awt.Color.RED : java.awt.Color.BLACK);
        });
    }

    /** Dừng stream + gửi STOP. Gọi khi đóng dialog hoặc mất kết nối. */
    private void closeStream() {
        if (!streaming.compareAndSet(true, false)) {
            return;
        }
        sendStreamControl(false);
        adaptiveController.reset();
        lastRenderedSeq = -1;
        lastFrameLatencyMs = -1;
    }
}
