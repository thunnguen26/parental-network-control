package com.parental.server.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JPanel;

/**
 * Panel vẽ đồ thị đường sóng mềm mại (sparkline wave) cho các thẻ chỉ số
 * và trạng thái hệ thống, không dùng thư viện ngoài.
 */
public class SparklineWavePanel extends JPanel {

    private final Color strokeColor;
    private final Color gradientStartColor;
    private final float[] samplePoints;

    public SparklineWavePanel(Color strokeColor, float[] samplePoints) {
        this.strokeColor = strokeColor;
        this.gradientStartColor = new Color(strokeColor.getRed(), strokeColor.getGreen(),
                strokeColor.getBlue(), 45);
        this.samplePoints = samplePoints != null && samplePoints.length >= 4
                ? samplePoints.clone()
                : new float[] {0.3f, 0.6f, 0.2f, 0.8f, 0.4f, 0.7f, 0.5f, 0.9f};
        setOpaque(false);
        setPreferredSize(new Dimension(100, 32));
        setMinimumSize(new Dimension(60, 24));
    }

    public static SparklineWavePanel blueDownloadWave() {
        return new SparklineWavePanel(new Color(0x3B, 0x82, 0xF6),
                new float[] {0.2f, 0.5f, 0.3f, 0.8f, 0.4f, 0.6f, 0.3f, 0.7f, 0.9f});
    }

    public static SparklineWavePanel greenUploadWave() {
        return new SparklineWavePanel(new Color(0x10, 0xB9, 0x81),
                new float[] {0.4f, 0.2f, 0.7f, 0.5f, 0.9f, 0.6f, 0.8f, 0.4f, 0.6f});
    }

    public static SparklineWavePanel purpleTrafficWave() {
        return new SparklineWavePanel(new Color(0x8B, 0x5C, 0xF6),
                new float[] {0.3f, 0.7f, 0.4f, 0.9f, 0.5f, 0.8f, 0.4f, 0.9f, 0.6f});
    }

    public static SparklineWavePanel systemStatusWave() {
        return new SparklineWavePanel(new Color(0x10, 0xB9, 0x81),
                new float[] {0.3f, 0.6f, 0.4f, 0.8f, 0.5f, 0.7f, 0.4f, 0.6f});
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 4 || h <= 4) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int n = samplePoints.length;
            double step = (double) w / (n - 1);
            double padTop = 4.0;
            double availH = h - 8.0;

            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = i * step;
                ys[i] = h - (padTop + samplePoints[i] * availH);
            }

            Path2D.Double linePath = new Path2D.Double();
            linePath.moveTo(xs[0], ys[0]);
            for (int i = 0; i < n - 1; i++) {
                double x1 = xs[i];
                double y1 = ys[i];
                double x2 = xs[i + 1];
                double y2 = ys[i + 1];
                double cx1 = x1 + (x2 - x1) / 2.0;
                double cy1 = y1;
                double cx2 = x1 + (x2 - x1) / 2.0;
                double cy2 = y2;
                linePath.curveTo(cx1, cy1, cx2, cy2, x2, y2);
            }

            Path2D.Double fillPath = new Path2D.Double(linePath);
            fillPath.lineTo(w, h);
            fillPath.lineTo(0, h);
            fillPath.closePath();

            GradientPaint gradient = new GradientPaint(
                    0, 0, gradientStartColor,
                    0, h, new Color(strokeColor.getRed(), strokeColor.getGreen(), strokeColor.getBlue(), 0));
            g2.setPaint(gradient);
            g2.fill(fillPath);

            g2.setColor(strokeColor);
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(linePath);
        } finally {
            g2.dispose();
        }
    }
}
