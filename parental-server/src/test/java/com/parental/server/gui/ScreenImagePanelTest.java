package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

class ScreenImagePanelTest {

    @Test
    void fitsWidescreenImageWithoutCropping() {
        Rectangle bounds = ScreenImagePanel.fitBounds(1920, 1080, 800, 600);

        assertEquals(new Rectangle(0, 75, 800, 450), bounds);
    }

    @Test
    void fitsImageIntoNarrowAreaAndCentersIt() {
        Rectangle bounds = ScreenImagePanel.fitBounds(1920, 1080, 600, 400);

        assertEquals(new Rectangle(0, 31, 600, 337), bounds);
    }

    @Test
    void invalidDimensionsReturnEmptyBounds() {
        assertTrue(ScreenImagePanel.fitBounds(1920, 1080, 0, 400).isEmpty());
        assertTrue(ScreenImagePanel.fitBounds(0, 1080, 600, 400).isEmpty());
    }

    @Test
    void mapsPanelPointToFramePixelForRemoteInput() {
        // 1920x1080 vừa 800x600 → ảnh 800x450 tại y=75, scale 2.4
        java.awt.Point center = ScreenImagePanel.toImagePoint(400, 300,
                1920, 1080, 800, 600);
        assertEquals(new java.awt.Point(960, 540), center);

        java.awt.Point corner = ScreenImagePanel.toImagePoint(0, 75,
                1920, 1080, 800, 600);
        assertEquals(new java.awt.Point(0, 0), corner);
    }

    @Test
    void panelPointOutsideImageIsIgnored() {
        // y=10 nằm trong viền đen letterbox phía trên
        org.junit.jupiter.api.Assertions.assertNull(
                ScreenImagePanel.toImagePoint(400, 10, 1920, 1080, 800, 600));
        org.junit.jupiter.api.Assertions.assertNull(
                ScreenImagePanel.toImagePoint(400, 300, 0, 1080, 800, 600));
    }

    @Test
    void mouseReleaseOutsideImageStillMapsToSafeFramePoint() {
        assertEquals(new java.awt.Point(0, 0),
                ScreenImagePanel.toImagePointForMouseButton(
                        400, 10, 1920, 1080, 800, 600, false));
        org.junit.jupiter.api.Assertions.assertNull(
                ScreenImagePanel.toImagePointForMouseButton(
                        400, 10, 1920, 1080, 800, 600, true));
    }
}
