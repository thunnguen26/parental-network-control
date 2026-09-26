package com.parental.server.gui;

/**
 * Logic định dạng chuỗi hiển thị của Dashboard GUI (Task 5.1) — tách riêng
 * khỏi Swing component để unit test được ở môi trường headless (CI/Maven).
 *
 * <p>Toàn bộ phương thức là pure function, không đụng AWT/Swing.</p>
 */
public final class GuiFormat {

    private GuiFormat() {
        // utility class — không instantiable
    }

    /** Đơn vị byte tăng dần cho {@link #formatBytes(long)}. */
    private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    /**
     * Định dạng dung lượng byte thành chuỗi dễ đọc: B → KB → MB → GB → TB
     * (chia 1024). Dưới 1024 B giữ nguyên byte; ≥ 2 mức thì 1 chữ số thập phân
     * (làm tròn HALF_UP); giá trị null-undefined → {@code "n/a"}.
     *
     * <p>Ví dụ: {@code 512 → "512 B"}, {@code 2048 → "2.0 KB"},
     * {@code 1_572_864 → "1.5 MB"}, {@code 0 → "0 B"}.</p>
     */
    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "n/a";
        }
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        if (unitIndex == 0) {
            return (int) value + " " + BYTE_UNITS[0];
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, BYTE_UNITS[unitIndex]);
    }

    /**
     * Định dạng tốc độ truyền (bytes/giây) — thêm hậu tố "/s" vào kết quả
     * {@link #formatBytes(long)}: {@code 1536 → "1.5 KB/s"}, {@code 0 → "0 B/s"}.
     * Âm → {@code "n/a"}.
     */
    public static String formatSpeedBps(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "n/a";
        }
        return formatBytes(bytesPerSecond) + "/s";
    }

    /**
     * Định dạng khoảng thời gian tính bằng giây thành {@code "Xh Ym Zs"}
     * (giờ:phút:giây), lược bỏ phần tử 0 ở đầu: {@code 3661 → "1h 1m 1s"},
     * {@code 600 → "10m 0s"}, {@code 45 → "45s"}, {@code 0 → "0s"}.
     * Âm → {@code "n/a"}.
     */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "n/a";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (hours > 0 || minutes > 0) {
            builder.append(minutes).append("m ");
        }
        builder.append(secs).append("s");
        return builder.toString();
    }

    /**
     * Giới hạn phần trăm vào khoảng {@code [0, 100]} (dùng cho JProgressBar).
     * Vượt hạn mức → 100 (GUI hiển thị màu đỏ qua trạng thái riêng).
     */
    public static int clampPercent(long used, long limit) {
        if (limit <= 0) {
            return 0;
        }
        double percent = (used * 100.0) / limit;
        return (int) Math.max(0, Math.min(100, Math.round(percent)));
    }
}
