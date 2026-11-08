package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.server.network.ClientHandler;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientSessionManager;

/** Tiled, low-bandwidth live view of all clients online when the dialog opens. */
public final class RemoteScreenWallDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(RemoteScreenWallDialog.class);
    private static final int WALL_WIDTH = 480;
    private static final int WALL_HEIGHT = 270;
    private static final int WALL_FPS = 2;
    private static final double WALL_QUALITY = 0.45;
    private static final int MAX_FRAME_BASE64_CHARS = 4 * 1024 * 1024;
    private static final long MAX_FRAME_PIXELS = 8_000_000L;

    private final JFrame owner;
    private final ClientSessionManager sessionManager;
    private final Runnable onClosed;
    private final List<ClientTile> tiles = new ArrayList<>();
    private final JLabel summaryLabel = new JLabel();
    private final JButton detailsButton = new JButton("Xem chi tiết máy đã chọn");
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean streamsStopped = new AtomicBoolean(false);
    private final Timer pollTimer;
    private ClientTile selectedTile;
    private boolean openingDetails;

    public RemoteScreenWallDialog(JFrame owner, ClientSessionManager sessionManager,
                                  Runnable onClosed) {
        super(owner, "Quan sát tất cả máy đang online", true);
        this.owner = owner;
        this.sessionManager = sessionManager;
        this.onClosed = onClosed;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(720, 480));
        setSize(1100, 760);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        List<ClientSession> onlineSessions = sessionManager.getAllSessions().stream()
                .filter(ClientSession::isConnected)
                .toList();
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (ClientSession session : onlineSessions) {
            ClientTile tile = new ClientTile(session);
            tiles.add(tile);
            grid.add(tile);
        }

        JPanel top = new JPanel(new BorderLayout());
        summaryLabel.setText(onlineSessions.size() + " máy online • preview "
                + WALL_WIDTH + "×" + WALL_HEIGHT + " • " + WALL_FPS + " FPS mỗi máy");
        top.add(summaryLabel, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(grid), BorderLayout.CENTER);

        JButton closeButton = new JButton("Đóng");
        closeButton.addActionListener(event -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        detailsButton.setEnabled(false);
        detailsButton.addActionListener(event -> openSelectedDetails());
        buttons.add(detailsButton);
        buttons.add(closeButton);
        add(buttons, BorderLayout.SOUTH);

        pollTimer = new Timer(300, event -> pollFrames());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                open.set(false);
                pollTimer.stop();
                stopStreams();
                if (!openingDetails && onClosed != null) {
                    onClosed.run();
                }
            }
        });

        if (tiles.isEmpty()) {
            grid.add(new JLabel("Hiện không có thiết bị online.", JLabel.CENTER));
        } else {
            selectTile(tiles.get(0));
        }
        startStreams();
        pollTimer.start();
        setVisible(true);
    }

    private void startStreams() {
        for (ClientTile tile : tiles) {
            ClientSession current = sessionManager.get(tile.session.getClientId());
            if (current != tile.session || !current.isConnected()) {
                tile.status.setText("Không còn online");
                continue;
            }
            current.setLastStreamFrame(null);
            if (sendControl(current, true)) {
                tile.streaming = true;
                tile.status.setText("Đang chờ hình...");
            } else {
                tile.status.setText("Không gửi được lệnh bắt đầu");
            }
        }
    }

    private void pollFrames() {
        if (!open.get()) {
            return;
        }
        for (ClientTile tile : tiles) {
            if (!tile.streaming || tile.decodePending) {
                continue;
            }
            ClientSession current = sessionManager.get(tile.session.getClientId());
            if (current != tile.session || !tile.session.isConnected()) {
                tile.streaming = false;
                tile.status.setText("Mất kết nối • mở lại cửa sổ để xem khi online");
                tile.image.setPlaceholder("Ngoại tuyến");
                continue;
            }
            ScreenFramePayload frame = tile.session.getLastStreamFrame();
            if (frame == null || frame.seqNo() == tile.lastSequence) {
                continue;
            }
            tile.lastSequence = frame.seqNo();
            tile.decodePending = true;
            new SwingWorker<BufferedImage, Void>() {
                @Override
                protected BufferedImage doInBackground() {
                    return decode(frame);
                }

                @Override
                protected void done() {
                    tile.decodePending = false;
                    if (!open.get()) {
                        return;
                    }
                    try {
                        BufferedImage image = get();
                        if (image != null) {
                            tile.image.setImage(image);
                            tile.status.setText("LIVE • " + frame.width() + "×" + frame.height()
                                    + " • seq=" + frame.seqNo());
                        } else {
                            tile.status.setText("Khung hình không hợp lệ");
                        }
                    } catch (Exception e) {
                        tile.status.setText("Lỗi giải mã hình");
                        log.warn("Failed to decode stream frame from {}: {}",
                                tile.session.getClientId(), e.getMessage());
                    }
                }
            }.execute();
        }
    }

    private static BufferedImage decode(ScreenFramePayload frame) {
        if (frame.width() <= 0 || frame.height() <= 0
                || (long) frame.width() * frame.height() > MAX_FRAME_PIXELS
                || frame.jpegBase64() == null
                || frame.jpegBase64().length() > MAX_FRAME_BASE64_CHARS) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(frame.jpegBase64());
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null;
        }
    }

    private void selectTile(ClientTile tile) {
        if (selectedTile != null) {
            selectedTile.setSelected(false);
        }
        selectedTile = tile;
        selectedTile.setSelected(true);
        detailsButton.setEnabled(true);
    }

    private void openSelectedDetails() {
        if (selectedTile == null) {
            return;
        }
        String clientId = selectedTile.session.getClientId();
        openingDetails = true;
        stopStreams();
        pollTimer.stop();
        dispose();
        SwingUtilities.invokeLater(() ->
                new RemoteScreenDialog(owner, clientId, sessionManager, onClosed));
    }

    private void stopStreams() {
        if (!streamsStopped.compareAndSet(false, true)) {
            return;
        }
        for (ClientTile tile : tiles) {
            if (tile.streaming && tile.session.isConnected()) {
                sendControl(tile.session, false);
            }
            tile.streaming = false;
        }
    }

    private static boolean sendControl(ClientSession session, boolean start) {
        if (!session.isConnected()) {
            return false;
        }
        StreamControlPayload payload = start
                ? new StreamControlPayload(StreamControlPayload.ACTION_START, WALL_WIDTH,
                        WALL_HEIGHT, WALL_QUALITY, WALL_FPS)
                : new StreamControlPayload(StreamControlPayload.ACTION_STOP,
                        WALL_WIDTH, WALL_HEIGHT, WALL_QUALITY, WALL_FPS);
        return session.send(BaseMessage.of(MessageType.STREAM_CONTROL,
                ClientHandler.SERVER_ID, payload));
    }

    private final class ClientTile extends JPanel {
        private final ClientSession session;
        private final ScreenImagePanel image =
                new ScreenImagePanel(new Dimension(WALL_WIDTH, WALL_HEIGHT), "Đang chờ hình...");
        private final JLabel status = new JLabel("Đang kết nối stream...");
        private long lastSequence = Long.MIN_VALUE;
        private boolean streaming;
        private boolean decodePending;

        private ClientTile(ClientSession session) {
            super(new BorderLayout(4, 4));
            this.session = session;
            String name = session.getDeviceInfo() != null
                    ? session.getDeviceInfo().deviceName() : session.getClientId();
            JLabel title = new JLabel(name + " • " + session.getRemoteAddress());
            title.setToolTipText(session.getClientId());
            add(title, BorderLayout.NORTH);
            add(image, BorderLayout.CENTER);
            status.setForeground(new Color(0x33, 0x66, 0x33));
            add(status, BorderLayout.SOUTH);
            setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            java.awt.event.MouseAdapter selector = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    selectTile(ClientTile.this);
                }
            };
            addMouseListener(selector);
            title.addMouseListener(selector);
            image.addMouseListener(selector);
            status.addMouseListener(selector);
        }

        private void setSelected(boolean selected) {
            setBorder(BorderFactory.createLineBorder(selected ? new Color(0x20, 0x78, 0xD4)
                    : Color.GRAY, selected ? 3 : 1));
        }
    }
}
