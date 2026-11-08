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
}
