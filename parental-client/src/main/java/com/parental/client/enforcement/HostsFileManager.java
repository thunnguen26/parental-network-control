package com.parental.client.enforcement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Blocking Engine qua file {@code hosts} (Task 3.2).
 *
 * <p><b>Marker Block</b>: mọi domain ứng dụng chặn nằm giữa 2 dòng đánh dấu,
 * không bao giờ đụng cấu hình sẵn có của người dùng:
 * <pre>
 * # &gt;&gt;&gt; PARENTAL_CONTROL_BLOCK_START &gt;&gt;&gt;
 * 127.0.0.1  example1.com
 * 127.0.0.1  example2.com
 * # &lt;&lt;&lt; PARENTAL_CONTROL_BLOCK_END &lt;&lt;&lt;
 * </pre></p>
 *
 * <p><b>Defensive OS Interfacing</b> (AGENTS.md): kiểm tra {@code canWrite()}
 * trước khi ghi; bắt {@code AccessDeniedException}/{@code IOException} và ghi
 * log WARN — <b>không bao giờ ném exception làm crash Client hay ngắt
 * Socket</b>. Mọi thao tác ghi là atomic (ghi temp file rồi
 * {@code ATOMIC_MOVE/REPLACE_EXISTING}).</p>
 *
 * <p>Đường dẫn file hosts truyền vào linh hoạt qua constructor (test dùng
 * temp file); mặc định suy theo OS: Windows
 * {@code %SystemRoot%\System32\drivers\etc\hosts}, Linux/macOS {@code /etc/hosts}.</p>
 */
public class HostsFileManager {

    public static final String MARKER_START = "# >>> PARENTAL_CONTROL_BLOCK_START >>>";
    public static final String MARKER_END = "# <<< PARENTAL_CONTROL_BLOCK_END <<<";
    public static final String BACKUP_SUFFIX = ".parental.bak";
    public static final String BLOCK_IP = "127.0.0.1";
    public static final String BLOCK_IP_EXTRA = "0.0.0.0";

    private static final Logger log = LoggerFactory.getLogger(HostsFileManager.class);

    /** Số lần retry khi move bị khóa tạm thời trên Windows (AV/process đọc đồng thời). */
    private static final int MOVE_RETRY_ATTEMPTS = 3;
    /** Khoảng nghỉ giữa các lần retry (ms). */
    private static final long MOVE_RETRY_DELAY_MS = 100;

    private final Path hostsFilePath;
    private final Path backupFilePath;

    /** Suy đường dẫn hosts theo OS hiện hành (Windows qua env SystemRoot). */
    public static Path defaultHostsPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                systemRoot = "C:\\Windows";
            }
            return Path.of(systemRoot, "System32", "drivers", "etc", "hosts");
        }
        return Path.of("/etc/hosts");
    }

    /** Dùng đường dẫn hosts mặc định theo OS. */
    public HostsFileManager() {
        this(defaultHostsPath());
    }

    /**
     * @param hostsFilePath đường dẫn file hosts (test truyền temp file —
     *                      KHÔNG BAO GIỜ đụng hosts thật trong test)
     */
    public HostsFileManager(Path hostsFilePath) {
        this.hostsFilePath = hostsFilePath;
        this.backupFilePath = hostsFilePath.resolveSibling(
                hostsFilePath.getFileName() + BACKUP_SUFFIX);
    }

    public Path getHostsFilePath() {
        return hostsFilePath;
    }

    public Path getBackupFilePath() {
        return backupFilePath;
    }

    // ------------------------------------------------------------------
    // API chính
    // ------------------------------------------------------------------

    /**
     * Cập nhật danh sách domain bị chặn (idempotent): xóa block cũ giữa 2
     * marker, ghi block mới với danh sách {@code domains} trỏ về
     * {@code 127.0.0.1} (và {@code 0.0.0.0} để triệt để). Tạo backup trước
     * khi ghi lần đầu. Danh sách rỗng tương đương {@link #clearBlocks()}.
     *
     * @return {@code true} nếu ghi thành công; {@code false} nếu thiếu quyền
     *         hoặc I/O lỗi (đã log WARN, không ném exception)
     */
    public boolean applyBlockedDomains(List<String> domains) {
        List<String> sanitized = sanitizeDomains(domains);
        if (sanitized.isEmpty()) {
            log.info("Empty blocked-domains list — clearing hosts block instead");
            return clearBlocks();
        }
        try {
            String original = readHostsSafely();
            ensureBackup(original);

            String withoutOldBlock = stripMarkerBlock(original);
            String newBlock = buildMarkerBlock(sanitized);
            String updated = appendBlock(withoutOldBlock, newBlock);

            atomicWrite(updated);
            log.info("Hosts file updated: {} domain(s) blocked via {}", sanitized.size(), hostsFilePath);
            return true;
        } catch (AccessDeniedException e) {
            log.warn("Lacking Administrator/root privileges to modify hosts file. "
                    + "Domain blocking is bypassed. ({})", e.getMessage());
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to update hosts file ({}): {}", hostsFilePath, e.getMessage());
            return false;
        }
    }

    /**
     * Xóa sạch block giữa 2 marker — trả file hosts về trạng thái có nội
     * dung gốc (nếu còn) hoặc trạng thái sạch. Idempotent: file chưa có
     * marker thì không ghi gì cả.
     *
     * @return {@code true} nếu có thay đổi và ghi thành công; {@code false}
     *         nếu không cần ghi hoặc ghi lỗi (không ném exception)
     */
    public boolean clearBlocks() {
        try {
            String original = readHostsSafely();
            if (!original.contains(MARKER_START)) {
                log.debug("No parental block present in hosts file — nothing to clear");
                return false;
            }
            ensureBackup(original);
            atomicWrite(stripMarkerBlock(original).stripTrailing() + System.lineSeparator());
            log.info("Parental block removed from hosts file ({})", hostsFilePath);
            return true;
        } catch (AccessDeniedException e) {
            log.warn("Lacking Administrator/root privileges to modify hosts file. "
                    + "Domain blocking is bypassed. ({})", e.getMessage());
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to clear hosts block ({}): {}", hostsFilePath, e.getMessage());
            return false;
        }
    }

    /**
     * Khôi phục file hosts từ bản sao lưu {@code hosts.parental.bak} — gọi
     * khi tắt ứng dụng hoặc reset. Không ném exception.
     *
     * @return {@code true} nếu khôi phục thành công
     */
    public boolean restoreBackup() {
        try {
            if (!Files.exists(backupFilePath)) {
                log.debug("No hosts backup found at {} — nothing to restore", backupFilePath);
                return false;
            }
            atomicWrite(Files.readString(backupFilePath, StandardCharsets.UTF_8));
            log.info("Hosts file restored from backup {}", backupFilePath);
            return true;
        } catch (AccessDeniedException e) {
            log.warn("Lacking Administrator/root privileges to restore hosts file. ({})", e.getMessage());
            return false;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to restore hosts backup ({}): {}", backupFilePath, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Chuẩn hóa danh sách domain: lowercase, bỏ trùng, bỏ blank — null-safe. */
    static List<String> sanitizeDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String domain : domains) {
            if (domain == null) {
                continue;
            }
            String cleaned = domain.trim().toLowerCase(Locale.ROOT);
            if (!cleaned.isBlank()) {
                unique.add(cleaned);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Đọc file hosts; file chưa tồn tại (môi trường test) → trả chuỗi rỗng.
     */
    private String readHostsSafely() throws IOException {
        if (!Files.exists(hostsFilePath)) {
            return "";
        }
        return Files.readString(hostsFilePath, StandardCharsets.UTF_8);
    }

    /** Tạo backup một lần duy nhất (chỉ khi chưa có backup). */
    private void ensureBackup(String originalContent) throws IOException {
        if (Files.exists(backupFilePath)) {
            return; // giữ backup GỐC đầu tiên — không ghi đè
        }
        Path temp = Files.createTempFile(hostsFilePath.getParent(), "parental-bak-", ".tmp");
        Files.writeString(temp, originalContent, StandardCharsets.UTF_8);
        try {
            Files.move(temp, backupFilePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        log.info("Hosts backup created at {}", backupFilePath);
    }

    /** Xóa toàn bộ nội dung giữa 2 marker (kể cả marker) khỏi nội dung. */
    static String stripMarkerBlock(String content) {
        if (content == null || !content.contains(MARKER_START)) {
            return content == null ? "" : content;
        }
        StringBuilder sb = new StringBuilder(content.length());
        boolean inBlock = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(MARKER_START)) {
                inBlock = true;
                continue;
            }
            if (trimmed.equals(MARKER_END)) {
                inBlock = false;
                continue;
            }
            if (!inBlock) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Dựng khối marker mới từ danh sách domain đã sanitize. */
    static String buildMarkerBlock(List<String> domains) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_START).append('\n');
        for (String domain : domains) {
            sb.append(BLOCK_IP).append("  ").append(domain).append('\n');
            sb.append(BLOCK_IP_EXTRA).append("  ").append(domain).append('\n');
        }
        sb.append(MARKER_END);
        return sb.toString();
    }

    /** Nối khối marker vào cuối nội dung (bảo đảm có dòng trống phân tách). */
    private static String appendBlock(String content, String block) {
        String base = content.endsWith("\n") ? content : content + (content.isBlank() ? "" : "\n");
        return base + "\n" + block + "\n";
    }

    /**
     * Ghi an toàn: ghi vào temp file cùng thư mục rồi atomic move đè file
     * hosts — tránh trạng thái nửa vời khi crash giữa chừng.
     *
     * <p>Trên Windows, file hosts có thể bị khóa tạm thời (trình quét virus,
     * process đọc đồng thời) — {@code move} thất bại được retry tới
     * {@value MOVE_RETRY_ATTEMPTS} lần với khoảng nghỉ ngắn trước khi bỏ.</p>
     */
    private void atomicWrite(String content) throws IOException {
        if (!Files.exists(hostsFilePath) && hostsFilePath.getParent() != null) {
            Files.createDirectories(hostsFilePath.getParent());
        }
        Path temp = Files.createTempFile(hostsFilePath.getParent(), "parental-hosts-", ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        IOException lastError = null;
        try {
            for (int attempt = 1; attempt <= MOVE_RETRY_ATTEMPTS; attempt++) {
                try {
                    try {
                        Files.move(temp, hostsFilePath,
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        // Một số filesystem (tmpfs/FAT) không hỗ trợ atomic move — fallback
                        Files.move(temp, hostsFilePath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return; // thành công
                } catch (IOException e) {
                    lastError = e;
                    log.debug("Hosts write attempt {}/{} failed: {}",
                            attempt, MOVE_RETRY_ATTEMPTS, e.getMessage());
                    try {
                        Thread.sleep(MOVE_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw lastError != null ? lastError : new IOException("Unknown move failure");
        } finally {
            Files.deleteIfExists(temp); // temp còn lại nếu mọi lần move đều thất bại
        }
    }

    /** Kiểm tra quyền ghi file hosts (phòng thủ, không ném exception). */
    public boolean canWrite() {
        try {
            return Files.isWritable(hostsFilePath);
        } catch (RuntimeException e) {
            log.debug("canWrite check failed: {}", e.getMessage());
            return false;
        }
    }
}
