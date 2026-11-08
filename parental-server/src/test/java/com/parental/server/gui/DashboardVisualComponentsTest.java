package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;

class DashboardVisualComponentsTest {

    @Test
    void sparklineWavePanelRendersSmoothlyInHeadless() {
        SparklineWavePanel blueWave = SparklineWavePanel.blueDownloadWave();
        SparklineWavePanel greenWave = SparklineWavePanel.greenUploadWave();
        SparklineWavePanel purpleWave = SparklineWavePanel.purpleTrafficWave();
        SparklineWavePanel statusWave = SparklineWavePanel.systemStatusWave();

        assertNotNull(blueWave);
        assertNotNull(greenWave);
        assertNotNull(purpleWave);
        assertNotNull(statusWave);

        BufferedImage img = new BufferedImage(120, 36, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            blueWave.setSize(120, 36);
            assertDoesNotThrow(() -> blueWave.paintComponent(g2));

            boolean hasColoredPixel = false;
            for (int x = 0; x < 120; x++) {
                for (int y = 0; y < 36; y++) {
                    if ((img.getRGB(x, y) >>> 24) > 0) {
                        hasColoredPixel = true;
                        break;
                    }
                }
            }
            assertTrue(hasColoredPixel, "SparklineWavePanel must paint visible wave pixels");
        } finally {
            g2.dispose();
        }
    }

    @Test
    void windowsLogoPanelRendersTilesInHeadless() {
        WindowsLogoPanel winPanel = new WindowsLogoPanel();
        assertNotNull(winPanel);

        BufferedImage img = new BufferedImage(110, 110, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            winPanel.setSize(110, 110);
            assertDoesNotThrow(() -> winPanel.paintComponent(g2));

            boolean hasBlueTilePixel = false;
            int winBlue = new Color(0x00, 0x78, 0xD4).getRGB();
            for (int x = 0; x < 110; x++) {
                for (int y = 0; y < 110; y++) {
                    if (img.getRGB(x, y) == winBlue) {
                        hasBlueTilePixel = true;
                        break;
                    }
                }
            }
            assertTrue(hasBlueTilePixel, "WindowsLogoPanel must paint Windows blue tile pixels");
        } finally {
            g2.dispose();
        }
    }

    @Test
    void headerBrandIconRendersShieldInHeadless() {
        HeaderBrandIcon icon = new HeaderBrandIcon();
        assertNotNull(icon);

        BufferedImage img = new BufferedImage(44, 44, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            icon.setSize(44, 44);
            assertDoesNotThrow(() -> icon.paintComponent(g2));

            boolean hasVisiblePixel = false;
            for (int x = 0; x < 44; x++) {
                for (int y = 0; y < 44; y++) {
                    if ((img.getRGB(x, y) >>> 24) > 0) {
                        hasVisiblePixel = true;
                        break;
                    }
                }
            }
            assertTrue(hasVisiblePixel, "HeaderBrandIcon must paint visible shield pixels");
        } finally {
            g2.dispose();
        }
    }

    @Test
    void dashboardVectorIconsRenderWithoutTofuInHeadless() {
        JPanel dummy = new JPanel();
        Icon[] icons = new Icon[] {
                DashboardIcons.home(16, Color.WHITE),
                DashboardIcons.monitor(16, Color.BLUE),
                DashboardIcons.shield(16, Color.BLUE),
                DashboardIcons.stats(16, Color.GRAY),
                DashboardIcons.settings(16, Color.GRAY),
                DashboardIcons.info(16, Color.BLUE),
                DashboardIcons.lightning(14, Color.YELLOW),
                DashboardIcons.plus(15, Color.WHITE),
                DashboardIcons.trash(15, Color.WHITE),
                DashboardIcons.grid(15, Color.WHITE),
                DashboardIcons.power(15, Color.WHITE),
                DashboardIcons.users(16, Color.BLUE),
                DashboardIcons.document(16, Color.CYAN),
                DashboardIcons.downloadCircle(22),
                DashboardIcons.uploadCircle(22),
                DashboardIcons.trafficCircle(22),
                DashboardIcons.edit(14, Color.BLACK)
        };

        for (Icon icon : icons) {
            assertNotNull(icon);
            assertTrue(icon.getIconWidth() > 0);
            assertTrue(icon.getIconHeight() > 0);

            BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            try {
                assertDoesNotThrow(() -> icon.paintIcon(dummy, g2, 0, 0));
            } finally {
                g2.dispose();
            }
            boolean hasVisiblePixels = false;
            for (int x = 0; x < img.getWidth() && !hasVisiblePixels; x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    if ((img.getRGB(x, y) >>> 24) > 0) {
                        hasVisiblePixels = true;
                        break;
                    }
                }
            }
            assertTrue(hasVisiblePixels, "Dashboard vector icon must paint visible pixels");
        }
    }
}
