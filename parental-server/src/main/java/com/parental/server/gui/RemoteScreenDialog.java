package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
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

    private final JLabel imageLabel;
    private final JLabel statusLabel;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(false);

    /** Chặn độ lớn frame Base64 để không overflow label/heap khi network congested. */
    private static final int MAX_DECODE_PIXELS = 1920 * 1080;

    public RemoteScreenDialog(JFrame owner, String clientId,
                              ClientSessionManager sessionManager, Runnable onClosed) {
        super(owner, "Xem màn hình remote - " + clientId, true);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");

        setSize(640, 480);
        setMinimumSize(new Dimension(480, 360));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        imageLabel = new JLabel("Không có hình", JLabel.CENTER);
        imageLabel.setPreferredSize(new Dimension(600, 400));
        imageLabel.setHorizontalTextPosition(JLabel.CENTER);
        imageLabel.setVerticalTextPosition(JLabel.BOTTOM);

        statusLabel = new JLabel("Phát hiện stream...", JLabel.CENTER);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(createEmptyBorder());
        content.add(statusLabel, BorderLayout.NORTH);
        content.add(imageLabel, BorderLayout.CENTER);

        JButton closeButton = new JButton("Đóng");
        closeButton.addActionListener(event -> dispose());
        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.add(closeButton, BorderLayout.EAST);

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
        setVisible(true);
        startStreamIfOnline();
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
        try {
            BaseMessage<?> control = BaseMessage.of(MessageType.STREAM_CONTROL, ClientHandler.SERVER_ID,
                    new StreamControlPayload(action, 0, 0, 0.7, 5L));
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
            if (frame != null && !frame.jpegBase64().isBlank()) {
                SwingUtilities.invokeLater(() -> {
                    BufferedImage img = decodeFrame(frame);
                    if (img != null) {
                        imageLabel.setIcon(new ImageIcon(img));
                        imageLabel.setText(null);
                        statusLabel.setText("LIVE • " + frame.width() + "x" + frame.height()
                                + " • seq=" + frame.seqNo() + " • "
                                + java.time.LocalTime.now().toString());
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
    }
}
