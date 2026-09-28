package com.parental.server.gui;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper trích xuất TÊN MIỀN từ tiêu đề cửa sổ (tính năng "🚫 Chặn nhanh tên
 * miền" từ Nhật ký hoạt động) — thuần Java, không Swing → test headless được
 * (AGENTS.md: separation of concerns).
 *
 * <p>Chiến lược 2 lớp (theo thứ tự ưu tiên):</p>
 * <ol>
 *   <li><b>Domain tường minh</b>: tìm URL/domain trong title
 *       ({@code vietjack.com}, {@code https://facebook.com/...}) — chuẩn nhất
 *       khi tiêu đề hiển thị địa chỉ thật;</li>
 *   <li><b>Từ khóa thương hiệu</b>: chữ trong title khớp bảng từ khóa quen
 *       thuộc (YouTube → youtube.com, Facebook → facebook.com, Cốc Cốc →
 *       coc-coc.com...) — dùng khi tiêu đề chỉ là tên trang, không có URL.
 *       Bảng từ khóa chỉ là GỢI Ý ban đầu (kèm trong dialog xác nhận, phụ
 *       huynh xem/sửa trước khi chặn) — KHÔNG phải cơ chế chặn tự động.</li>
 * </ol>
 *
 * <p>Chỉ dashboard dùng (package-private); kết quả luôn đi qua
 * {@link BlockedDomainDialogHelper#normalize} trước khi lưu DB.</p>
 */
final class WindowTitleDomainExtractor {

    /**
     * Regex domain: chuỗi dạng a.b(.c...) với TLD ≥ 2 chữ cái — dùng để bắt
     * domain/URL xuất hiện tường minh trong tiêu đề.
     */
    private static final Pattern DOMAIN_IN_TEXT = Pattern.compile(
            "(?:https?://)?(?:www\\.)?((?:[a-z0-9-]+\\.)+[a-z]{2,})",
            Pattern.CASE_INSENSITIVE);

    /**
     * Từ khóa thương hiệu quen thuộc → tên miền gợi ý (không phân biệt hoa/
     * thường). Bổ sung dần theo nhu cầu; KHÔNG bắt buộc — title có URL thật
     * luôn được ưu tiên trước bảng này.
     */
    private static final Pattern KEYWORD_DOMAINS = Pattern.compile(
            "(youtube|facebook|instagram|tiktok|twitter|reddit|netflix|zalo|twitch|discord)",
            Pattern.CASE_INSENSITIVE);

    private WindowTitleDomainExtractor() {
        // utility class — không khởi tạo
    }

    /**
     * Trích xuất tên miền gợi ý từ tiêu đề cửa sổ.
     *
     * @param windowTitle tiêu đề cửa sổ (vd {@code "Dang nh?p vo trang |
     *                    VKU's Elearning - C?c C?c"}, {@code "YouTube - Cốc
     *                    Cốc"}, {@code "https://vietjack.com/bai-tap — Google
     *                    Chrome"})
     * @return domain gợi ý (lowercase, đã bỏ scheme/www), hoặc {@code null}
     *         nếu không tìm ra gì
     */
    static String suggestDomain(String windowTitle) {
        if (windowTitle == null || windowTitle.isBlank()) {
            return null;
        }

        // --- Lớp 1: domain tường minh trong title (ưu tiên cao nhất) ---
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = DOMAIN_IN_TEXT.matcher(windowTitle);
        while (matcher.find() && found.size() < 5) {
            String candidate = matcher.group(1).toLowerCase(Locale.ROOT);
            if (isPlausibleDomain(candidate)) {
                found.add(candidate);
            }
        }
        if (found.size() == 1) {
            return found.iterator().next();
        }
        if (found.size() > 1) {
            // Nhiều domain — chọn domain "thương mại" đầu tiên (bỏ engine/
            // CDN phổ biến trượt vào tiêu đề trình duyệt như gstatic).
            for (String domain : found) {
                if (!isInfrastructureDomain(domain)) {
                    return domain;
                }
            }
            return found.iterator().next();
        }

        // --- Lớp 2: từ khóa thương hiệu quen thuộc ---
        Matcher keyword = KEYWORD_DOMAINS.matcher(windowTitle);
        if (keyword.find()) {
            return switch (keyword.group(1).toLowerCase(Locale.ROOT)) {
                case "youtube" -> "youtube.com";
                case "facebook" -> "facebook.com";
                case "instagram" -> "instagram.com";
                case "tiktok" -> "tiktok.com";
                case "twitter" -> "twitter.com";
                case "reddit" -> "reddit.com";
                case "netflix" -> "netflix.com";
                case "zalo" -> "zalo.me";
                case "twitch" -> "twitch.tv";
                case "discord" -> "discord.com";
                default -> null;
            };
        }
        return null;
    }

    /** Domain có vẻ hợp lệ: có TLD quen thuộc hoặc ≥ 2 nhãn, không toàn số. */
    private static boolean isPlausibleDomain(String domain) {
        if (domain == null || domain.isBlank() || !domain.contains(".")) {
            return false;
        }
        // Toàn chữ số/chấm (IP tạm/đoạn số trong title) — không phải domain
        if (domain.matches("[0-9.]+")) {
            return false;
        }
        return domain.length() >= 4;
    }

    /**
     * Domain hạ tầng/CDN hay lọt vào tiêu đề (cookie, asset) — hạ ưu tiên khi
     * có nhiều ứng viên.
     */
    private static boolean isInfrastructureDomain(String domain) {
        return domain.endsWith("gstatic.com") || domain.endsWith("googleapis.com")
                || domain.endsWith("googleusercontent.com") || domain.endsWith("cdn.net")
                || domain.endsWith("cloudfront.net") || domain.endsWith("akamai.net");
    }
}
