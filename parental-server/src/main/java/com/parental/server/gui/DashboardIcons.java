package com.parental.server.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * Bộ biểu tượng vector thuần Java 2D cho Dashboard — hoàn toàn không phụ thuộc
 * vào font emoji hệ điều hành, chống triệt để lỗi ô vuông (tofu) hay mất chữ.
 */
public final class DashboardIcons {

    private DashboardIcons() {
    }

    public static Icon home(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.15, h * 0.45);
            p.lineTo(w * 0.5, h * 0.15);
            p.lineTo(w * 0.85, h * 0.45);
            p.moveTo(w * 0.25, h * 0.40);
            p.lineTo(w * 0.25, h * 0.85);
            p.lineTo(w * 0.75, h * 0.85);
            p.lineTo(w * 0.75, h * 0.40);
            p.moveTo(w * 0.42, h * 0.85);
            p.lineTo(w * 0.42, h * 0.58);
            p.lineTo(w * 0.58, h * 0.58);
            p.lineTo(w * 0.58, h * 0.85);
            g2.draw(p);
        });
    }

    public static Icon monitor(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect((int) (w * 0.12), (int) (h * 0.15), (int) (w * 0.76), (int) (h * 0.54), 3, 3);
            g2.drawLine((int) (w * 0.35), (int) (h * 0.85), (int) (w * 0.65), (int) (h * 0.85));
            g2.drawLine((int) (w * 0.50), (int) (h * 0.69), (int) (w * 0.50), (int) (h * 0.85));
        });
    }

    public static Icon shield(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.5, h * 0.12);
            p.lineTo(w * 0.85, h * 0.25);
            p.quadTo(w * 0.85, h * 0.68, w * 0.5, h * 0.90);
            p.quadTo(w * 0.15, h * 0.68, w * 0.15, h * 0.25);
            p.closePath();
            g2.draw(p);
        });
    }

    public static Icon stats(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) (w * 0.12), (int) (h * 0.88), (int) (w * 0.88), (int) (h * 0.88));
            g2.fillRect((int) (w * 0.20), (int) (h * 0.52), (int) (w * 0.14), (int) (h * 0.36));
            g2.fillRect((int) (w * 0.43), (int) (h * 0.28), (int) (w * 0.14), (int) (h * 0.60));
            g2.fillRect((int) (w * 0.66), (int) (h * 0.40), (int) (w * 0.14), (int) (h * 0.48));
        });
    }

    public static Icon settings(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval((int) (w * 0.32), (int) (h * 0.32), (int) (w * 0.36), (int) (h * 0.36));
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                int x1 = (int) (w * 0.5 + Math.cos(angle) * (w * 0.26));
                int y1 = (int) (h * 0.5 + Math.sin(angle) * (h * 0.26));
                int x2 = (int) (w * 0.5 + Math.cos(angle) * (w * 0.44));
                int y2 = (int) (h * 0.5 + Math.sin(angle) * (h * 0.44));
                g2.drawLine(x1, y1, x2, y2);
            }
        });
    }

    public static Icon info(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval((int) (w * 0.12), (int) (h * 0.12), (int) (w * 0.76), (int) (h * 0.76));
            g2.fillOval((int) (w * 0.46), (int) (h * 0.28), (int) (w * 0.10), (int) (h * 0.10));
            g2.drawLine((int) (w * 0.50), (int) (h * 0.46), (int) (w * 0.50), (int) (h * 0.72));
        });
    }

    public static Icon lightning(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.55, h * 0.10);
            p.lineTo(w * 0.22, h * 0.55);
            p.lineTo(w * 0.48, h * 0.55);
            p.lineTo(w * 0.42, h * 0.90);
            p.lineTo(w * 0.78, h * 0.45);
            p.lineTo(w * 0.52, h * 0.45);
            p.closePath();
            g2.fill(p);
        });
    }

    public static Icon plus(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect((int) (w * 0.14), (int) (h * 0.14), (int) (w * 0.72), (int) (h * 0.72), 3, 3);
            g2.drawLine((int) (w * 0.50), (int) (h * 0.30), (int) (w * 0.50), (int) (h * 0.70));
            g2.drawLine((int) (w * 0.30), (int) (h * 0.50), (int) (w * 0.70), (int) (h * 0.50));
        });
    }

    public static Icon trash(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) (w * 0.20), (int) (h * 0.25), (int) (w * 0.80), (int) (h * 0.25));
            g2.drawLine((int) (w * 0.38), (int) (h * 0.15), (int) (w * 0.62), (int) (h * 0.15));
            g2.drawRoundRect((int) (w * 0.26), (int) (h * 0.25), (int) (w * 0.48), (int) (h * 0.62), 2, 2);
            g2.drawLine((int) (w * 0.42), (int) (h * 0.38), (int) (w * 0.42), (int) (h * 0.72));
            g2.drawLine((int) (w * 0.58), (int) (h * 0.38), (int) (w * 0.58), (int) (h * 0.72));
        });
    }

    public static Icon grid(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            int boxW = (int) (w * 0.32);
            int boxH = (int) (h * 0.32);
            g2.drawRoundRect((int) (w * 0.14), (int) (h * 0.14), boxW, boxH, 2, 2);
            g2.drawRoundRect((int) (w * 0.54), (int) (h * 0.14), boxW, boxH, 2, 2);
            g2.drawRoundRect((int) (w * 0.14), (int) (h * 0.54), boxW, boxH, 2, 2);
            g2.drawRoundRect((int) (w * 0.54), (int) (h * 0.54), boxW, boxH, 2, 2);
        });
    }

    public static Icon power(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc((int) (w * 0.18), (int) (h * 0.18), (int) (w * 0.64), (int) (h * 0.64), 50, 260);
            g2.drawLine((int) (w * 0.50), (int) (h * 0.10), (int) (w * 0.50), (int) (h * 0.48));
        });
    }

    public static Icon users(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval((int) (w * 0.32), (int) (h * 0.12), (int) (w * 0.36), (int) (h * 0.36));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.18, h * 0.85);
            p.quadTo(w * 0.20, h * 0.58, w * 0.50, h * 0.58);
            p.quadTo(w * 0.80, h * 0.58, w * 0.82, h * 0.85);
            g2.draw(p);
        });
    }

    public static Icon document(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.20, h * 0.12);
            p.lineTo(w * 0.58, h * 0.12);
            p.lineTo(w * 0.80, h * 0.34);
            p.lineTo(w * 0.80, h * 0.88);
            p.lineTo(w * 0.20, h * 0.88);
            p.closePath();
            g2.draw(p);
            g2.drawLine((int) (w * 0.58), (int) (h * 0.12), (int) (w * 0.58), (int) (h * 0.34));
            g2.drawLine((int) (w * 0.58), (int) (h * 0.34), (int) (w * 0.80), (int) (h * 0.34));
            g2.drawLine((int) (w * 0.32), (int) (h * 0.50), (int) (w * 0.68), (int) (h * 0.50));
            g2.drawLine((int) (w * 0.32), (int) (h * 0.68), (int) (w * 0.58), (int) (h * 0.68));
        });
    }

    public static Icon downloadCircle(int size) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(new Color(0x02, 0x84, 0xC7));
            g2.fillOval(0, 0, w, h);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) (w * 0.5), (int) (h * 0.26), (int) (w * 0.5), (int) (h * 0.68));
            g2.drawLine((int) (w * 0.32), (int) (h * 0.52), (int) (w * 0.5), (int) (h * 0.70));
            g2.drawLine((int) (w * 0.68), (int) (h * 0.52), (int) (w * 0.5), (int) (h * 0.70));
        });
    }

    public static Icon uploadCircle(int size) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(new Color(0x16, 0xA3, 0x4A));
            g2.fillOval(0, 0, w, h);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) (w * 0.5), (int) (h * 0.74), (int) (w * 0.5), (int) (h * 0.32));
            g2.drawLine((int) (w * 0.32), (int) (h * 0.48), (int) (w * 0.5), (int) (h * 0.30));
            g2.drawLine((int) (w * 0.68), (int) (h * 0.48), (int) (w * 0.5), (int) (h * 0.30));
        });
    }

    public static Icon trafficCircle(int size) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(new Color(0x93, 0x33, 0xEA));
            g2.fillOval(0, 0, w, h);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawOval((int) (w * 0.28), (int) (h * 0.24), (int) (w * 0.44), (int) (h * 0.20));
            g2.drawArc((int) (w * 0.28), (int) (h * 0.42), (int) (w * 0.44), (int) (h * 0.20), 180, 180);
            g2.drawArc((int) (w * 0.28), (int) (h * 0.60), (int) (w * 0.44), (int) (h * 0.20), 180, 180);
            g2.drawLine((int) (w * 0.28), (int) (h * 0.34), (int) (w * 0.28), (int) (h * 0.70));
            g2.drawLine((int) (w * 0.72), (int) (h * 0.34), (int) (w * 0.72), (int) (h * 0.70));
        });
    }

    public static Icon edit(int size, Color color) {
        return new VectorIcon(size, size, (g2, w, h) -> {
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            p.moveTo(w * 0.70, h * 0.16);
            p.lineTo(w * 0.84, h * 0.30);
            p.lineTo(w * 0.35, h * 0.80);
            p.lineTo(w * 0.18, h * 0.82);
            p.lineTo(w * 0.20, h * 0.65);
            p.closePath();
            g2.draw(p);
        });
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D g2, int width, int height);
    }

    private static final class VectorIcon implements Icon {
        private final int width;
        private final int height;
        private final Painter painter;

        VectorIcon(int width, int height, Painter painter) {
            this.width = width;
            this.height = height;
            this.painter = painter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (c != null && !c.isEnabled()) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.40f));
                }
                painter.paint(g2, width, height);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }
}
