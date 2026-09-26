package com.parental.client.enforcement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test cho {@link HostsFileManager} — CHẠY TRÊN TEMP FILE, không bao giờ
 * đụng file hosts thật của máy. Kiểm tra: bảo toàn nội dung gốc, marker block
 * đúng định dạng, idempotent khi cập nhật lặp, thay thế block cũ, clearBlocks,
 * null-safety, backup/restore và defensive khi thiếu quyền ghi.
 */
class HostsFileManagerTest {

    private static final String OS_LIKE_CONTENT = """
            # Copyright (c) 1993-2009 Microsoft Corp.
            #
            # This is a sample HOSTS file used by Microsoft TCP/IP for Windows.
            127.0.0.1  localhost
            ::1        localhost
            """;

    @TempDir
    Path tempDir;

    private HostsFileManager newManager(String initialContent) throws IOException {
        Path hosts = tempDir.resolve("hosts-" + System.nanoTime());
        if (initialContent != null) {
            Files.writeString(hosts, initialContent);
        }
        return new HostsFileManager(hosts);
    }

    private static String read(Path hosts) throws IOException {
        return Files.exists(hosts) ? Files.readString(hosts) : "";
    }

    private static Path hostsPathOf(HostsFileManager manager) {
        return manager.getHostsFilePath();
    }

    // ------------------------------------------------------------------
    // Ghi block mới khi file có nội dung OS sẵn
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Ghi block mới: nội dung gốc được bảo toàn, block nằm giữa 2 marker")
    void applyWritesMarkerBlockAndPreservesOriginalContent() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);

        assertTrue(manager.applyBlockedDomains(List.of("tiktok.com", "facebook.com")));
        String content = read(hostsPathOf(manager));

        // Nội dung gốc còn nguyên
        assertTrue(content.contains("127.0.0.1  localhost"), "Nội dung gốc phải được bảo toàn");
        assertTrue(content.contains("sample HOSTS file"), "Comment gốc phải còn");

        // Marker block đúng định dạng
        assertTrue(content.contains(HostsFileManager.MARKER_START));
        assertTrue(content.contains(HostsFileManager.MARKER_END));
        assertTrue(content.contains("127.0.0.1  tiktok.com"));
        assertTrue(content.contains("0.0.0.0  tiktok.com"));
        assertTrue(content.contains("127.0.0.1  facebook.com"));

        // Thứ tự: START trước END
        assertTrue(content.indexOf(HostsFileManager.MARKER_START)
                < content.indexOf(HostsFileManager.MARKER_END));
    }

    @Test
    @DisplayName("Idempotent: gọi apply 2 lần cùng danh sách → không trùng lặp")
    void applyIsIdempotent() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);
        List<String> domains = List.of("tiktok.com");

        manager.applyBlockedDomains(domains);
        String first = read(hostsPathOf(manager));

        manager.applyBlockedDomains(domains);
        String second = read(hostsPathOf(manager));

        assertEquals(countOccurrences(second, "127.0.0.1  tiktok.com"), 1,
                "Domain không được xuất hiện 2 lần trong block");
        assertEquals(countOccurrences(second, HostsFileManager.MARKER_START), 1,
                "Chỉ được có đúng 1 marker START");
        // Nội dung file sau 2 lần apply giống hệt lần 1 (ngoại trừ dòng trống thừa)
        assertEquals(normalize(first), normalize(second), "Idempotent: file không đổi");
    }

    @Test
    @DisplayName("Cập nhật danh sách mới: block cũ bị xóa sạch, chỉ còn block mới")
    void applyReplacesOldBlock() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);

        manager.applyBlockedDomains(List.of("tiktok.com", "facebook.com"));
        manager.applyBlockedDomains(List.of("instagram.com"));
        String content = read(hostsPathOf(manager));

        assertFalse(content.contains("tiktok.com"), "Domain cũ phải bị gỡ");
        assertFalse(content.contains("facebook.com"), "Domain cũ phải bị gỡ");
        assertTrue(content.contains("instagram.com"), "Domain mới phải xuất hiện");
        assertEquals(countOccurrences(content, HostsFileManager.MARKER_START), 1);
        assertEquals(countOccurrences(content, "127.0.0.1  localhost"), 1,
                "localhost gốc không bị nhân bản");
    }

    // ------------------------------------------------------------------
    // clearBlocks + restoreBackup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("clearBlocks: xóa sạch block, giữ nguyên nội dung gốc; file chưa có marker → false")
    void clearBlocksRemovesOnlyMarkerBlock() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);
        manager.applyBlockedDomains(List.of("tiktok.com", "porn.example"));

        assertTrue(manager.clearBlocks());
        String content = read(hostsPathOf(manager));
        assertFalse(content.contains(HostsFileManager.MARKER_START));
        assertFalse(content.contains("tiktok.com"));
        assertTrue(content.contains("127.0.0.1  localhost"), "Nội dung gốc phải còn");

        // Idempotent: lần 2 không còn gì để xóa
        assertFalse(manager.clearBlocks(), "Không còn marker → trả false, không ghi");
    }

    @Test
    @DisplayName("Backup tạo đúng 1 lần và restoreBackup khôi phục nội dung gốc ban đầu")
    void backupAndRestoreRestoreOriginalContent() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);

        manager.applyBlockedDomains(List.of("tiktok.com")); // tạo backup lần đầu
        assertTrue(Files.exists(manager.getBackupFilePath()));
        String backupContent = Files.readString(manager.getBackupFilePath());
        assertTrue(backupContent.contains("127.0.0.1  localhost"), "Backup phải là nội dung gốc");

        // Apply thêm lần nữa KHÔNG được ghi đè backup gốc
        manager.applyBlockedDomains(List.of("instagram.com"));
        assertEquals(backupContent, Files.readString(manager.getBackupFilePath()),
                "Backup gốc không được ghi đè bởi lần apply sau");

        manager.clearBlocks();
        assertTrue(manager.restoreBackup());
        assertEquals(normalize(OS_LIKE_CONTENT), normalize(read(hostsPathOf(manager))),
                "restoreBackup phải trả file về trạng thái ban đầu");
    }

    @Test
    @DisplayName("restoreBackup khi chưa có backup → false, không ném exception")
    void restoreWithoutBackupReturnsFalse() throws IOException {
        HostsFileManager manager = newManager(null);
        assertFalse(manager.restoreBackup());
    }

    // ------------------------------------------------------------------
    // Null-safety / danh sách rỗng / file chưa tồn tại
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Danh sách rỗng/null → clearBlocks; null element và blank được bỏ qua")
    void nullAndEmptyListsAreSafe() throws Exception {
        HostsFileManager manager = newManager(OS_LIKE_CONTENT);
        manager.applyBlockedDomains(List.of("tiktok.com"));

        // null → clear
        assertTrue(manager.applyBlockedDomains(null));
        assertFalse(read(hostsPathOf(manager)).contains(HostsFileManager.MARKER_START));

        // rỗng → clear (và trả false vì không còn marker)
        assertFalse(manager.applyBlockedDomains(List.of()));

        // Danh sách có null/blank element: chỉ domain hợp lệ được ghi
        manager.applyBlockedDomains(java.util.Arrays.asList("TIKTOK.Com", "  ", null, "tiktok.com"));
        String content = read(hostsPathOf(manager));
        assertTrue(content.contains("127.0.0.1  tiktok.com"), "Domain hợp lệ phải được ghi");
        assertEquals(countOccurrences(content, "127.0.0.1  tiktok.com"), 1,
                "Trùng lặp (sau lowercase/trim) phải bị khử");
        assertFalse(content.contains("null"), "Không được ghi dòng 'null'");
    }

    @Test
    @DisplayName("File hosts chưa tồn tại: apply tự tạo file với block marker")
    void applyCreatesMissingHostsFile() throws Exception {
        HostsFileManager manager = newManager(null); // file chưa tồn tại

        assertTrue(manager.applyBlockedDomains(List.of("tiktok.com")));
        String content = read(hostsPathOf(manager));
        assertTrue(content.contains(HostsFileManager.MARKER_START));
        assertTrue(content.contains("127.0.0.1  tiktok.com"));

        assertTrue(manager.clearBlocks());
        assertTrue(read(hostsPathOf(manager)).isBlank() || read(hostsPathOf(manager)).isEmpty(),
                "File gốc rỗng → sau clear phải rỗng");
    }

    // ------------------------------------------------------------------
    // Defensive: thiếu quyền ghi
    // ------------------------------------------------------------------

    @Test
    @DisplayName("File read-only: apply/clear không ném exception, trả false (log WARN)")
    void readOnlyFileReturnsFalseWithoutThrowing() throws Exception {
        Path hosts = tempDir.resolve("hosts-readonly");
        Files.writeString(hosts, OS_LIKE_CONTENT);
        HostsFileManager manager = new HostsFileManager(hosts);
        makeReadOnly(hosts);

        try {
            // Trên Windows, read-only attribute làm Files.move thất bại —
            // HostsFileManager phải nuốt và trả false, KHÔNG crash.
            boolean result = manager.applyBlockedDomains(List.of("tiktok.com"));
            assertFalse(result, "Ghi vào file read-only phải thất bại êm");
            assertFalse(read(hosts).contains(HostsFileManager.MARKER_START));
            assertFalse(manager.clearBlocks());
            assertFalse(manager.restoreBackup());
        } finally {
            makeWritable(hosts); // dọn dẹp cho @TempDir xóa được
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long countOccurrences(String content, String needle) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String normalize(String content) {
        return content.replaceAll("\\s+\n", "\n").trim();
    }

    private static void makeReadOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        } catch (UnsupportedOperationException e) {
            // Windows: không có POSIX permissions — dùng Dos attribute read-only
            Files.setAttribute(file, "dos:readonly", true);
        }
    }

    private static void makeWritable(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        } catch (UnsupportedOperationException e) {
            Files.setAttribute(file, "dos:readonly", false);
        }
    }
}
