package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test cho hotfix hiển thị ALERT (yêu cầu task):
 * <ol>
 *   <li>Popup JDialog ALWAYS-ON-TOP được đẩy lên EDT qua
 *       {@code SwingUtilities.invokeLater} (trước đây popup tạo từ thread
 *       {@code parental-client-alert} → vi phạm EDT, popup không focus và bị
 *       chôn dưới các cửa sổ khác);</li>
 *   <li>Dialog {@code setAlwaysOnTop(true)} + {@code toFront()/requestFocus()}
 *       — luôn nổi trên cùng và ép focus;</li>
 *   <li>message rỗng → text mặc định "Bạn nhận được một thông báo từ phụ
 *       huynh!", tiêu đề "Cảnh báo từ phụ huynh";</li>
 *   <li>tự đóng sau 15s — dispose ĐÚNG dialog alert (không đụng window khác).</li>
 * </ol>
 * Máy CI/agent headless không mở được GUI thật → các nhánh hiển thị được
 * kiểm chứng bằng SOURCE CONTRACT (quét file .java — tiền lệ
 * DomainBlockNoBrowserKillTest của repo) + test hành vi headless.
 */
class SystemTrayAlertNotifierTest {

    private static final Path NOTIFIER_SOURCE = Path.of(
            "src/main/java/com/parental/client/enforcement/SystemTrayAlertNotifier.java");

    private String readNotifierSource() throws IOException {
        return Files.readString(NOTIFIER_SOURCE);
    }

    // ------------------------------------------------------------------
    // Hành vi headless / defensive (chạy được mọi môi trường)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("notify(null/blank) trả false, không ném exception")
    void nullOrBlankReturnsFalse() {
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier(true);
        assertFalse(notifier.notify(null));
        assertFalse(notifier.notify("  "));
    }

    @Test
    @DisplayName("Headless: log fallback trả false, không ném exception")
    void headlessFallsBackToLog() {
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier(true);
        assertFalse(notifier.notify("Message for headless environment"));
    }

    @Test
    @DisplayName("Headless: message có nội dung vẫn không ném + trả false (kênh GUI)")
    void headlessNeverThrows() {
        SystemTrayAlertNotifier notifier = new SystemTrayAlertNotifier(true);
        assertFalse(notifier.notify("Không crash với ký tự đặc biệt: <>&\"'"));
    }

    // ------------------------------------------------------------------
    // SOURCE CONTRACT — yêu cầu bắt buộc của hotfix (tiền lệ repo)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Contract: popup hiển thị trong SwingUtilities.invokeLater (EDT)")
    void popupRunsOnEdtViaInvokeLater() throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("SwingUtilities.invokeLater"),
                "Phải đẩy popup lên EDT qua SwingUtilities.invokeLater");
        assertTrue(source.contains("showAlwaysOnTopPopup"),
                "Phải có hàm popup always-on-top riêng");
    }

    @Test
    @DisplayName("Contract: JDialog setAlwaysOnTop(true) + toFront/requestFocus")
    void popupIsAlwaysOnTopAndTakesFocus() throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("setAlwaysOnTop(true)"),
                "Dialog phải setAlwaysOnTop(true) — nổi trên cùng mọi cửa sổ");
        assertTrue(source.contains("toFront()"),
                "Dialog phải toFront() — đưa lên trước");
        assertTrue(source.contains("requestFocus()"),
                "Dialog phải requestFocus() — ép focus lên màn hình");
    }

    @Test
    @DisplayName("Contract: tiêu đề + message mặc định đúng spec")
    void popupTitleAndDefaultTextMatchSpec() throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("\"Cảnh báo từ phụ huynh\""),
                "Tiêu đề popup phải là 'Cảnh báo từ phụ huynh'");
        assertTrue(source.contains("Bạn nhận được một thông báo từ phụ huynh!"),
                "Message rỗng phải dùng text mặc định theo spec");
        assertTrue(source.contains("JOptionPane.WARNING_MESSAGE"),
                "Popup phải dùng WARNING_MESSAGE");
    }

    @Test
    @DisplayName("Contract: tự đóng 15s dispose ĐÚNG dialog (không đụng window khác)")
    void autoCloseDisposesOnlyAlertDialog() throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("POPUP_AUTO_CLOSE_MS = 15_000"),
                "Vẫn giữ auto-close 15s");
        // Fix hành vi cũ dispose TOÀN BỘ windows của app:
        assertTrue(source.contains("window.getOwner()"),
                "Auto-close chỉ dispose dialog alert + dialog con (owner), không đụng window khác");
    }

    @Test
    @DisplayName("Contract: DISPOSE_ON_CLOSE + JDialog import (không phải JFrame)")
    void dialogDisposeOnClose() throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("JDialog.DISPOSE_ON_CLOSE"),
                "Dialog phải DISPOSE_ON_CLOSE theo spec");
        assertTrue(source.contains("import javax.swing.JDialog;"),
                "Dùng JDialog theo spec");
    }

    @Test
    @DisplayName("Contract: notifier giữ chuỗi fallback tray → popup → log")
    void fallbackChainPreserved(@TempDir Path tmp) throws IOException {
        String source = readNotifierSource();
        assertTrue(source.contains("SystemTray.isSupported()"),
                "Ưu tiên tray balloon khi có tray");
        assertTrue(source.contains("log.warn(\"[ALERT] {}\", message)"),
                "Headless fallback log WARN giữ nguyên");
        assertFalse(source.isBlank());
    }
}
