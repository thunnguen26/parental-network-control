package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test {@link VietnameseInputSupport} (sửa lỗi gõ tiếng Việt Unikey/
 * EVKey trên Swing) — headless an toàn: {@code applyDefaultFont} chỉ đụng
 * UIManager defaults (không cần toolkit đồ họa);
 * {@code enableInputMethodsRecursively} no-op khi headless.
 */
class VietnameseInputSupportTest {

    @AfterEach
    void restoreDefaults() {
        UIManager.getLookAndFeelDefaults().remove("TextField.font");
    }

    @Test
    @DisplayName("applyDefaultFont: font mới hỗ trợ đầy đủ glyph tiếng Việt (ẠẶẸỆỠỰ...)")
    void appliedFontSupportsVietnameseGlyphs() {
        boolean applied = VietnameseInputSupport.applyDefaultFont();

        if (applied) {
            Font font = new Font(VietnameseInputSupport.VIETNAMESE_FONT_FAMILY, Font.PLAIN, 12);
            assertEquals(-1, font.canDisplayUpTo(
                    "ĂăÂâĐđÊêÔôƠơƯưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹđ"),
                    "Segoe UI phải hỗ trợ ĐẦY ĐỦ bảng chữ tiếng Việt có dấu");
        }
        // Máy không có Segoe UI (Linux CI) → applied=false là hợp lệ, không fail
    }

    @Test
    @DisplayName("applyDefaultFont: không ném khi LAF defaults chứa key font lạ (non-String/null font)")
    void weirdDefaultsAreSafe() {
        UIManager.getLookAndFeelDefaults().put("Weird.thing.font", 42); // Integer — không phải Font
        UIManager.getLookAndFeelDefaults().put("Other.font", null);

        boolean applied = VietnameseInputSupport.applyDefaultFont(); // không được ném

        UIManager.getLookAndFeelDefaults().remove("Weird.thing.font");
        UIManager.getLookAndFeelDefaults().remove("Other.font");
        assertNotNull(applied);
    }

    @Test
    @DisplayName("enableInputMethodsRecursively: null window + headless → no-op an toàn không ném")
    void headlessIsSafeNoOp() {
        // surefire chạy headless=true (pom) — method phải no-op sạch (null trước cả isHeadless check)
        VietnameseInputSupport.enableInputMethodsRecursively(null);
    }

    @Test
    @DisplayName("enableInputMethods(true) trên JTextField bật được IME (đơn giản component)")
    void jTextFieldAcceptsInputMethodEnable() {
        JTextField field = new JTextField("tiếng Việt có dấu");
        field.enableInputMethods(true); // đúng API helper gọi — không được ném
        assertEquals("tiếng Việt có dấu", field.getText());
    }

    @Test
    @DisplayName("VIETNAMESE_FONT_FAMILY = Segoe UI theo spec")
    void fontFamilyIsSegoeUi() {
        assertEquals("Segoe UI", VietnameseInputSupport.VIETNAMESE_FONT_FAMILY);
    }

    @Test
    @DisplayName("Cây component lồng nhau: JTextField/JPanel dựng headless được, enableInputMethods không ném")
    void nestedContainersAreSafeHeadless() {
        JPanel root = new JPanel();
        JPanel child = new JPanel();
        child.add(new JTextField("gõ tiếng Việt được"));
        root.add(child);

        // Duyệt tay đúng logic helper trên cây KHÔNG cần Window (headless không tạo được JFrame):
        for (java.awt.Component component : root.getComponents()) {
            component.enableInputMethods(true);
            if (component instanceof JPanel panel) {
                for (java.awt.Component leaf : panel.getComponents()) {
                    leaf.enableInputMethods(true);
                }
            }
        }
    }
}
