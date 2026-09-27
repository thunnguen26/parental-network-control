package com.parental.server.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.RowFilter;

/**
 * Unit test logic BỘ LỌC bảng nhật ký hoạt động (Mở rộng Nhật ký &amp; Bộ lọc
 * tìm kiếm) — dùng helper pure-function {@code ServerDashboardFrameTestHook}
 * (package-private) để kiểm chứng đúng {@link RowFilter} được tạo mà KHÔNG cần
 * dựng JFrame (an toàn headless — RowFilter là dữ liệu thuần, không đụng AWT
 * toolkit).
 *
 * <p>Quy ước dòng JTable: cột 0 = Thời điểm, cột 1 = Tiến trình, cột 2 = Tiêu đề.</p>
 */
class ActivityFilterSupportTest {

    /** Entry giả lập 1 dòng JTable từ List — để gọi filter.include() thuần. */
    private static final class ListEntry extends RowFilter.Entry<Object, Integer> {
        private final List<Object> values;

        ListEntry(List<Object> values) {
            this.values = values;
        }

        @Override
        public Object getValue(int index) {
            return values.get(index);
        }

        @Override
        public int getValueCount() {
            return values.size();
        }

        @Override
        public Integer getIdentifier() {
            return 0;
        }

        @Override
        public Object getModel() {
            return null; // không dùng — RegexFilter chỉ đọc getValue/getValueCount
        }
    }

    /** Arrays.asList (KHÔNG List.of — cần chấp nhận ô null trong test). */
    @SafeVarargs
    private static boolean included(RowFilter<Object, Integer> filter, Object... cells) {
        return filter.include(new ListEntry(Arrays.asList(cells)));
    }

    @Test
    @DisplayName("null/blank query → filter rỗng (không lọc gì)")
    void nullOrBlankQueryYieldsEmptyFilter() {
        assertFalse(ServerDashboardFrameTestHook.buildActivityRowFilter(null).isPresent());
        assertFalse(ServerDashboardFrameTestHook.buildActivityRowFilter("").isPresent());
        assertFalse(ServerDashboardFrameTestHook.buildActivityRowFilter("   ").isPresent());
    }

    @Test
    @DisplayName("Lọc theo tên tiến trình 'chrome' — khớp cột Tiến trình, không phân biệt hoa/thường")
    void filterByProcessNameCaseInsensitive() {
        RowFilter<Object, Integer> filter =
                ServerDashboardFrameTestHook.buildActivityRowFilter("chrome").orElseThrow();

        assertTrue(included(filter, "10:00:00", "chrome.exe", "Google Chrome"));
        assertTrue(included(filter, "10:00:00", "CHROME.EXE", "Trang chủ"),
                "Không phân biệt hoa/thường");
        assertTrue(included(filter, "10:00:00", "msedge.exe", "Google Chrome"),
                "Khớp cột TIÊU ĐỀ 'Google Chrome' → dòng được giữ (lọc theo tiến trình HOẶC tiêu đề)");
        assertFalse(included(filter, "10:00:00", "msedge.exe", "Trang chủ Bing"),
                "Không cột nào chứa 'chrome' → bị loại");
    }

    @Test
    @DisplayName("Lọc theo tiêu đề 'youtube' — khớp cột Tiêu đề cửa sổ")
    void filterByWindowTitle() {
        RowFilter<Object, Integer> filter =
                ServerDashboardFrameTestHook.buildActivityRowFilter("youtube").orElseThrow();

        assertTrue(included(filter, "10:00:00", "chrome.exe", "Xem video YouTube - Chrome"));
        assertFalse(included(filter, "10:00:00", "chrome.exe", "Trang chủ Google"));
    }

    @Test
    @DisplayName("Từ khóa chứa ký tự đặc biệt regex (?*[]) → so khớp LITERAL an toàn")
    void specialCharactersMatchLiterally() {
        RowFilter<Object, Integer> filter =
                ServerDashboardFrameTestHook.buildActivityRowFilter("bài tập (hóa)?").orElseThrow();

        assertTrue(included(filter, "10:00:00", "notepad.exe", "bài tập (hóa)? - Ghi chú"),
                "Ký tự đặc biệt phải được escape (Pattern.quote), so khớp literal");
        assertFalse(included(filter, "10:00:00", "notepad.exe", "bài tập hóa x"));
    }

    @Test
    @DisplayName("Cột Thời điểm cũng nằm trong phạm vi lọc (3 cột)")
    void filterCoversTimeColumn() {
        RowFilter<Object, Integer> filter =
                ServerDashboardFrameTestHook.buildActivityRowFilter("09:15").orElseThrow();

        assertTrue(included(filter, "09:15:33", "notepad.exe", "Ghi chú"));
        assertFalse(included(filter, "10:00:00", "notepad.exe", "Ghi chú thường"));
    }

    @Test
    @DisplayName("Ô lọc để trống sau khi lọc → Optional rỗng (GUI gỡ RowFilter, hiện toàn bộ)")
    void emptyAfterFilteringRestoresAllRows() {
        assertTrue(ServerDashboardFrameTestHook.buildActivityRowFilter("chrome").isPresent());
        assertFalse(ServerDashboardFrameTestHook.buildActivityRowFilter(" ").isPresent(),
                "Chỉ khoảng trắng → coi như bỏ lọc");
    }

    @Test
    @DisplayName("Giá trị ô null → coi như chuỗi rỗng, không ném exception")
    void nullCellValueIsSafe() {
        RowFilter<Object, Integer> filter =
                ServerDashboardFrameTestHook.buildActivityRowFilter("chrome").orElseThrow();

        // Dòng có ô null (process/title chưa nạp kịp) — include() không được ném
        assertFalse(included(filter, "10:00:00", null, null));
        assertEquals(3, new ListEntry(Arrays.asList("a", null, "c")).getValueCount());
    }
}
