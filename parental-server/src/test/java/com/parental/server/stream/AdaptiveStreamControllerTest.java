package com.parental.server.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test {@link AdaptiveStreamController} với thời gian giả lập (không
 * sleep, không phụ thuộc Swing/network).
 */
class AdaptiveStreamControllerTest {

    private static void feed(AdaptiveStreamController controller, long fromMs,
                             long toMs, long stepMs, long charsPerFrame) {
        for (long t = fromMs; t <= toMs; t += stepMs) {
            controller.onFrame(t, charsPerFrame, t - 100);
        }
    }

    @Test
    @DisplayName("Mặc định nằm trong biên; Settings tự clamp width/quality/fps")
    void defaultsAreWithinBounds() {
        AdaptiveStreamController.Settings defaults =
                AdaptiveStreamController.Settings.defaults();
        assertEquals(960, defaults.width());
        assertEquals(540, defaults.height());
        assertEquals(0.7, defaults.quality(), 1e-9);
        assertEquals(5, defaults.fps());

        AdaptiveStreamController.Settings clamped =
                AdaptiveStreamController.Settings.of(99_999, 5.0, 999);
        assertEquals(AdaptiveStreamController.MAX_WIDTH, clamped.width());
        assertEquals(AdaptiveStreamController.MAX_QUALITY, clamped.quality(), 1e-9);
        assertEquals(AdaptiveStreamController.MAX_FPS, clamped.fps());

        AdaptiveStreamController.Settings tiny =
                AdaptiveStreamController.Settings.of(1, -1, -5);
        assertEquals(AdaptiveStreamController.MIN_WIDTH, tiny.width());
        assertEquals(AdaptiveStreamController.MIN_QUALITY, tiny.quality(), 1e-9);
        assertEquals(AdaptiveStreamController.MIN_FPS, tiny.fps());
    }

    @Test
    @DisplayName("Chưa đủ mẫu / cửa sổ quá ngắn → không đổi cấu hình")
    void noSamplesMeansNoChange() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        AdaptiveStreamController.Decision decision = controller.evaluate(10_000);
        assertFalse(decision.changed());
        assertEquals(AdaptiveStreamController.Settings.defaults(), decision.settings());

        controller.onFrame(0, 10_000_000, 0);
        controller.onFrame(100, 10_000_000, 0);
        assertFalse(controller.evaluate(500).changed(), "Mới 2 mẫu, chưa đủ 3");
    }

    @Test
    @DisplayName("Tải cao liên tiếp: đủ 2 lần xấu mới giảm quality; cooldown chặn giảm tiếp")
    void degradesAfterTwoBadEvaluationsAndHonorsCooldown() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        feed(controller, 0, 1_200, 200, 800_000); // ~5.6M chars trong 1.2s

        AdaptiveStreamController.Decision first = controller.evaluate(1_500);
        assertFalse(first.changed(), "Mới 1 lần xấu — hysteresis chưa cho đổi");
        assertEquals(0.7, controller.currentSettings().quality(), 1e-9);

        feed(controller, 1_400, 2_400, 200, 800_000);
        AdaptiveStreamController.Decision second = controller.evaluate(2_600);
        assertTrue(second.changed(), "Lần xấu thứ 2 phải giảm tải");
        assertEquals(0.6, second.settings().quality(), 1e-9);
        assertEquals(960, second.settings().width());

        feed(controller, 2_600, 3_600, 200, 800_000);
        AdaptiveStreamController.Decision duringCooldown = controller.evaluate(3_600);
        assertFalse(duringCooldown.changed(), "Đang cooldown 4s — không đổi tiếp");
    }

    @Test
    @DisplayName("Tải thấp + latency thấp liên tiếp: tăng FPS sau 3 lần tốt, tôn trọng cooldown")
    void upgradesFpsAfterThreeGoodEvaluations() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        feed(controller, 0, 1_200, 200, 100_000);
        assertFalse(controller.evaluate(1_500).changed());
        feed(controller, 1_400, 2_400, 200, 100_000);
        assertFalse(controller.evaluate(2_600).changed());
        feed(controller, 2_600, 3_600, 200, 100_000);

        AdaptiveStreamController.Decision decision = controller.evaluate(3_700);
        assertTrue(decision.changed());
        assertEquals(6, decision.settings().fps(), "FPS tăng trước khi tăng quality/width");
        assertEquals(0.7, decision.settings().quality(), 1e-9);

        feed(controller, 3_800, 5_000, 200, 100_000);
        assertFalse(controller.evaluate(5_000).changed(), "Cooldown 4s sau thay đổi");
    }

    @Test
    @DisplayName("Latency cao một mình cũng là tín hiệu xấu")
    void highLatencyTriggersDegrade() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        for (long t = 0; t <= 2_400; t += 200) {
            controller.onFrame(t, 50_000, t - 1_500); // latency 1500ms
        }
        assertFalse(controller.evaluate(1_500).changed());
        for (long t = 1_600; t <= 3_000; t += 200) {
            controller.onFrame(t, 50_000, t - 1_500);
        }
        assertTrue(controller.evaluate(3_100).changed());
    }

    @Test
    @DisplayName("Không có timestamp latency (0/lệch đồng hồ) không làm crash hay giảm oan")
    void missingOrFutureTimestampIsIgnored() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        for (long t = 0; t <= 2_400; t += 200) {
            controller.onFrame(t, 50_000, 0); // client không gửi tsMs
        }
        assertFalse(controller.evaluate(1_500).changed());
        assertFalse(controller.evaluate(2_600).changed());

        controller.reset();
        for (long t = 0; t <= 2_400; t += 200) {
            controller.onFrame(t, 50_000, t + 10_000); // tsMs ở tương lai
        }
        assertFalse(controller.evaluate(2_600).changed());
    }

    @Test
    @DisplayName("Giảm/tăng lặp luôn kẹp trong biên, không vượt MIN/MAX")
    void repeatedStepsRespectBounds() {
        AdaptiveStreamController.Settings settings =
                AdaptiveStreamController.Settings.defaults();
        for (int i = 0; i < 30; i++) {
            settings = AdaptiveStreamController.degrade(settings);
        }
        assertEquals(AdaptiveStreamController.MIN_WIDTH, settings.width());
        assertEquals(AdaptiveStreamController.MIN_QUALITY, settings.quality(), 1e-9);
        assertEquals(AdaptiveStreamController.MIN_FPS, settings.fps());

        for (int i = 0; i < 60; i++) {
            settings = AdaptiveStreamController.upgrade(settings);
        }
        assertEquals(AdaptiveStreamController.MAX_WIDTH, settings.width());
        assertEquals(AdaptiveStreamController.MAX_QUALITY, settings.quality(), 1e-9);
        assertEquals(AdaptiveStreamController.MAX_FPS, settings.fps());
    }

    @Test
    @DisplayName("reset() về mặc định và xóa mẫu/cooldown")
    void resetRestoresDefaults() {
        AdaptiveStreamController controller = new AdaptiveStreamController();
        feed(controller, 0, 3_000, 200, 1_000_000);
        controller.evaluate(1_500);
        controller.evaluate(2_600);
        assertTrue(controller.currentSettings().width() < 960
                || controller.currentSettings().quality() < 0.7);

        controller.reset();

        assertEquals(AdaptiveStreamController.Settings.defaults(),
                controller.currentSettings());
        assertFalse(controller.evaluate(10_000).changed(), "Sau reset chưa có mẫu");
    }
}
