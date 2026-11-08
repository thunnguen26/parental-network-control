package com.parental.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import org.junit.jupiter.api.Test;

class DesktopThemeTest {

    @Test
    void installsSharedFlatLightDefaults() throws Exception {
        SwingUtilities.invokeAndWait(DesktopTheme::install);

        assertInstanceOf(com.formdev.flatlaf.FlatLightLaf.class,
                UIManager.getLookAndFeel());
        assertEquals(16, UIManager.getInt("Component.arc"));
        assertEquals(14, UIManager.getInt("Button.arc"));
        assertEquals(30, UIManager.getInt("Table.rowHeight"));
        assertEquals(DesktopTheme.PRIMARY, UIManager.getColor("Component.focusColor"));
    }

    @Test
    void roundedBorderKeepsItsCornersTransparent() {
        JPanel panel = new JPanel();
        Border border = DesktopTheme.roundedBorder(Color.WHITE,
                DesktopTheme.CARD_BORDER, 18, new Insets(8, 8, 8, 8));
        BufferedImage image = new BufferedImage(80, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            border.paintBorder(panel, graphics, 0, 0, 80, 48);
        } finally {
            graphics.dispose();
        }

        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(Color.WHITE.getRGB(), image.getRGB(40, 24));
        assertEquals(8, border.getBorderInsets(panel).top);
    }

    @Test
    void roundedTitleAddsClearanceAboveCardContent() {
        Border border = DesktopTheme.roundedTitledBorder("POLICY", Color.WHITE,
                DesktopTheme.CARD_BORDER, 18, new Insets(8, 10, 8, 10));

        assertEquals(25, border.getBorderInsets(new JPanel()).top);
    }
}
