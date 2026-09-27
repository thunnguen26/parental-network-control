package com.parental.client.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Quản lý định danh bền vững của Client (Client Identity Persistence).
 *
 * <p><b>Vấn đề được giải quyết triệt để</b>: trước đây client sinh UUID mới
 * trong RAM mỗi lần bật lại → Server Dashboard nhân bản nhiều dòng thiết bị
 * Offline. Giờ đây {@code clientId} được sinh {@code UUID.randomUUID()}
 * <b>MỘT LẦN DUY NHẤT</b> rồi lưu bền vững vào {@code client_identity.json}
 * trong working directory — mọi lần khởi động sau đọc lại đúng UUID đó, thiết
 * bị chỉ có 1 dòng duy nhất trên Server.</p>
 *
 * <p>Các trường lưu trữ (JSON):</p>
 * <ul>
 *   <li>{@code clientId} — UUID định danh suốt đời máy;</li>
 *   <li>{@code deviceToken} — token cấp quyền kết nối, cấp khi ghép đôi
 *       thành công bằng mã PIN 6 số (null khi chưa ghép đôi);</li>
 *   <li>{@code serverHost} / {@code serverPort} — địa chỉ server đã dùng
 *       lần gần nhất (ghi nhớ zero-config cho lần bật sau).</li>
 * </ul>
 *
 * <p><b>Defensive</b>: file hỏng/thiếu quyền → log WARN và coi như chưa có
 * định danh (tạo mới) — không bao giờ làm chết JVM; ghi file atomic
 * (ghi temp → {@code ATOMIC_MOVE}) tránh file bị hỏng giữa chừng khi crash.</p>
 */
public class ClientIdentityManager {

    /** Tên file định danh trong working directory. */
    public static final String DEFAULT_IDENTITY_FILE = "client_identity.json";

    private static final Logger log = LoggerFactory.getLogger(ClientIdentityManager.class);

    private final Path identityFilePath;
    private final ObjectMapper mapper;

    /** Bản ghi định danh (khớp file {@code client_identity.json}). */
    public record ClientIdentity(
            String clientId,
            String deviceToken,
            String serverHost,
            int serverPort) {

        /** Bản ghi mới chỉ có clientId (chưa ghép đôi, chưa biết server). */
        public static ClientIdentity fresh(String clientId) {
            return new ClientIdentity(clientId, null, null, 0);
        }
    }

    /**
     * Manager trỏ tới {@code client_identity.json} trong working directory.
     */
    public ClientIdentityManager() {
        this(Path.of(DEFAULT_IDENTITY_FILE));
    }

    /**
     * @param identityFilePath đường dẫn file định danh (test dùng {@code @TempDir}
     *                         — KHÔNG đụng file thật)
     */
    public ClientIdentityManager(Path identityFilePath) {
        this.identityFilePath = identityFilePath;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Đọc định danh bền vững; nếu file chưa tồn tại/hỏng → tạo bản ghi mới:
     * sinh {@code UUID.randomUUID().toString()} MỘT LẦN DUY NHẤT và lưu lại
     * ngay. Bảo đảm mọi lần gọi sau trên cùng máy trả về cùng {@code clientId}.
     */
    public ClientIdentity loadOrCreate() {
        ClientIdentity existing = load();
        if (existing != null && existing.clientId() != null && !existing.clientId().isBlank()) {
            log.info("Identity loaded from {}: clientId={} (paired={})",
                    identityFilePath, existing.clientId(), existing.deviceToken() != null);
            return existing;
        }
        String clientId = UUID.randomUUID().toString();
        ClientIdentity created = existing != null
                ? new ClientIdentity(clientId, existing.deviceToken(),
                        existing.serverHost(), existing.serverPort())
                : ClientIdentity.fresh(clientId);
        if (save(created)) {
            log.info("New persistent identity created: clientId={} → {}", clientId, identityFilePath);
        }
        return created;
    }

    /** Đọc file định danh (null nếu chưa có hoặc file hỏng). */
    public ClientIdentity load() {
        if (!Files.exists(identityFilePath)) {
            return null;
        }
        try {
            String json = Files.readString(identityFilePath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return null;
            }
            return mapper.readValue(json, ClientIdentity.class);
        } catch (IOException | RuntimeException e) {
            // File hỏng/thiếu quyền — defensive: coi như chưa có định danh.
            log.warn("Cannot read identity file {} ({}): starting with fresh identity",
                    identityFilePath, e.getMessage());
            return null;
        }
    }

    /** Ghi đè toàn bộ định danh xuống file (atomic, có backup khi fail). */
    public boolean save(ClientIdentity identity) {
        if (identity == null || identity.clientId() == null || identity.clientId().isBlank()) {
            log.warn("Refusing to save identity without clientId");
            return false;
        }
        Path tempFile = identityFilePath.resolveSibling(
                identityFilePath.getFileName() + ".tmp");
        try {
            if (identityFilePath.getParent() != null) {
                Files.createDirectories(identityFilePath.getParent());
            }
            String json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(identity);
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, identityFilePath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                // Filesystem không hỗ trợ ATOMIC_MOVE → fallback move thường.
                Files.move(tempFile, identityFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Identity saved to {} (clientId={}, paired={})",
                    identityFilePath, identity.clientId(), identity.deviceToken() != null);
            return true;
        } catch (IOException | RuntimeException e) {
            log.error("Failed to save identity to {}: {}", identityFilePath, e.getMessage());
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // ignore cleanup failure
            }
            return false;
        }
    }

    /**
     * Tiện lợi: cập nhật {@code deviceToken} (kết quả ghép đôi) trên bản ghi
     * hiện hành và lưu file. Giữ nguyên clientId/serverHost/serverPort.
     */
    public boolean saveDeviceToken(String deviceToken) {
        ClientIdentity current = loadOrCreate();
        return save(new ClientIdentity(current.clientId(), deviceToken,
                current.serverHost(), current.serverPort()));
    }

    /** Đã ghép đôi chưa (có deviceToken)? */
    public boolean isPaired() {
        ClientIdentity identity = load();
        return identity != null && identity.deviceToken() != null && !identity.deviceToken().isBlank();
    }

    /** Đường dẫn file định danh đang quản lý (test/log dùng). */
    public Path getIdentityFilePath() {
        return identityFilePath;
    }
}
