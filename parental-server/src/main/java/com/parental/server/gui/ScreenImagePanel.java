package com.parental.server.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

/** Displays a remote frame scaled to fit the available area without cropping. */
final class ScreenImagePanel extends JPanel {

    private BufferedImage image;
    private String placeholder;

    ScreenImagePanel(Dimension preferredSize, String placeholder) {
        this.placeholder = placeholder;
        setPreferredSize(preferredSize);
        setMinimumSize(new Dimension(160, 100));
        setBackground(Color.BLACK);
        setOpaque(true);
    }

    void setImage(BufferedImage image) {
        this.image = image;
        this.placeholder = null;
        repaint();
    }

    void setPlaceholder(String placeholder) {
        this.image = null;
        this.placeholder = placeholder;
        repaint();
    }

    /**
     * Quy đổi một điểm trên panel sang pixel của frame ảnh đang hiển thị.
     * Trả {@code null} nếu điểm nằm ngoài vùng ảnh (viền đen letterbox) hoặc
     * frame/kích thước không hợp lệ.
     */
    static java.awt.Point toImagePoint(int panelX, int panelY,
                                       int imageWidth, int imageHeight, int areaWidth, int areaHeight) {
        Rectangle bounds = fitBounds(imageWidth, imageHeight, areaWidth, areaHeight);
        if (bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        if (panelX < bounds.x || panelY < bounds.y
                || panelX >= bounds.x + bounds.width || panelY >= bounds.y + bounds.height) {
            return null;
        }
        int imageX = (int) Math.floor((panelX - bounds.x)
                * (imageWidth / (double) bounds.width));
        int imageY = (int) Math.floor((panelY - bounds.y)
                * (imageHeight / (double) bounds.height));
        imageX = Math.max(0, Math.min(imageWidth - 1, imageX));
        imageY = Math.max(0, Math.min(imageHeight - 1, imageY));
        return new java.awt.Point(imageX, imageY);
    }

    static java.awt.Point toImagePointForMouseButton(int panelX, int panelY,
                                                    int imageWidth, int imageHeight,
                                                    int areaWidth, int areaHeight,
                                                    boolean down) {
        java.awt.Point point = toImagePoint(panelX, panelY,
                imageWidth, imageHeight, areaWidth, areaHeight);
        return point == null && !down ? new java.awt.Point(0, 0) : point;
    }

    static Rectangle fitBounds(int imageWidth, int imageHeight, int areaWidth, int areaHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || areaWidth <= 0 || areaHeight <= 0) {
            return new Rectangle();
        }
        double scale = Math.min((double) areaWidth / imageWidth, (double) areaHeight / imageHeight);
        int width = Math.max(1, (int) Math.floor(imageWidth * scale));
        int height = Math.max(1, (int) Math.floor(imageHeight * scale));
        return new Rectangle((areaWidth - width) / 2, (areaHeight - height) / 2, width, height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (image == null) {
                if (placeholder != null) {
                    g.setColor(Color.WHITE);
                    var metrics = g.getFontMetrics();
                    g.drawString(placeholder,
                            Math.max(8, (getWidth() - metrics.stringWidth(placeholder)) / 2),
                            getHeight() / 2);
                }
                return;
            }
            Rectangle bounds = fitBounds(image.getWidth(), image.getHeight(), getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
        } finally {
            g.dispose();
        }
    }
}
