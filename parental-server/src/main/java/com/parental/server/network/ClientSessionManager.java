package com.parental.server.network;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;

/**
 * Quản lý toàn bộ phiên kết nối Client đang online.
 *
 * <p>Thread-safety: dùng {@link ConcurrentHashMap} theo quy ước AGENTS.md
 * (Mục Concurrency &amp; Thread-Safety). Snapshot {@link #getAllSessions()}
 * trả về bản sao để caller không phụ thuộc trạng thái nội bộ.</p>
 *
 * <p>Task 2.3: hỗ trợ {@link ClientConnectionListener} — fire callback
 * {@code onClientConnected} khi đăng ký phiên mới và
 * {@code onClientDisconnected(clientId, reason)} khi phiên bị gỡ kèm nguyên
 * nhân (CLIENT_DISCONNECTED / HEARTBEAT_TIMEOUT / REPLACED / SERVER_SHUTDOWN
 * / IO_ERROR / UNKNOWN). Callback chạy trên thread gọi (mạng/sweeper) — phải
 * nhanh, không block.</p>
 */
public class ClientSessionManager {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ClientConnectionListener> listeners = new CopyOnWriteArrayList<>();

    /** Đăng ký thêm listener sự kiện kết nối (idempotent theo instance). */
    public void addConnectionListener(ClientConnectionListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** Gỡ listener (dùng khi test dọn dẹp hoặc engine tắt). */
    public void removeConnectionListener(ClientConnectionListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void fireConnected(ClientSession session) {
        for (ClientConnectionListener listener : listeners) {
            try {
                listener.onClientConnected(session);
            } catch (RuntimeException e) {
                log.error("ConnectionListener.onClientConnected threw: {}", e.getMessage());
            }
        }
    }

    private void fireDisconnected(String clientId, DisconnectReason reason) {
        for (ClientConnectionListener listener : listeners) {
            try {
                listener.onClientDisconnected(clientId, reason);
            } catch (RuntimeException e) {
                log.error("ConnectionListener.onClientDisconnected threw: {}", e.getMessage());
            }
        }
    }

    /** Fire callback ACTIVITY_REPORT cho mọi listener (Activity Monitoring). */
    public void fireActivityReport(ClientSession session,
            com.parental.common.protocol.payload.ActivityReportPayload activity) {
        for (ClientConnectionListener listener : listeners) {
            try {
                listener.onActivityReport(session, activity);
            } catch (RuntimeException e) {
                log.error("ConnectionListener.onActivityReport threw: {}", e.getMessage());
            }
        }
    }

    /**
     * Đăng ký phiên mới. Nếu clientId đã tồn tại (reconnect đè lên) thì phiên
     * cũ bị đóng trước khi thay thế (reason REPLACED) để không rò rỉ socket.
     * Fire callback {@code onClientConnected} cho phiên mới.
     */
    public void register(ClientSession session) {
        ClientSession old = sessions.put(session.getClientId(), session);
        if (old != null && old != session && !old.isClosed()) {
            log.warn("Duplicate clientId [{}]: closing stale session, replacing with new one",
                    session.getClientId());
            old.close();
            fireDisconnected(old.getClientId(), DisconnectReason.REPLACED);
        }
        log.info("Client [{}] registered ({} online)", session.getClientId(), count());
        fireConnected(session);
    }

    /**
     * Gỡ và đóng phiên của clientId (nếu còn tồn tại) với nguyên nhân
     * {@code reason}. Chỉ fire callback {@code onClientDisconnected} khi thực
     * sự gỡ được một phiên.
     */
    public void remove(String clientId, DisconnectReason reason) {
        if (clientId == null) {
            return;
        }
        ClientSession removed = sessions.remove(clientId);
        if (removed != null) {
            removed.close();
            log.info("Client [{}] removed ({} online)", clientId, count());
            fireDisconnected(clientId, reason);
        }
    }

    /** Gỡ và đóng phiên của clientId với nguyên nhân {@link DisconnectReason#UNKNOWN}. */
    public void remove(String clientId) {
        remove(clientId, DisconnectReason.UNKNOWN);
    }

    /**
     * Gỡ phiên cụ thể CHỈ khi nó vẫn là phiên đang được ánh xạ (remove
     * nguyên tử {@code remove(key, value)}) — tránh xóa nhầm phiên mới khi
     * client reconnect đè lên phiên cũ còn chậm dọn dẹp.
     */
    public void removeSession(ClientSession session) {
        removeSession(session, DisconnectReason.UNKNOWN);
    }

    /** Giống {@link #removeSession(ClientSession)} nhưng chỉ định nguyên nhân. */
    public void removeSession(ClientSession session, DisconnectReason reason) {
        if (session == null) {
            return;
        }
        if (sessions.remove(session.getClientId(), session)) {
            session.close();
            log.info("Client [{}] disconnected ({} online)", session.getClientId(), count());
            fireDisconnected(session.getClientId(), reason);
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
     * Đóng TOÀN BỘ phiên (graceful shutdown của server — Task 2.3) với nguyên
     * nhân {@link DisconnectReason#SERVER_SHUTDOWN}, fire callback cho từng
     * phiên rồi xóa sạch map. Idempotent.
     */
    public void closeAll() {
        closeAll(DisconnectReason.SERVER_SHUTDOWN);
    }

    /** Giống {@link #closeAll()} nhưng chỉ định nguyên nhân ngắt kết nối. */
    public void closeAll(DisconnectReason reason) {
        for (ClientSession session : getAllSessions()) {
            if (sessions.remove(session.getClientId(), session)) {
                session.close();
                log.info("Client [{}] closed ({})", session.getClientId(), reason);
                fireDisconnected(session.getClientId(), reason);
            }
        }
    }

    /**
     * Đẩy một gói tin tới tất cả client đang online. Phiên gửi lỗi sẽ bị gỡ
     * khỏi danh sách (được coi là dead connection, reason IO_ERROR).
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
                remove(session.getClientId(), DisconnectReason.IO_ERROR);
            }
        }
        return delivered;
    }

    /**
     * Quét và gỡ các phiên quá hạn: không nhận HEARTBEAT/bất kỳ dữ liệu nào
     * trong khoảng {@code timeoutSeconds} (chỉ áp dụng cho phiên đã handshake
     * — có deviceInfo). Mỗi phiên bị gỡ fire callback với reason
     * {@link DisconnectReason#HEARTBEAT_TIMEOUT}. Trả về danh sách clientId đã bị gỡ.
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
                remove(session.getClientId(), DisconnectReason.HEARTBEAT_TIMEOUT);
                removed.add(session.getClientId());
            }
        }
        return removed;
    }
}
