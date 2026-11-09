package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.server.network.ClientConnectionListener;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientHandler;
import com.parental.server.network.ClientSessionManager;
import com.parental.server.remoteinput.ServerRemoteInputController;
import com.parental.server.stream.AdaptiveStreamController;
import static com.parental.ui.DesktopTheme.CANVAS;
import static com.parental.ui.DesktopTheme.PRIMARY;
import static com.parental.ui.DesktopTheme.TEXT_PRIMARY;
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

    /** Phiên điều khiển có đồng ý (Mục 3.11) — mỗi viewer sở hữu 1 controller. */
    private final ServerRemoteInputController inputController;
    private final ClientConnectionListener inputAckListener;
    /** Gửi input trên thread nền — không chặn EDT bằng I/O socket. */
    private final ExecutorService inputSender;
    /** Luồng riêng gửi STOP nếu hàng đợi input đầy để Client nhả input đang giữ. */
    private final ExecutorService inputStopper;
    private final AtomicBoolean inputQueueStopRequested = new AtomicBoolean();
    private JButton controlButton;
    private JLabel controlStatusLabel;
    /** Kích thước frame đang hiển thị — dùng quy đổi tọa độ chuột. */
    private int lastFrameWidth = -1;
    private int lastFrameHeight = -1;

    public RemoteScreenDialog(JFrame owner, String clientId,
                              ClientSessionManager sessionManager, Runnable onClosed) {
        super(owner, "Xem màn hình remote - " + clientId, true);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");

        this.inputSender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                runnable -> {
                    Thread thread = new Thread(runnable, "remote-input-sender");
                    thread.setDaemon(true);
                    return thread;
                });
        this.inputStopper = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "remote-input-stop");
            thread.setDaemon(true);
            return thread;
        });
        this.inputController = new ServerRemoteInputController(clientId,
                new ServerRemoteInputController.Transport() {
                    @Override
                    public boolean isOnline() {
                        ClientSession session = sessionManager.get(clientId);
                        return session != null && session.isConnected();
                    }

                    @Override
                    public boolean send(BaseMessage<?> message) {
                        ClientSession session = sessionManager.get(clientId);
                        return session != null && session.send(message);
                    }
                });
        this.inputAckListener = new ClientConnectionListener() {
            @Override
            public void onRemoteInputAck(ClientSession session, RemoteInputAckPayload ack) {
                if (!clientId.equals(session.getClientId())) {
                    return;
                }
                if (inputController.onAck(ack)) {
                    SwingUtilities.invokeLater(RemoteScreenDialog.this::updateControlUi);
                }
            }
        };
        sessionManager.addConnectionListener(inputAckListener);

        setSize(1000, 700);
        setMinimumSize(new Dimension(600, 420));
        setLocationRelativeTo(owner);
        // Mặc định JDialog là HIDE_ON_CLOSE: bấm X chỉ ẩn dialog → windowClosed
        // KHÔNG chạy → client tiếp tục chụp/gửi frame mãi. DISPOSE buộc dọn stream.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(CANVAS);

        imagePanel = new ScreenImagePanel(new Dimension(960, 540), "Đang chờ khung hình...");
        imagePanel.setBorder(javax.swing.BorderFactory.createLineBorder(
                new java.awt.Color(0xD8, 0xE1, 0xED)));

        statusLabel = new JLabel("Phát hiện stream...", JLabel.CENTER);
        statusLabel.setForeground(TEXT_PRIMARY);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(createEmptyBorder());
        content.add(statusLabel, BorderLayout.NORTH);
        content.add(imagePanel, BorderLayout.CENTER);

        controlButton = new JButton("Điều khiển chuột/bàn phím");
        controlButton.addActionListener(event -> toggleRemoteInput());
        controlStatusLabel = new JLabel("Chưa yêu cầu điều khiển", JLabel.LEFT);
        controlStatusLabel.setForeground(TEXT_PRIMARY);

        JButton closeButton = new JButton("Đóng");
        closeButton.addActionListener(event -> dispose());
        closeButton.setBackground(PRIMARY);
        closeButton.setForeground(java.awt.Color.WHITE);
        closeButton.setFocusPainted(false);
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
        buttonRow.add(controlButton);
        buttonRow.add(maximizeButton);
        buttonRow.add(closeButton);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(controlStatusLabel, BorderLayout.NORTH);
        bottom.add(buttonRow, BorderLayout.CENTER);

        add(content, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        installInputCapture();

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                inputSender.shutdownNow();
                inputStopper.shutdownNow();
                sessionManager.removeConnectionListener(inputAckListener);
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
            inputController.expireIfNeeded();
            updateControlUi();
            ScreenFramePayload frame = session.getLastStreamFrame();
            if (frame != null && !frame.jpegBase64().isBlank()
                    && frame.seqNo() != lastRenderedSeq) {
                lastRenderedSeq = frame.seqNo();
                lastFrameWidth = frame.width();
                lastFrameHeight = frame.height();
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

    // ------------------------------------------------------------------
    // Remote input control (Mục 3.11)
    // ------------------------------------------------------------------

    /**
     * Bật/tắt yêu cầu điều khiển. Chỉ yêu cầu khi cửa sổ xem màn hình đang mở
     * và Client đang online; không bao giờ tự bật, luôn chờ Client chấp thuận.
     */
    private void toggleRemoteInput() {
        ServerRemoteInputController.State state = inputController.getState();
        if (state == ServerRemoteInputController.State.ACTIVE
                || state == ServerRemoteInputController.State.WAITING_CONSENT) {
            inputController.stop("Người quản trị đã dừng điều khiển");
            updateControlUi();
            return;
        }
        if (!streaming.get() || !open.get()) {
            controlStatusLabel.setText("Chưa mở phiên xem màn hình — không thể điều khiển");
            return;
        }
        ClientSession session = sessionManager.get(clientId);
        if (session == null || !session.isConnected()) {
            controlStatusLabel.setText("Client không trực tuyến — không thể điều khiển");
            return;
        }
        inputQueueStopRequested.set(false);
        inputController.requestControl();
        updateControlUi();
    }

    /** Cập nhật nhãn/trạng thái nút điều khiển (chạy trên EDT). */
    private void updateControlUi() {
        if (controlButton == null || controlStatusLabel == null) {
            return;
        }
        ClientSession session = sessionManager.get(clientId);
        boolean online = session != null && session.isConnected();
        ServerRemoteInputController.State state = inputController.getState();
        switch (state) {
            case WAITING_CONSENT -> {
                controlButton.setText("Hủy yêu cầu điều khiển");
                controlButton.setEnabled(online);
                controlStatusLabel.setText("Đang chờ Client chấp nhận yêu cầu điều khiển...");
                controlStatusLabel.setForeground(new java.awt.Color(0xB4, 0x53, 0x09));
            }
            case ACTIVE -> {
                controlButton.setText("Dừng điều khiển");
                controlButton.setEnabled(online);
                controlStatusLabel.setText("ĐANG ĐIỀU KHIỂN — nhấp vào hình để dùng bàn phím; "
                        + "nhấn Dừng để kết thúc ngay.");
                controlStatusLabel.setForeground(new java.awt.Color(0x15, 0x80, 0x3D));
            }
            default -> {
                controlButton.setText("Điều khiển chuột/bàn phím");
                controlButton.setEnabled(online && streaming.get() && open.get());
                controlStatusLabel.setText(inputController.getLastStatus());
                controlStatusLabel.setForeground(TEXT_PRIMARY);
            }
        }
    }

    /** Gắn listener chuột/bàn phím — chỉ gửi khi phiên đang ACTIVE. */
    private void installInputCapture() {
        imagePanel.setFocusable(true);
        MouseInputAdapter mouse = new MouseInputAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                imagePanel.requestFocusInWindow();
                submitMouseButton(event, true);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                submitMouseButton(event, false);
            }

            @Override
            public void mouseMoved(java.awt.event.MouseEvent event) {
                submitMouseMove(event);
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent event) {
                submitMouseMove(event);
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                java.awt.Point point = imagePoint(event.getX(), event.getY());
                if (point == null || !isControlActive()) {
                    return;
                }
                int notches = event.getWheelRotation();
                int width = lastFrameWidth;
                int height = lastFrameHeight;
                submitInput(() -> inputController.sendMouseWheel(notches,
                        point.x, point.y, width, height));
            }
        };
        imagePanel.addMouseListener(mouse);
        imagePanel.addMouseMotionListener(mouse);
        imagePanel.addMouseWheelListener(mouse);
        imagePanel.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent event) {
                if (!isControlActive()) {
                    return;
                }
                int keyCode = event.getKeyCode();
                submitInput(() -> inputController.sendKey(true, keyCode));
            }

            @Override
            public void keyReleased(java.awt.event.KeyEvent event) {
                if (!isControlActive()) {
                    return;
                }
                int keyCode = event.getKeyCode();
                submitInput(() -> inputController.sendKey(false, keyCode));
            }
        });
    }

    private boolean isControlActive() {
        return open.get() && lastFrameWidth > 0 && lastFrameHeight > 0
                && inputController.getState() == ServerRemoteInputController.State.ACTIVE;
    }

    private void submitMouseMove(java.awt.event.MouseEvent event) {
        if (!isControlActive()) {
            return;
        }
        java.awt.Point point = imagePoint(event.getX(), event.getY());
        if (point == null) {
            return;
        }
        int width = lastFrameWidth;
        int height = lastFrameHeight;
        submitInput(() -> inputController.sendMouseMove(point.x, point.y, width, height));
    }

    private void submitMouseButton(java.awt.event.MouseEvent event, boolean down) {
        if (!isControlActive()) {
            return;
        }
        int button = event.getButton();
        if (button < 1 || button > 3) {
            return;
        }
        java.awt.Point point = ScreenImagePanel.toImagePointForMouseButton(
                event.getX(), event.getY(),
                lastFrameWidth, lastFrameHeight, imagePanel.getWidth(), imagePanel.getHeight(), down);
        if (point == null) {
            return;
        }
        int width = lastFrameWidth;
        int height = lastFrameHeight;
        submitInput(() -> inputController.sendMouseButton(down, button,
                point.x, point.y, width, height));
    }

    /** Quy đổi điểm panel → pixel frame; null khi ngoài vùng ảnh. */
    private java.awt.Point imagePoint(int panelX, int panelY) {
        return ScreenImagePanel.toImagePoint(panelX, panelY,
                lastFrameWidth, lastFrameHeight, imagePanel.getWidth(), imagePanel.getHeight());
    }

    /** Gửi trên thread nền, bỏ qua khi hàng đợi đầy (không block EDT). */
    private void submitInput(Runnable task) {
        if (!open.get()) {
            return;
        }
        try {
            inputSender.execute(task);
        } catch (RejectedExecutionException e) {
            if (inputQueueStopRequested.compareAndSet(false, true)) {
                log.warn("[RemoteScreenDialog] Input queue saturated — stopping control session");
                try {
                    inputStopper.execute(() -> {
                        inputController.stop("Hàng đợi input quá tải — dừng phiên an toàn");
                        SwingUtilities.invokeLater(this::updateControlUi);
                    });
                } catch (RejectedExecutionException stopError) {
                    log.warn("[RemoteScreenDialog] Could not schedule safe input stop: {}",
                            stopError.getMessage());
                }
            }
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
        // Mục 3.11: đóng viewer/mất kết nối phải thu hồi quyền điều khiển.
        inputController.stop("Cửa sổ xem màn hình đã đóng hoặc mất kết nối");
        lastFrameWidth = -1;
        lastFrameHeight = -1;
        if (!streaming.compareAndSet(true, false)) {
            return;
        }
        sendStreamControl(false);
        adaptiveController.reset();
        lastRenderedSeq = -1;
        lastFrameLatencyMs = -1;
    }
}
