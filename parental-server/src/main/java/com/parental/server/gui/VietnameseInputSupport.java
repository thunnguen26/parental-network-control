package com.parental.server.gui;

import java.awt.Font;
import java.awt.Window;
import java.util.Locale;

import javax.swing.UIManager;

/**
 * Sửa lỗi KHÔNG GÕ ĐƯỢC tiếng Việt (Unikey/EVKey) vào các ô nhập liệu Swing
 * trên Windows — 2 nguyên nhân thường gặp và cách khắc phục tương ứng:
 *
 * <ol>
 *   <li><b>Font không hỗ trợ Unicode</b>: font mặc định của một số theme/LAF
 *       (hoặc font Monospaced cũ) không có glyph tiếng Việt → IME của Unikey
 *       gửi tổ hợp dấu nhưng Swing render thành ô vuông/ký tự rác, thậm chí
 *       InputMethod bị bỏ qua. Cách sửa: ép toàn bộ font UI (JTextField,
 *       JTextArea, JTable, JComboBox, JLabel...) sang {@code Segoe UI} — font
 *       hệ thống Windows 10/11 hỗ trợ TIẾNG VIỆT ĐẦY ĐỦ;</li>
 *   <li><b>InputMethodEvent không được đón</b>: component Swing mặc định chỉ
 *       nhận composed text khi {@code enableInputMethods(true)}. Một số LAF
 *       (FlatLaf cũ) và JTextField dựng bằng DocumentFilter tùy biến tắt IME
 *       ngầm. Cách sửa: {@code enableInputMethods(true)} tường minh trên mọi
 *       text component qua {@link #enableInputMethodsRecursively}.</li>
 * </ol>
 *
 * <p>Cách dùng (đã wire trong {@code ServerApp.main}): gọi
 * {@link #applyDefaultFont()} ngay sau khi setup LAF, TRƯỚC khi tạo Swing
 * component đầu tiên — mọi component tạo sau đó kế thừa font mới; gọi
 * {@link #enableInputMethodsRecursively(Window)} sau khi dựng frame/dialog
 * để bật IME cho toàn bộ cây component.</p>
 *
 * <p>Headless an toàn: {@code applyDefaultFont} chỉ đụng UIManager defaults
 * (không cần toolkit đồ họa — unit test được); {@code enableInputMethods-
 * Recursively} check {@code GraphicsEnvironment.isHeadless()} và no-op.</p>
 */
public final class VietnameseInputSupport {

    /** Font chuẩn Windows hỗ trợ tiếng Việt đầy đủ (fallback an toàn nếu thiếu). */
    public static final String VIETNAMESE_FONT_FAMILY = "Segoe UI";

    private VietnameseInputSupport() {
        // utility class — không instantiable
    }

    /**
     * Ép font mặc định của TOÀN BỘ Swing component sang {@code Segoe UI}
     * (giữ nguyên style/size hiện có). Gọi SAU khi setup LAF và TRƯỚC khi
     * tạo component đầu tiên. Headless an toàn — unit test được.
     *
     * @return {@code true} nếu font mặc định đã được áp dụng cho ít nhất 1
     *         loại component; {@code false} nếu hệ thống thiếu Segoe UI
     *         (khi đó giữ nguyên font hiện hành — không phá UI)
     */
    public static boolean applyDefaultFont() {
        Font base = findSupportedFont();
        if (base == null) {
            return false;
        }
        int changed = 0;
        for (Object keyObj : java.util.Collections.list(UIManager.getLookAndFeelDefaults().keys())) {
            if (!(keyObj instanceof String)) {
                continue; // key lạ (non-String) — bỏ qua an toàn
            }
            String key = (String) keyObj;
            if (key.toLowerCase(Locale.ROOT).endsWith("font")) {
                Font current = UIManager.getLookAndFeelDefaults().getFont(key);
                if (current != null) {
                    UIManager.put(key, base.deriveFont(current.getStyle(), current.getSize2D()));
                    changed++;
                }
            }
        }
        return changed > 0;
    }

    /** Font hỗ trợ tiếng Việt đầu tiên tìm được trong hệ thống (null nếu không có). */
    private static Font findSupportedFont() {
        for (String family : new String[] {VIETNAMESE_FONT_FAMILY,
                "Arial Unicode MS", "Tahoma", "Dialog"}) {
            Font font = new Font(family, Font.PLAIN, 12);
            if (font.canDisplayUpTo("ĂăÂâĐđÊêÔôƠơƯưạếệốỡứự") < 0) {
                return font;
            }
        }
        return null; // không font nào đủ glyph — giữ nguyên font hiện hành
    }

    /**
     * Bật InputMethod (Unikey/EVKey) cho TOÀN BỘ cây component của window —
     * đặc biệt các JTextField/JTextArea/JTable/JComboBox/JList. Idempotent
     * ({@code enableInputMethods(true)} gọi lặp không hại). Headless → no-op.
     */
    public static void enableInputMethodsRecursively(Window window) {
        if (window == null || isHeadless()) {
            return;
        }
        enableInputMethodsOn(window);
        for (java.awt.Component component : window.getComponents()) {
            enableInputMethodsOn(component);
            if (component instanceof java.awt.Container container) {
                enableInputMethodsOnChildren(container);
            }
        }
    }

    /** Duyệt đệ quy toàn bộ con — dùng enableInputMethods(true) từng component. */
    private static void enableInputMethodsOnChildren(java.awt.Container container) {
        for (java.awt.Component component : container.getComponents()) {
            enableInputMethodsOn(component);
            if (component instanceof java.awt.Container child) {
                enableInputMethodsOnChildren(child);
            }
        }
    }

    private static void enableInputMethodsOn(java.awt.Component component) {
        try {
            component.enableInputMethods(true);
        } catch (RuntimeException e) {
            // defensive: component lạ/đã dispose — bỏ qua, không crash UI
        }
    }

    private static boolean isHeadless() {
        try {
            return java.awt.GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
