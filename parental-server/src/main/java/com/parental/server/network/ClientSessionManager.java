package com.parental.server.network;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;

/**
 * Quản lý toàn bộ phiên kết nối Client đang online.
 *
 * <p>Thread-safety: dùng {@link ConcurrentHashMap} theo quy ước AGENTS.md
 * (Mục Concurrency &amp; Thread-Safety). Snapshot {@link #getAllSessions()}
 * trả về bản sao để caller không phụ thuộc trạng thái nội bộ.</p>
 */
public class ClientSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    /**
     * Đăng ký phiên mới. Nếu clientId đã tồn tại (reconnect đè lên) thì phiên
     * cũ bị đóng trước khi thay thế để không rò rỉ socket.
     */
    public void register(ClientSession session) {
        ClientSession old = sessions.put(session.getClientId(), session);
        if (old != null && old != session && !old.isClosed()) {
            log.warn("Duplicate clientId [{}]: closing stale session, replacing with new one",
                    session.getClientId());
            old.close();
        }
        log.info("Client [{}] registered ({} online)", session.getClientId(), count());
    }

    /** Gỡ và đóng phiên của clientId (nếu còn tồn tại). */
    public void remove(String clientId) {
        if (clientId == null) {
            return;
        }
        ClientSession removed = sessions.remove(clientId);
        if (removed != null) {
            removed.close();
            log.info("Client [{}] removed ({} online)", clientId, count());
        }
    }

    /**
     * Gỡ phiên cụ thể CHỈ khi nó vẫn là phiên đang được ánh xạ (remove
     * nguyên tử {@code remove(key, value)}) — tránh xóa nhầm phiên mới khi
     * client reconnect đè lên phiên cũ còn chậm dọn dẹp.
     */
    public void removeSession(ClientSession session) {
        if (session == null) {
            return;
        }
        if (sessions.remove(session.getClientId(), session)) {
            session.close();
            log.info("Client [{}] disconnected ({} online)", session.getClientId(), count());
        }
    }

    public ClientSession get(String clientId) {
        return clientId == null ? null : sessions.get(clientId);
    }

    /** Bản sao snapshot các phiên hiện có. */
    public List<ClientSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /** Số phiên đang online. */
    public int count() {
        return sessions.size();
    }

    /**
     * Đẩy một gói tin tới tất cả client đang online. Phiên gửi lỗi sẽ bị gỡ
     * khỏi danh sách (được coi là dead connection).
     *
     * @return số client nhận thành công
     */
    public int broadcast(BaseMessage<?> message) {
        int delivered = 0;
        for (ClientSession session : getAllSessions()) {
            if (session.send(message)) {
                delivered++;
            } else {
                log.warn("Broadcast: removing dead session [{}]", session.getClientId());
                remove(session.getClientId());
            }
        }
        return delivered;
    }

    /**
     * Quét và gỡ các phiên quá hạn: không nhận HEARTBEAT trong khoảng
     * {@code timeoutSeconds} (chỉ áp dụng cho phiên đã handshake — có
     * deviceInfo). Trả về danh sách clientId đã bị gỡ.
     */
    public List<String> sweepDeadSessions(long timeoutSeconds) {
        List<String> removed = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (ClientSession session : getAllSessions()) {
            if (session.getDeviceInfo() == null) {
                continue; // chưa handshake, ClientHandler tự xử lý
            }
            long elapsed = now - Math.max(session.getLastHeartbeatTimestamp(), 0L);
            if (elapsed > timeoutSeconds) {
                log.warn("Session [{}] timed out: no heartbeat for {}s > {}s, closing",
                        session.getClientId(), elapsed, timeoutSeconds);
                remove(session.getClientId());
                removed.add(session.getClientId());
            }
        }
        return removed;
    }
}
