package com.parental.client.input;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Chỉ báo luôn nổi trên màn hình Client khi phiên điều khiển đang hoạt động,
 * kèm nút “Dừng điều khiển” (Mục 3.11). Người dùng đóng cửa sổ cũng dừng
 * phiên ngay.
 */
public final class SwingControlIndicator implements ControlIndicator {

    private final AtomicReference<JFrame> frame = new AtomicReference<>();

    @Override
    public void show(Runnable stopAction) {
        if (isHeadless()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            disposeNow();
            JFrame indicator = new JFrame("Điều khiển từ xa đang hoạt động");
            indicator.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            indicator.setAlwaysOnTop(true);
            // Không trang trí để người dùng không thể thu nhỏ/ẩn chỉ báo;
            // không nhận focus để phím điều khiển từ xa vẫn tới ứng dụng đích.
            indicator.setUndecorated(true);
            indicator.setResizable(false);
            indicator.setFocusableWindowState(false);
            indicator.setAutoRequestFocus(false);
            indicator.setLayout(new BorderLayout(10, 10));

            JPanel banner = new JPanel(new BorderLayout(8, 8));
            banner.setBackground(new Color(0xB4, 0x1C, 0x1C));
            banner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

            JLabel label = new JLabel("ĐIỀU KHIỂN TỪ XA ĐANG HOẠT ĐỘNG");
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));

            JButton stopButton = new JButton("Dừng điều khiển");
            stopButton.setFocusPainted(false);
            stopButton.addActionListener(event -> {
                if (stopAction != null) {
                    stopAction.run();
                }
            });

            banner.add(label, BorderLayout.CENTER);
            banner.add(stopButton, BorderLayout.SOUTH);
            indicator.add(banner, BorderLayout.CENTER);

            indicator.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent event) {
                    if (stopAction != null) {
                        stopAction.run();
                    }
                }
            });

            indicator.pack();
            java.awt.Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            indicator.setLocation(screen.x + screen.width - indicator.getWidth() - 24,
                    screen.y + 24);
            frame.set(indicator);
            indicator.setVisible(true);
        });
    }

    @Override
    public void hide() {
        SwingUtilities.invokeLater(this::disposeNow);
    }

    private void disposeNow() {
        JFrame current = frame.getAndSet(null);
        if (current != null) {
            current.dispose();
        }
    }

    private static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
