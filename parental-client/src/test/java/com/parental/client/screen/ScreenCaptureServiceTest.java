package com.parental.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScreenCaptureServiceTest {

    @Test
    @DisplayName("Virtual desktop bounds include monitors left of and above the primary display")
    void unionsVirtualScreenBounds() {
        Rectangle bounds = ScreenCaptureService.unionScreenBounds(List.of(
                new Rectangle(0, 0, 1920, 1080),
                new Rectangle(-1280, -200, 1280, 1024)));

        assertEquals(new Rectangle(-1280, -200, 3200, 1280), bounds);
    }

    @Test
    @DisplayName("Capture output fits requested bounds without cropping or changing aspect ratio")
    void fitsFullScreenImageWithinRequestedBounds() {
        Dimension target = ScreenCaptureService.fitWithin(2560, 1080, 960, 540);

        assertEquals(960, target.width);
        assertEquals(405, target.height);
    }

    @Test
    @DisplayName("Output is not upscaled when the desktop is smaller than the requested frame")
    void doesNotUpscaleSmallDesktop() {
        Dimension target = ScreenCaptureService.fitWithin(640, 480, 960, 540);

        assertEquals(640, target.width);
        assertEquals(480, target.height);
    }

    @Test
    @DisplayName("JPEG quality is applied and generated output remains decodable")
    void appliesJpegCompressionQuality() throws Exception {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(41);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt(1 << 24));
            }
        }

        byte[] lowQuality = ScreenCaptureService.toJpeg(image, 0.1);
        byte[] highQuality = ScreenCaptureService.toJpeg(image, 1.0);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(highQuality));

        assertTrue(lowQuality.length < highQuality.length);
        assertEquals(image.getWidth(), decoded.getWidth());
        assertEquals(image.getHeight(), decoded.getHeight());
    }

    @Test
    @DisplayName("Empty display geometry is rejected explicitly")
    void rejectsEmptyScreenGeometry() {
        assertThrows(IllegalStateException.class,
                () -> ScreenCaptureService.unionScreenBounds(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenCaptureService.fitWithin(0, 100, 100, 100));
    }
}
