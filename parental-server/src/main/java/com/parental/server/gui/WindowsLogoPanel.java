package com.parental.server.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/**
 * Khối hiển thị logo hệ điều hành (Windows 11) dạng vector góc bo sạch sẽ,
 * khớp chính xác với thẻ thông tin thiết bị trong ảnh mẫu.
 */
public class WindowsLogoPanel extends JPanel {

    private static final Color BG_COLOR = new Color(0xE8, 0xF2, 0xFC);
    private static final Color BORDER_COLOR = new Color(0xCF, 0xE2, 0xFA);
    private static final Color WIN_BLUE = new Color(0x00, 0x78, 0xD4);

    public WindowsLogoPanel() {
        setOpaque(false);
        setPreferredSize(new Dimension(110, 110));
        setMinimumSize(new Dimension(80, 80));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 10 || h <= 10) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Nền bo góc
            g2.setColor(BG_COLOR);
            g2.fillRoundRect(1, 1, w - 2, h - 2, 16, 16);
            g2.setColor(BORDER_COLOR);
            g2.drawRoundRect(1, 1, w - 2, h - 2, 16, 16);

            // Vẽ 4 ô vuông Windows 11
            int logoSize = Math.min(w, h) * 44 / 100;
            int gap = Math.max(3, logoSize / 11);
            int tileSize = (logoSize - gap) / 2;
            int startX = (w - (tileSize * 2 + gap)) / 2;
            int startY = (h - (tileSize * 2 + gap)) / 2;

            g2.setColor(WIN_BLUE);
            // Ô trên - trái
            g2.fillRect(startX, startY, tileSize, tileSize);
            // Ô trên - phải
            g2.fillRect(startX + tileSize + gap, startY, tileSize, tileSize);
            // Ô dưới - trái
            g2.fillRect(startX, startY + tileSize + gap, tileSize, tileSize);
            // Ô dưới - phải
            g2.fillRect(startX + tileSize + gap, startY + tileSize + gap, tileSize, tileSize);
        } finally {
            g2.dispose();
        }
    }
}
