package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test logic định dạng GUI (Task 5.1) — pure function, an toàn headless
 * (không đụng Swing/AWT component nào).
 */
class GuiFormatTest {

    // ------------------------------------------------------------------
    // formatBytes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatBytes: 512 → '512 B'; 0 → '0 B'")
    void bytesSmall() {
        assertEquals("512 B", GuiFormat.formatBytes(512));
        assertEquals("0 B", GuiFormat.formatBytes(0));
        assertEquals("1023 B", GuiFormat.formatBytes(1023));
    }

    @Test
    @DisplayName("formatBytes: 1024 → '1.0 KB'; 1_572_864 → '1.5 MB'; 1 GB → '1.0 GB'")
    void bytesScaled() {
        assertEquals("1.0 KB", GuiFormat.formatBytes(1024));
        assertEquals("2.0 KB", GuiFormat.formatBytes(2048));
        assertEquals("1.5 MB", GuiFormat.formatBytes(1_572_864));
        assertEquals("1.0 GB", GuiFormat.formatBytes(1_073_741_824L));
        assertEquals("1.0 TB", GuiFormat.formatBytes(1_099_511_627_776L));
    }

    @Test
    @DisplayName("formatBytes: giá trị âm → 'n/a'")
    void bytesNegative() {
        assertEquals("n/a", GuiFormat.formatBytes(-1));
    }

    // ------------------------------------------------------------------
    // formatSpeedBps
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatSpeedBps: 1536 → '1.5 KB/s'; 0 → '0 B/s'; âm → 'n/a'")
    void speed() {
        assertEquals("1.5 KB/s", GuiFormat.formatSpeedBps(1536));
        assertEquals("0 B/s", GuiFormat.formatSpeedBps(0));
        assertEquals("2.5 MB/s", GuiFormat.formatSpeedBps(2_621_440));
        assertEquals("n/a", GuiFormat.formatSpeedBps(-5));
    }

    // ------------------------------------------------------------------
    // formatDuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("formatDuration: 3661 → '1h 1m 1s'; 600 → '10m 0s'; 45 → '45s'; 0 → '0s'")
    void duration() {
        assertEquals("1h 1m 1s", GuiFormat.formatDuration(3661));
        assertEquals("10m 0s", GuiFormat.formatDuration(600));
        assertEquals("45s", GuiFormat.formatDuration(45));
        assertEquals("0s", GuiFormat.formatDuration(0));
        assertEquals("2h 0m 0s", GuiFormat.formatDuration(7200));
        assertEquals("n/a", GuiFormat.formatDuration(-10));
    }

    // ------------------------------------------------------------------
    // clampPercent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clampPercent: 50/100 → 50; vượt hạn mức → 100; limit ≤ 0 → 0; âm → 0")
    void clampPercent() {
        assertEquals(50, GuiFormat.clampPercent(50, 100));
        assertEquals(25, GuiFormat.clampPercent(250, 1000));
        assertEquals(100, GuiFormat.clampPercent(150, 100)); // vượt hạn mức kẹp 100
        assertEquals(0, GuiFormat.clampPercent(100, 0));
        assertEquals(0, GuiFormat.clampPercent(100, -5));
        assertEquals(0, GuiFormat.clampPercent(-10, 100));
        assertEquals(100, GuiFormat.clampPercent(100, 100));
    }
}
