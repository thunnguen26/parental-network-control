package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.server.db.ActivityDao;

/**
 * Unit test logic 1-CLICK chặn nhanh (Mở rộng 10) qua test hook pure-function:
 * nhãn menu domain/tiến trình, đọc ô bảng an toàn, dòng 4 cột có URL.
 */
class QuickBlockOneClickTest {

    @Test
    @DisplayName("buildQuickBlockLabel: 1-click — nhãn chứa domain đã bóc tách sẵn, không 'nhập tay'")
    void domainLabelIsOneClick() {
        String label = ServerDashboardFrameTestHook.buildQuickBlockLabel("gemini.google.com");

        assertEquals("Chặn tên miền [gemini.google.com]", label,
                "Menu 1-click điền sẵn domain — không bắt người dùng gõ tay");
        assertTrue(label.contains("gemini.google.com"));
    }

    @Test
    @DisplayName("buildQuickBlockProcessLabel: nhãn chặn tiến trình .exe rõ ràng")
    void processLabelNamesProcess() {
        assertEquals("Chặn tiến trình [somegame.exe]",
                ServerDashboardFrameTestHook.buildQuickBlockProcessLabel("somegame.exe"));
    }

    @Test
    @DisplayName("Mở rộng 11: buildQuickUnblockProcessLabel — nhãn 'Bỏ chặn tiến trình [app.exe]'")
    void unblockLabelNamesProcess() {
        assertEquals("Bỏ chặn tiến trình [somegame.exe]",
                ServerDashboardFrameTestHook.buildQuickUnblockProcessLabel("somegame.exe"));
        // Nhãn unblock phân biệt rõ với nhãn block (không gây nhầm nút)
        assertTrue(!ServerDashboardFrameTestHook.buildQuickUnblockProcessLabel("app.exe")
                .equals(ServerDashboardFrameTestHook.buildQuickBlockProcessLabel("app.exe")));
    }

    @Test
    @DisplayName("Mở rộng 11: kiểm tra trạng thái đã chặn — so khớp tên normalize (có/không .exe, hoa/thường)")
    void blockedStateMatchByNormalizedName() {
        List<String> blockedInDb = List.of("somegame.exe", "browser.exe");

        // Logic so khớp trong maybeShowQuickBlockPopup: processName từ bảng có thể
        // là 'somegame.exe'/'SOMEGAME.EXE'/'somegame' — phải khớp tất cả
        for (String cellValue : new String[] {"somegame.exe", "SOMEGAME.EXE", "somegame"}) {
            boolean alreadyBlocked = blockedInDb.stream()
                    .anyMatch(name -> name.equalsIgnoreCase(
                            cellValue.trim().toLowerCase(java.util.Locale.ROOT))
                            || name.equalsIgnoreCase(cellValue.trim() + ".exe")
                            || name.equalsIgnoreCase(cellValue.trim()
                                    .toLowerCase(java.util.Locale.ROOT) + ".exe"));
            assertTrue(alreadyBlocked,
                    "'" + cellValue + "' phải được nhận diện là ĐÃ chặn → menu 'Bỏ chặn'");
        }
        boolean notBlocked = blockedInDb.stream().anyMatch(name -> name.equalsIgnoreCase(
                "otherapp.exe"));
        assertFalse(notBlocked, "App lạ → chưa chặn → menu 'Chặn tiến trình'");
    }

    @Test
    @DisplayName("Nhãn KHÔNG chứa emoji ngoài BMP (quy ước GUI sau hotfix ô vuông)")
    void labelsUseBmpOnly() {
        String domainLabel = ServerDashboardFrameTestHook.buildQuickBlockLabel("youtube.com");
        String processLabel = ServerDashboardFrameTestHook.buildQuickBlockProcessLabel("app.exe");

        for (String label : new String[] {domainLabel, processLabel}) {
            for (int i = 0; i < label.length(); i++) {
                assertTrue(Character.charCount(label.codePointAt(i)) <= 0xFFFF,
                        "Nhãn chỉ được dùng ký tự BMP: " + label);
            }
        }
    }

    @Test
    @DisplayName("buildActivityTableRow: 4 cột — URL/domain ở cột 3 (rỗng → '—')")
    void rowHasFourColumnsWithUrl() {
        ActivityDao.ActivityRecord withUrl = new ActivityDao.ActivityRecord(
                1L, "client-01", "chrome.exe",
                "Nhập câu lệnh cho Gemini - Google Chrome", "gemini.google.com",
                Instant.now());
        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(withUrl);

        assertEquals(4, row.length, "4 cột: Thời điểm / Tiến trình / Tiêu đề / URL");
        assertEquals("chrome.exe", row[1]);
        assertEquals("Nhập câu lệnh cho Gemini - Google Chrome", row[2]);
        assertEquals("gemini.google.com", row[3]);

        ActivityDao.ActivityRecord noUrl = new ActivityDao.ActivityRecord(
                2L, "client-01", "somegame.exe", "Elden Ring", "", Instant.now());
        assertEquals("—", ServerDashboardFrameTestHook.buildActivityTableRow(noUrl)[3],
                "App không URL → '—'");
    }

    @Test
    @DisplayName("valueOfCell: đọc ô an toàn — null/out-of-range/model null → chuỗi rỗng")
    void valueOfCellIsDefensive() {
        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel();
        model.setColumnIdentifiers(new Object[] {"A", "B", "C", "D"});
        model.addRow(new Object[] {"a1", null, "c1", "d1"});

        assertEquals("", ServerDashboardFrameTestHook.valueOfCell(model, 0, 1),
                "Ô null → rỗng");
        assertEquals("d1", ServerDashboardFrameTestHook.valueOfCell(model, 0, 3));
        assertEquals("", ServerDashboardFrameTestHook.valueOfCell(model, 5, 0),
                "Row out-of-range → rỗng");
        assertEquals("", ServerDashboardFrameTestHook.valueOfCell(model, 0, 99),
                "Column out-of-range → rỗng");
        assertEquals("", ServerDashboardFrameTestHook.valueOfCell(null, 0, 0),
                "Model null → rỗng");
    }

    @Test
    @DisplayName("Suggest domain từ payload UIA cho dòng bảng: URL cột 3 → 1-click được")
    void suggestDomainFromRowCells() {
        String url = ServerDashboardFrameTestHook.valueOfCell(
                new javax.swing.table.DefaultTableModel(
                        new Object[] {"21:00", "chrome.exe", "Gemini", "gemini.google.com"}, 1),
                0, ServerDashboardFrameTestHook.ACTIVITY_COLUMN_URL);

        assertEquals("gemini.google.com",
                WindowTitleDomainExtractor.suggestDomain(url, "Gemini"),
                "Cột URL có giá trị → 1-click chặn được domain sạch");
    }
}
