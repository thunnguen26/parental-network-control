package com.parental.server.stream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Điều chỉnh thích ứng FPS/JPEG cho Live Screen (Tính năng 3.2) — logic thuần,
 * không đụng socket/Swing, unit test được.
 *
 * <p><b>Cách đo:</b> mỗi frame đến được ghi nhận {@code encodedChars} (độ dài
 * chuỗi Base64 — xấp xỉ byte JPEG × 4/3) và latency {@code arrival - tsMs}.
 * Cửa sổ trượt {@value #WINDOW_MS} ms; chỉ ra quyết định khi có ≥
 * {@value #MIN_SAMPLES} mẫu và đã qua {@value #EVALUATE_MIN_INTERVAL_MS} ms kể
 * từ lần đánh giá trước.</p>
 *
 * <p><b>Chống dao động (hysteresis):</b> cần {@value #BAD_STREAK_REQUIRED} lần
 * đánh giá "xấu" liên tiếp mới giảm, {@value #GOOD_STREAK_REQUIRED} lần "tốt"
 * liên tiếp mới tăng; sau mỗi thay đổi nghỉ {@value #COOLDOWN_MS} ms. Băng
 * thông/latency ở giữa 2 ngưỡng được coi là ổn định → không đổi.</p>
 *
 * <p><b>Biên:</b> FPS 1..10, quality 0.35..0.85, width 640..1280 (height theo
 * 16:9). Khi giảm: quality → width → fps; khi tăng: fps → quality → width.
 * Không bao giờ vượt biên; dừng stream gọi {@link #reset()}.</p>
 */
public final class AdaptiveStreamController {

    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 10;
    public static final double MIN_QUALITY = 0.35;
    public static final double MAX_QUALITY = 0.85;
    public static final int MIN_WIDTH = 640;
    public static final int MAX_WIDTH = 1280;
    public static final int DEFAULT_WIDTH = 960;
    public static final double DEFAULT_QUALITY = 0.7;
    public static final int DEFAULT_FPS = 5;

    /** Cửa sổ đo (ms). */
    public static final long WINDOW_MS = 3_000;
    /** Không đánh giá quá dày (ms). */
    public static final long EVALUATE_MIN_INTERVAL_MS = 1_000;
    /** Nghỉ sau mỗi thay đổi (ms) — chống dao động. */
    public static final long COOLDOWN_MS = 4_000;
    public static final int MIN_SAMPLES = 3;
    public static final int BAD_STREAK_REQUIRED = 2;
    public static final int GOOD_STREAK_REQUIRED = 3;
    /** Ngưỡng "xấu": > 2.0M ký tự Base64/s (~1.5 MB/s) hoặc latency > 800ms. */
    public static final long HIGH_ENCODED_CHARS_PER_SECOND = 2_000_000L;
    public static final long HIGH_LATENCY_MS = 800;
    /** Ngưỡng "tốt": < 600K ký tự Base64/s (~450 KB/s) và latency < 300ms. */
    public static final long LOW_ENCODED_CHARS_PER_SECOND = 600_000L;
    public static final long LOW_LATENCY_MS = 300;

    /** Cấu hình stream hợp lệ (đã clamp trong constructor). */
    public record Settings(int width, int height, double quality, int fps) {

        public Settings {
            width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
            width = Math.max(MIN_WIDTH, Math.round(width / 16f) * 16);
            height = Math.max(1, (int) Math.round(width * 9.0 / 16.0));
            if (!Double.isFinite(quality)) {
                quality = DEFAULT_QUALITY;
            }
            quality = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, quality));
            fps = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
        }

        public static Settings defaults() {
            return new Settings(DEFAULT_WIDTH, 0, DEFAULT_QUALITY, DEFAULT_FPS);
        }

        public static Settings of(int width, double quality, int fps) {
            return new Settings(width, 0, quality, fps);
        }

        public String describe() {
            return String.format(Locale.ROOT, "%dx%d q=%.2f fps=%d",
                    width, height, quality, fps);
        }
    }

    /** Kết quả một lần đánh giá. */
    public record Decision(Settings settings, boolean changed, String reason) {

        static Decision unchanged(Settings settings, String reason) {
            return new Decision(settings, false, reason);
        }
    }

    private record Sample(long atMs, long encodedChars, long latencyMs) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private Settings current = Settings.defaults();
    private long lastEvaluateMs = Long.MIN_VALUE;
    private long cooldownUntilMs = Long.MIN_VALUE;
    private long lastArrivalMs = Long.MIN_VALUE;
    private int badStreak;
    private int goodStreak;

    /** Ghi nhận 1 frame mới (bỏ qua frame trùng đã xử lý ở tầng gọi). */
    public synchronized void onFrame(long nowMs, long encodedChars, long captureTsMs) {
        long latency = captureTsMs > 0 && nowMs >= captureTsMs
                ? nowMs - captureTsMs : -1;
        if (latency > 60_000) {
            latency = -1; // lệch đồng hồ client/server — không dùng để quyết định
        }
        samples.addLast(new Sample(nowMs, Math.max(0, encodedChars), latency));
        lastArrivalMs = nowMs;
        purge(nowMs);
    }

    /**
     * Đánh giá và trả cấu hình kế tiếp. Gọi định kỳ (VD mỗi 500ms) — method tự
     * giới hạn tần suất và cooldown, gọi nhiều không gây đổi liên tục.
     */
    public synchronized Decision evaluate(long nowMs) {
        purge(nowMs);
        if (samples.size() < MIN_SAMPLES) {
            return Decision.unchanged(current, "chưa đủ mẫu");
        }
        long firstAt = samples.peekFirst().atMs;
        long elapsed = nowMs - firstAt;
        if (elapsed < EVALUATE_MIN_INTERVAL_MS) {
            return Decision.unchanged(current, "cửa sổ đo quá ngắn");
        }
        if (lastEvaluateMs != Long.MIN_VALUE
                && nowMs - lastEvaluateMs < EVALUATE_MIN_INTERVAL_MS) {
            return Decision.unchanged(current, "chưa đến kỳ đánh giá");
        }
        lastEvaluateMs = nowMs;

        long totalChars = 0;
        long latencySum = 0;
        int latencyCount = 0;
        for (Sample sample : samples) {
            totalChars += sample.encodedChars();
            if (sample.latencyMs() >= 0) {
                latencySum += sample.latencyMs();
                latencyCount++;
            }
        }
        long charsPerSecond = totalChars * 1000 / Math.max(1, elapsed);
        long averageLatency = latencyCount == 0 ? -1 : latencySum / latencyCount;

        boolean bad = charsPerSecond > HIGH_ENCODED_CHARS_PER_SECOND
                || (averageLatency >= 0 && averageLatency > HIGH_LATENCY_MS);
        boolean good = charsPerSecond < LOW_ENCODED_CHARS_PER_SECOND
                && (averageLatency < 0 || averageLatency < LOW_LATENCY_MS);

        if (bad) {
            badStreak++;
            goodStreak = 0;
        } else if (good) {
            goodStreak++;
            badStreak = 0;
        } else {
            badStreak = 0;
            goodStreak = 0;
            return Decision.unchanged(current,
                    "ổn định (" + charsPerSecond + " chars/s, latency="
                            + (averageLatency < 0 ? "n/a" : averageLatency + "ms") + ")");
        }

        if (nowMs < cooldownUntilMs) {
            return Decision.unchanged(current, "đang cooldown");
        }
        if (bad && badStreak >= BAD_STREAK_REQUIRED) {
            Settings next = degrade(current);
            badStreak = 0;
            if (!next.equals(current)) {
                current = next;
                cooldownUntilMs = nowMs + COOLDOWN_MS;
                return new Decision(next, true, "giảm tải ("
                        + charsPerSecond + " chars/s, latency="
                        + (averageLatency < 0 ? "n/a" : averageLatency + "ms") + ")");
            }
        } else if (good && goodStreak >= GOOD_STREAK_REQUIRED) {
            Settings next = upgrade(current);
            goodStreak = 0;
            if (!next.equals(current)) {
                current = next;
                cooldownUntilMs = nowMs + COOLDOWN_MS;
                return new Decision(next, true, "tăng chất lượng ("
                        + charsPerSecond + " chars/s, latency="
                        + (averageLatency < 0 ? "n/a" : averageLatency + "ms") + ")");
            }
        }
        return Decision.unchanged(current, "giữ nguyên");
    }

    /** Dừng stream/mất kết nối: xóa mẫu, về mặc định, bỏ cooldown. */
    public synchronized void reset() {
        samples.clear();
        current = Settings.defaults();
        lastEvaluateMs = Long.MIN_VALUE;
        cooldownUntilMs = Long.MIN_VALUE;
        lastArrivalMs = Long.MIN_VALUE;
        badStreak = 0;
        goodStreak = 0;
    }

    public synchronized Settings currentSettings() {
        return current;
    }

    /** Thời điểm frame cuối (ms) — test/chẩn đoán. */
    public synchronized long lastArrivalMs() {
        return lastArrivalMs;
    }

    private void purge(long nowMs) {
        while (!samples.isEmpty() && nowMs - samples.peekFirst().atMs() > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    /** Giảm 1 bậc: quality → width → fps. */
    static Settings degrade(Settings settings) {
        double quality = settings.quality() - 0.10;
        if (quality >= MIN_QUALITY - 1e-9) {
            return Settings.of(settings.width(), quality, settings.fps());
        }
        if (settings.width() > MIN_WIDTH) {
            int width = Math.max(MIN_WIDTH, (int) Math.round(settings.width() * 0.8));
            return Settings.of(width, MIN_QUALITY, settings.fps());
        }
        if (settings.fps() > MIN_FPS) {
            return Settings.of(MIN_WIDTH, MIN_QUALITY, settings.fps() - 1);
        }
        return settings; // đã ở biên
    }

    /** Tăng 1 bậc: fps → quality → width. */
    static Settings upgrade(Settings settings) {
        if (settings.fps() < MAX_FPS) {
            return Settings.of(settings.width(), settings.quality(), settings.fps() + 1);
        }
        if (settings.quality() < MAX_QUALITY - 1e-9) {
            return Settings.of(settings.width(),
                    Math.min(MAX_QUALITY, settings.quality() + 0.05), settings.fps());
        }
        if (settings.width() < MAX_WIDTH) {
            int width = Math.min(MAX_WIDTH, (int) Math.round(settings.width() * 1.25));
            return Settings.of(width, MAX_QUALITY, settings.fps());
        }
        return settings;
    }
}
