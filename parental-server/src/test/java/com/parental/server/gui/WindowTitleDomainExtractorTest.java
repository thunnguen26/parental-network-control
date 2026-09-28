package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test {@link WindowTitleDomainExtractor} — trích xuất domain từ tiêu đề
 * cửa sổ cho tính năng "🚫 Chặn nhanh tên miền" (headless, pure function).
 */
class WindowTitleDomainExtractorTest {

    @Test
    @DisplayName("Tiêu đề có URL tường minh → lấy domain sạch (bỏ scheme/path)")
    void extractsExplicitUrl() {
        assertEquals("vietjack.com",
                WindowTitleDomainExtractor.suggestDomain(
                        "https://vietjack.com/bai-tap-hoa-12 — Google Chrome"));
        assertEquals("facebook.com",
                WindowTitleDomainExtractor.suggestDomain(
                        "Dang nh?p vo trang | VKU's Elearning - http://facebook.com/profile"));
        assertEquals("example.com.vn",
                WindowTitleDomainExtractor.suggestDomain("Xem bài học tại www.example.com.vn"));
    }

    @Test
    @DisplayName("Tiêu đề chỉ có tên thương hiệu (YouTube/Facebook/Zalo...) → gợi ý domain quen thuộc")
    void suggestsFromBrandKeyword() {
        assertEquals("youtube.com",
                WindowTitleDomainExtractor.suggestDomain("YouTube - Cốc Cốc"));
        assertEquals("facebook.com",
                WindowTitleDomainExtractor.suggestDomain("Facebook – log in or sign up"));
        assertEquals("zalo.me", WindowTitleDomainExtractor.suggestDomain("Zalo - Nhắn tin"));
        assertEquals("tiktok.com", WindowTitleDomainExtractor.suggestDomain("TikTok - Make Your Day"));
    }

    @Test
    @DisplayName("Không có URL → chỉ có brand; có cả hai → ưu tiên URL thật")
    void explicitUrlBeatsBrand() {
        assertEquals("netflix.com",
                WindowTitleDomainExtractor.suggestDomain("Netflix — Watch TV Shows"));
        // Title có cả URL thật lẫn tên YouTube → URL thắng
        assertEquals("truonghoc.edu.vn",
                WindowTitleDomainExtractor.suggestDomain(
                        "YouTube Video — https://truonghoc.edu.vn/xem-video"));
    }

    @Test
    @DisplayName("Domain hạ tầng/CDN (gstatic...) bị hạ ưu tiên — chọn domain nội dung")
    void infrastructureDomainsDemoted() {
        assertEquals("example.com",
                WindowTitleDomainExtractor.suggestDomain(
                        "Page - fonts.gstatic.com/static https://example.com/trang"));
    }

    @Test
    @DisplayName("Chuỗi số/IP tạm trong title KHÔNG bị nhận là domain")
    void numericTokensNotDomains() {
        assertNull(WindowTitleDomainExtractor.suggestDomain("Bài 12.3.4 — Toán rời rạc"));
    }

    @Test
    @DisplayName("Title rỗng/null/không gợi ý được → null (GUI hiển thị ô nhập tay)")
    void nullOrUnsuggestableReturnsNull() {
        assertNull(WindowTitleDomainExtractor.suggestDomain(null));
        assertNull(WindowTitleDomainExtractor.suggestDomain(""));
        assertNull(WindowTitleDomainExtractor.suggestDomain("   "));
        assertNull(WindowTitleDomainExtractor.suggestDomain("Bài tập Toán - Notepad"));
        assertNull(WindowTitleDomainExtractor.suggestDomain("Visual Studio Code"));
    }

    @Test
    @DisplayName("Kết quả luôn chuẩn hóa lowercase — khớp BlockedDomainDialogHelper.normalize")
    void resultIsLowercaseNormalizable() {
        String suggestion = WindowTitleDomainExtractor.suggestDomain(
                "Trang chủ VietJack.COM — hướng dẫn giải bài tập");
        assertEquals("vietjack.com", suggestion);
        assertEquals(suggestion, BlockedDomainDialogHelper.normalize(suggestion),
                "Gợi ý phải đi qua normalize không đổi — nhất quán với DAO");
    }
}
