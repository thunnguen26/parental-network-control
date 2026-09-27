package com.parental.server.gui;

/**
 * Helper chuẩn hóa domain cho GUI (Domain Blacklist Management) — giữ quy tắc
 * GIỐNG NHAU với {@code com.parental.server.db.BlockedDomainDao#normalizeDomain}
 * nhưng tách riêng để test headless độc lập với DB layer và không đụng DAO
 * từ dialog (separation of concerns). Test kiểm tra 2 lớp cho ra kết quả
 * nhất quán (xem {@code BlockedDomainDaoTest#normalizationMatchesGuiHelper}).
 */
final class BlockedDomainDialogHelper {

    private BlockedDomainDialogHelper() {
        // utility class — không instantiable
    }

    /**
     * Chuẩn hóa đầu vào thành domain sạch: trim + lowercase, bỏ scheme
     * {@code http://}/{@code https://}, bỏ path/query sau {@code /}, bỏ port,
     * bỏ {@code www.} đầu. Không giống domain hợp lệ → {@code null}.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String domain = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (domain.isEmpty()) {
            return null;
        }
        domain = domain.replaceFirst("^https?://", "");
        int slash = domain.indexOf('/');
        if (slash >= 0) {
            domain = domain.substring(0, slash);
        }
        int colon = domain.indexOf(':');
        if (colon >= 0) {
            domain = domain.substring(0, colon);
        }
        if (domain.startsWith("www.") && domain.length() > 4) {
            domain = domain.substring(4);
        }
        domain = domain.trim();
        if (domain.isEmpty() || !domain.contains(".") || domain.startsWith(".")
                || domain.endsWith(".") || domain.contains(" ")
                || !domain.matches("[a-z0-9.-]+")) {
            return null;
        }
        return domain;
    }
}
