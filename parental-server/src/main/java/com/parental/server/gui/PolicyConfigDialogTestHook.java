package com.parental.server.gui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultListModel;
import javax.swing.ListModel;

import com.parental.server.db.RuleDao;

/**
 * Test hook chứa LOGIC THUẦN của {@link PolicyConfigDialog} (Mở rộng 12):
 * nạp danh sách domains/tiến trình từ {@link RuleDao} vào model, snapshot
 * model → danh sách khi Lưu &amp; Đồng bộ, thêm/xóa phần tử — tách khỏi Swing
 * component để unit test được TRÊN MÔI TRƯỜNG HEADLESS (surefire chạy với
 * {@code java.awt.headless=true} — không dựng được JDialog thật; nhưng Swing
 * model ({@link DefaultListModel}) là object thuần, an toàn headless).
 *
 * <p>Chỉ PolicyConfigDialog gọi (package-private); không thuộc API công khai.</p>
 */
final class PolicyConfigDialogTestHook {

    private PolicyConfigDialogTestHook() {
        // utility class — không khởi tạo
    }

    /** Nạp danh sách TÊN MIỀN bị chặn từ DAO vào model (defensive null). */
    static void loadDomains(RuleDao ruleDao, String clientId, DefaultListModel<String> model) {
        fillModel(ruleDao != null ? ruleDao.getBlockedDomains(clientId) : null, model);
    }

    /** Nạp danh sách TIẾN TRÌNH bị chặn từ DAO vào model (defensive null) — Mở rộng 12. */
    static void loadProcesses(RuleDao ruleDao, String clientId, DefaultListModel<String> model) {
        fillModel(ruleDao != null ? ruleDao.getBlockedProcesses(clientId) : null, model);
    }

    private static void fillModel(List<String> items, DefaultListModel<String> model) {
        if (model == null) {
            return;
        }
        model.clear();
        if (items == null) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                model.addElement(item);
            }
        }
    }

    /**
     * Gom toàn bộ phần tử model thành danh sách LƯU DB (dùng chung cho domains
     * lẫn blockedProcesses trong {@code saveAndSync}). Thứ tự giữ nguyên;
     * phần tử null/rỗng bị bỏ qua. Null model → danh sách rỗng (không ném).
     */
    static List<String> snapshot(ListModel<String> model) {
        if (model == null) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            String item = model.getElementAt(i);
            if (item != null && !item.isBlank()) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Thêm tên (đã chuẩn hóa sẵn) vào model: trùng hoặc null/rỗng → không thêm,
     * trả {@code false} (caller tự quyết hiện cảnh báo khi null).
     */
    static boolean addNormalized(DefaultListModel<String> model, String normalized) {
        if (model == null || normalized == null || normalized.isBlank()
                || model.contains(normalized)) {
            return false;
        }
        model.addElement(normalized);
        return true;
    }

    /**
     * Xóa phần tử tại {@code index} (nút "Bỏ chặn / Xóa"): index ngoài phạm vi
     * → no-op trả {@code false}.
     */
    static boolean removeIndex(DefaultListModel<String> model, int index) {
        if (model == null || index < 0 || index >= model.size()) {
            return false;
        }
        model.remove(index);
        return true;
    }
}
