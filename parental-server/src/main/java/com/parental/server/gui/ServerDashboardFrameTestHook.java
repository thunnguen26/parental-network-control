package com.parental.server.gui;

import java.util.Optional;
import java.util.regex.Pattern;

import javax.swing.RowFilter;

/**
 * Test hook chứa LOGIC THUẦN của bộ lọc bảng nhật ký hoạt động trong
 * {@link ServerDashboardFrame} — tách khỏi Swing để unit test được trên
 * môi trường headless (AGENTS.md: separation of concerns, không spaghetti
 * trong UI handler).
 *
 * <p>Chỉ dashboard gọi (package-private); không thuộc API công khai.</p>
 */
final class ServerDashboardFrameTestHook {

    private ServerDashboardFrameTestHook() {
        // utility class — không khởi tạo
    }

    /**
     * Dựng {@link RowFilter} so khớp KHÔNG phân biệt hoa/thường trên 3 cột
     * nhật ký (0 = Thời điểm, 1 = Tiến trình, 2 = Tiêu đề). Từ khóa được
     * {@link Pattern#quote} — ký tự đặc biệt regex (?, *, [) so khớp LITERAL.
     *
     * @param query từ khóa tìm kiếm (null/rỗng/khoảng trắng → Optional rỗng =
     *              GUI gỡ lọc, hiện toàn bộ dòng)
     * @return filter cho TableRowSorter, hoặc rỗng nếu không cần lọc
     */
    static Optional<RowFilter<Object, Integer>> buildActivityRowFilter(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(RowFilter.regexFilter(
                "(?i)" + Pattern.quote(query.trim()), 0, 1, 2));
    }
}
