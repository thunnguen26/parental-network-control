package com.parental.server.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.JPanel;

/**
 * Biểu đồ cột thời gian sử dụng theo ngày (Tính năng 3.1) — Swing thuần, không
 * phụ thuộc thư viện chart ngoài. Dữ liệu rỗng/toàn 0 hiển thị thông báo thay
 * vì vẽ cột rỗng gây hiểu nhầm.
 */
public final class UsageBarChartPanel extends JPanel {

    private static final int PADDING_LEFT = 62;
    private static final int PADDING_RIGHT = 18;
    private static final int PADDING_TOP = 28;
    private static final int PADDING_BOTTOM = 46;
    private static final Color BAR_COLOR = new Color(0x2B, 0x6C, 0xB0);
    private static final Color BAR_COLOR_TODAY = new Color(0x0E, 0x9F, 0x6E);
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("dd/MM");

    private List<UsageStatistics.DailyPoint> points = List.of();

    public UsageBarChartPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(760, 330));
    }

    /** Cập nhật dữ liệu (gọi trên EDT) — null được coi là rỗng. */
    public void setData(List<UsageStatistics.DailyPoint> points) {
        this.points = points == null ? List.of() : List.copyOf(points);
        repaint();
    }

    /** Dữ liệu hiện tại (test không cần GUI có thể đọc getter này). */
    public List<UsageStatistics.DailyPoint> getData() {
        return points;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int chartWidth = Math.max(1, width - PADDING_LEFT - PADDING_RIGHT);
            int chartHeight = Math.max(1, height - PADDING_TOP - PADDING_BOTTOM);

            long max = 0;
            for (UsageStatistics.DailyPoint point : points) {
                max = Math.max(max, point.seconds());
            }
            if (points.isEmpty() || max <= 0) {
                g.setColor(new Color(0x6B, 0x72, 0x80));
                g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                String message = points.isEmpty()
                        ? "Chưa có dữ liệu trong khoảng này"
                        : "Chưa ghi nhận thời gian sử dụng trong khoảng này";
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(message,
                        (width - metrics.stringWidth(message)) / 2,
                        PADDING_TOP + chartHeight / 2);
                return;
            }

            // Trục + nhãn max (làm tròn lên giờ chẵn để dễ đọc).
            long axisMax = Math.max(60, ((max + 3599) / 3600) * 3600);
            g.setColor(new Color(0xE5, 0xE7, 0xEB));
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i <= 4; i++) {
                int y = PADDING_TOP + chartHeight - (chartHeight * i / 4);
                g.drawLine(PADDING_LEFT, y, PADDING_LEFT + chartWidth, y);
                g.setColor(new Color(0x6B, 0x72, 0x80));
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                String label = GuiFormat.formatDuration(axisMax * i / 4);
                g.drawString(label, 6, y + 4);
                g.setColor(new Color(0xE5, 0xE7, 0xEB));
            }

            int count = points.size();
            double slot = (double) chartWidth / count;
            int barWidth = Math.max(2, (int) Math.floor(slot * 0.66));
            int labelEvery = Math.max(1, (int) Math.ceil(count / 10.0));
            for (int i = 0; i < count; i++) {
                UsageStatistics.DailyPoint point = points.get(i);
                int barHeight = (int) Math.round(
                        chartHeight * Math.min(1.0, point.seconds() / (double) axisMax));
                int x = PADDING_LEFT + (int) Math.round(i * slot + (slot - barWidth) / 2.0);
                int y = PADDING_TOP + chartHeight - barHeight;
                g.setColor(point.date().equals(java.time.LocalDate.now())
                        ? BAR_COLOR_TODAY : BAR_COLOR);
                g.fillRoundRect(x, y, barWidth, Math.max(1, barHeight), 4, 4);

                if (i % labelEvery == 0 || i == count - 1) {
                    g.setColor(new Color(0x6B, 0x72, 0x80));
                    g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                    String label = point.date().format(LABEL_FORMAT);
                    FontMetrics metrics = g.getFontMetrics();
                    g.drawString(label, x + (barWidth - metrics.stringWidth(label)) / 2,
                            PADDING_TOP + chartHeight + 16);
                }
            }
            g.setColor(new Color(0x37, 0x41, 0x51));
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            g.drawString("Thời gian sử dụng / ngày (tối đa "
                    + GuiFormat.formatDuration(max) + ")", PADDING_LEFT, 18);
        } finally {
            g.dispose();
        }
    }
}
