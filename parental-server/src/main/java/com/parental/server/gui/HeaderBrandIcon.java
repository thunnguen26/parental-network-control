package com.parental.server.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;

/**
 * Biểu tượng chiếc khiên kết hợp quả địa cầu/mạng lưới (Shield with Globe)
 * ở góc trái tiêu đề ứng dụng theo đúng thiết kế trong ảnh mẫu.
 */
public class HeaderBrandIcon extends JComponent {

    public HeaderBrandIcon() {
        setPreferredSize(new Dimension(44, 44));
        setMinimumSize(new Dimension(44, 44));
        setOpaque(false);
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

            double pad = 3.0;
            double sw = w - pad * 2;
            double sh = h - pad * 2;

            // Hình dạng chiếc khiên
            Path2D.Double shield = new Path2D.Double();
            shield.moveTo(w / 2.0, pad);
            shield.lineTo(w - pad - 2, pad + 6);
            shield.quadTo(w - pad, h * 0.65, w / 2.0, h - pad);
            shield.quadTo(pad, h * 0.65, pad + 2, pad + 6);
            shield.closePath();

            // Gradient xanh dương đậm đến xanh dương sáng
            GradientPaint shieldGrad = new GradientPaint(
                    0, 0, new Color(0x1E, 0x64, 0xD8),
                    0, h, new Color(0x02, 0x84, 0xC7));
            g2.setPaint(shieldGrad);
            g2.fill(shield);

            g2.setColor(new Color(0x60, 0xA5, 0xFA));
            g2.setStroke(new BasicStroke(1.8f));
            g2.draw(shield);

            // Vẽ quả địa cầu / lưới bên trong
            double cx = w / 2.0;
            double cy = h * 0.48;
            double r = Math.min(sw, sh) * 0.28;

            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.4f));
            // Vòng tròn ngoài quả địa cầu
            g2.drawOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
            // Đường xích đạo
            g2.drawLine((int) (cx - r), (int) cy, (int) (cx + r), (int) cy);
            // Kinh tuyến giữa
            g2.drawLine((int) cx, (int) (cy - r), (int) cx, (int) (cy + r));
            // Kinh tuyến cong
            g2.drawOval((int) (cx - r * 0.5), (int) (cy - r), (int) (r), (int) (r * 2));
        } finally {
            g2.dispose();
        }
    }
}
