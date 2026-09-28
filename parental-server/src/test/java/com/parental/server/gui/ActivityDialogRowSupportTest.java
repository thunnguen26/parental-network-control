package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.RowFilter;

import com.parental.server.db.ActivityDao;

/**
 * Unit test logic THUẦN dựng bảng nhật ký hoạt động cho dialog
 * "⛶ Xem toàn bộ / Mở rộng" (hook pure-function — headless an toàn):
 * hằng số limit 200, formatter giờ local, dòng bảng giữ title nguyên vẹn,
 * defensive với dữ liệu null/rỗng.
 */
class ActivityDialogRowSupportTest {

    private static ActivityDao.ActivityRecord record(
            String process, String title, Instant at) {
        return new ActivityDao.ActivityRecord(1L, "client-01", process, title, at);
    }

    @Test
    @DisplayName("DIALOG_ROW_LIMIT = 200 — đúng spec dialog nạp tối đa 200 bản ghi mới nhất")
    void dialogRowLimitIsTwoHundred() {
        assertEquals(200, ServerDashboardFrameTestHook.DIALOG_ROW_LIMIT);
    }

    @Test
    @DisplayName("buildActivityTableRow: title giữ NGUYÊN VĂN — không truncate (scroll ngang xem hết)")
    void titleIsNeverTruncated() {
        String longTitle = "Giải bài tập Hóa 12 | VietJack — Bài 5: Cân bằng phương trình "
                + "hóa học — Chương 1: Chất và cấu tạo của chất — trang 128/540 (bản đầy đủ "
                + "rất dài để chắc chắn vượt mọi ngưỡng cắt cũ 80 ký tự của bảng dashboard)";
        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(
                record("browser.exe", longTitle, Instant.now()));

        assertEquals(4, row.length,
                "4 cột sau Mở rộng 10: Thời điểm / Tiến trình / Tiêu đề / URL-Domain");
        assertEquals(longTitle, row[2],
                "Cột 'Tiêu đề cửa sổ' phải giữ nguyên văn, KHÔNG bị cắt ngắn");
    }

    @Test
    @DisplayName("buildActivityTableRow: thời điểm format HH:mm:ss dd/MM/yyyy theo giờ LOCAL")
    void timestampIsLocalFormatted() {
        // 10:00 UTC → giờ local của ZoneId hệ thống; so khớp format, không hardcode múi giờ
        Instant at = LocalDateTime.of(2026, 9, 27, 10, 0, 0)
                .atZone(ZoneId.systemDefault()).toInstant();

        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(
                record("chrome.exe", "Google Chrome", at));

        String rendered = (String) row[0];
        assertTrue(rendered.matches("\\d{2}:\\d{2}:\\d{2} \\d{2}/\\d{2}/\\d{4}"),
                "Format HH:mm:ss dd/MM/yyyy — thực tế: " + rendered);
        assertEquals("10:00:00 27/09/2026", rendered,
                "Giờ LOCAL đúng như Instant tạo từ LocalDateTime hệ máy");
    }

    @Test
    @DisplayName("buildActivityTableRow: defensive — recordedAt/process/title null hoặc rỗng → '—'")
    void nullAndBlankCellsBecomeDash() {
        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(
                record(null, "  ", null));

        assertEquals("—", row[0], "recordedAt null → '—'");
        assertEquals("—", row[1], "process null → '—'");
        assertEquals("—", row[2], "title blank → '—'");
    }

    @Test
    @DisplayName("buildActivityTableRow: dữ liệu đầy đủ → 3 cột đúng thứ tự Thời điểm/Tiến trình/Tiêu đề")
    void rowColumnOrder() {
        Instant at = LocalDateTime.of(2026, 9, 27, 21, 5, 3)
                .atZone(ZoneId.systemDefault()).toInstant();

        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(
                record("browser.exe", "YouTube - Cốc Cốc", at));

        assertEquals("21:05:03 27/09/2026", row[0]);
        assertEquals("browser.exe", row[1]);
        assertEquals("YouTube - Cốc Cốc", row[2]);
    }

    @Test
    @DisplayName("activityTimestampFormatter: formatter mới mỗi lần gọi, có Zone hệ thống")
    void formatterIsZoneAware() {
        assertNotNull(ServerDashboardFrameTestHook.activityTimestampFormatter());
        assertEquals(ZoneId.systemDefault(),
                ServerDashboardFrameTestHook.activityTimestampFormatter().getZone());
    }

    @Test
    @DisplayName("Bộ lọc vẫn hoạt động trên dòng do hook dựng (tích hợp RowFilter + row builder)")
    void filterMatchesHookBuiltRows() {
        Instant at = LocalDateTime.of(2026, 9, 27, 9, 15, 33)
                .atZone(ZoneId.systemDefault()).toInstant();
        Object[] row = ServerDashboardFrameTestHook.buildActivityTableRow(
                record("browser.exe", "YouTube - Cốc Cốc", at));

        var filter = ServerDashboardFrameTestHook.buildActivityRowFilter("cốc cốc").orElseThrow();

        assertTrue(filter.include(new StaticRowEntry(row)),
                "Từ khóa 'cốc cốc' (không phân biệt hoa/thường) phải khớp cột tiêu đề");
        var missFilter = ServerDashboardFrameTestHook.buildActivityRowFilter("zzz-not-there")
                .orElseThrow();
        assertFalse(missFilter.include(new StaticRowEntry(row)),
                "Từ khóa không có trong dòng nào → dòng bị loại");
    }

    /** Entry tĩnh bọc 1 dòng Object[] để gọi RowFilter.include() headless. */
    private static final class StaticRowEntry extends RowFilter.Entry<Object, Integer> {
        private final Object[] cells;

        StaticRowEntry(Object[] cells) {
            this.cells = cells;
        }

        @Override
        public Object getValue(int index) {
            return cells[index];
        }

        @Override
        public int getValueCount() {
            return cells.length;
        }

        @Override
        public Integer getIdentifier() {
            return 0;
        }

        @Override
        public Object getModel() {
            return null; // RegexFilter chỉ đọc getValue/getValueCount
        }
    }
}
