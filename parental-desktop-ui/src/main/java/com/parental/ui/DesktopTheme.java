package com.parental.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Collections;

import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;

import com.formdev.flatlaf.FlatLightLaf;

/** Installs the shared FlatLaf palette and component defaults for desktop screens. */
public final class DesktopTheme {

    public static final Color NAVY = new Color(0x16, 0x2B, 0x4D);
    public static final Color NAVY_LIGHT = new Color(0x20, 0x3D, 0x67);
    public static final Color PRIMARY = new Color(0x1D, 0x64, 0xD8);
    public static final Color CANVAS = new Color(0xF1, 0xF5, 0xFB);
    public static final Color CARD_BORDER = new Color(0xD8, 0xE1, 0xED);
    public static final Color TEXT_PRIMARY = new Color(0x1A, 0x2C, 0x48);
    public static final Color SUCCESS = new Color(0x19, 0x82, 0x54);
    public static final Color DANGER = new Color(0xB4, 0x23, 0x18);

    private DesktopTheme() {
    }

    /** Must be called on the EDT before creating any Swing window. */
    public static void install() {
        if (!FlatLightLaf.setup()) {
            throw new IllegalStateException("Could not install FlatLaf Light look and feel");
        }

        Font baseFont = new Font("Segoe UI", Font.PLAIN, 13);
        UIManager.put("defaultFont", baseFont);
        UIManager.put("Component.arc", 16);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("ComboBox.arc", 12);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabHeight", 36);
        UIManager.put("Table.rowHeight", 30);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.alternateRowColor", new Color(0xF6, 0xF8, 0xFC));
        UIManager.put("Table.selectionBackground", new Color(0xDF, 0xEC, 0xFF));
        UIManager.put("Table.selectionForeground", TEXT_PRIMARY);
        UIManager.put("List.selectionBackground", new Color(0xDF, 0xEC, 0xFF));
        UIManager.put("List.selectionForeground", TEXT_PRIMARY);
        UIManager.put("ProgressBar.foreground", PRIMARY);
        UIManager.put("Component.focusColor", PRIMARY);
        UIManager.put("Button.default.background", PRIMARY);

        for (Object key : Collections.list(UIManager.getLookAndFeelDefaults().keys())) {
            Font current = UIManager.getLookAndFeelDefaults().getFont(key);
            if (current != null) {
                UIManager.put(key, baseFont.deriveFont(current.getStyle(),
                        current.getSize2D()));
            }
        }
    }

    public static Border roundedBorder(Color fill, Color outline, int arc, Insets padding) {
        return new RoundedPanelBorder(fill, outline, TEXT_PRIMARY, arc, padding, null);
    }

    public static Border roundedTitledBorder(String title, Color fill, Color outline,
                                             int arc, Insets padding) {
        return new RoundedPanelBorder(fill, outline, TEXT_PRIMARY, arc, padding, title);
    }

    public static void roundPanel(JPanel panel, String title, int arc) {
        Color fill = panel.getBackground();
        Insets padding = panel.getBorder() == null
                ? new Insets(10, 12, 10, 12)
                : panel.getBorder().getBorderInsets(panel);
        panel.setOpaque(false);
        panel.setBorder(roundedTitledBorder(title, fill, CARD_BORDER, arc, padding));
    }

    private static final class RoundedPanelBorder extends AbstractBorder {
        private final Color fill;
        private final Color outline;
        private final Color titleColor;
        private final int arc;
        private final Insets padding;
        private final String title;

        private RoundedPanelBorder(Color fill, Color outline, Color titleColor,
                               int arc, Insets padding, String title) {
        this.fill = fill;
        this.outline = outline;
        this.titleColor = titleColor;
        this.arc = arc;
        this.padding = (Insets) padding.clone();
        this.title = title;
        }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
        insets.top = Math.max(padding.top, title == null ? 0 : 25);
        insets.left = padding.left;
        insets.bottom = padding.bottom;
        insets.right = padding.right;
        return insets;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics,
                            int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(x, y, width - 1, height - 1, arc, arc);
            g.setColor(outline);
            g.drawRoundRect(x, y, width - 1, height - 1, arc, arc);

            if (title != null && !title.isBlank()) {
                Font titleFont = component.getFont().deriveFont(Font.BOLD, 11f);
                g.setFont(titleFont);
                FontMetrics metrics = g.getFontMetrics();
                int titleX = x + 14;
                int titleY = y + 16;
                g.setColor(fill);
                g.fillRect(titleX - 4, titleY - metrics.getAscent(),
                        metrics.stringWidth(title) + 8, metrics.getHeight());
                g.setColor(titleColor);
                g.drawString(title, titleX, titleY);
            }
        } finally {
            g.dispose();
        }
        }
    }
}
