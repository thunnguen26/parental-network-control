package com.parental.server.gui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.swing.RowFilter;

import com.parental.server.db.ActivityDao;

/**
 * Test hook chứa LOGIC THUẦN của bảng nhật ký hoạt động trong
 * {@link ServerDashboardFrame} (cả bảng nhỏ 30 dòng lẫn dialog
 * "⛶ Xem toàn bộ / Mở rộng") — tách khỏi Swing để unit test được trên môi
 * trường headless (AGENTS.md: separation of concerns, không spaghetti trong
 * UI handler).
 *
 * <p>Chỉ dashboard gọi (package-private); không thuộc API công khai.</p>
 */
final class ServerDashboardFrameTestHook {

    /**
     * Số bản ghi tối đa dialog "Xem toàn bộ" nạp từ {@link ActivityDao}
     * (spec: 100–200 bản ghi mới nhất — lấy trần 200).
     */
    static final int DIALOG_ROW_LIMIT = 200;

    private ServerDashboardFrameTestHook() {
        // utility class — không khởi tạo
    }

    /**
     * Formatter cột "Thời điểm" — giờ LOCAL hệ máy phụ huynh (recorded_at đã
     * được ghi tường minh theo giờ local phía {@link ActivityDao}).
     */
    static DateTimeFormatter activityTimestampFormatter() {
        return DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy")
                .withZone(ZoneId.systemDefault());
    }

    /**
     * Dựng 1 dòng JTable nhật ký từ bản ghi DB — CỘT "TIÊU ĐỀ CỬA SỔ" GIỮ
     * NGUYÊN VĂN (không truncate; dialog dùng AUTO_RESIZE_OFF + scroll ngang
     * để xem đầy đủ).
     *
     * <p>Defensive: recordedAt null → "—"; process/title null/rỗng → "—".</p>
     */
    static Object[] buildActivityTableRow(ActivityDao.ActivityRecord record) {
        DateTimeFormatter formatter = activityTimestampFormatter();
        Instant recordedAt = record.recordedAt();
        return new Object[] {
                recordedAt != null ? formatter.format(recordedAt) : "—",
                orDash(record.processName()),
                orDash(record.windowTitle())
        };
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
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
