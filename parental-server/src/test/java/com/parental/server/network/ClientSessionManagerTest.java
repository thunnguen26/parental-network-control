package com.parental.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Unit test cho {@link ClientSessionManager} — Task 2.3: listener sự kiện kết
 * nối (onClientConnected / onClientDisconnected kèm {@link DisconnectReason})
 * cho từng đường gỡ session: sweep HEARTBEAT_TIMEOUT, register đè REPLACED,
 * closeAll SERVER_SHUTDOWN.
 */
class ClientSessionManagerTest {

    /** Listener ghi nhận sự kiện để assert. */
    private static final class RecordingListener implements ClientConnectionListener {
        final List<String> connected = new CopyOnWriteArrayList<>();
        final List<String> disconnected = new CopyOnWriteArrayList<>();
        final List<DisconnectReason> reasons = new CopyOnWriteArrayList<>();

        @Override
        public void onClientConnected(ClientSession session) {
            connected.add(session.getClientId());
        }

        @Override
        public void onClientDisconnected(String clientId, DisconnectReason reason) {
            disconnected.add(clientId);
            reasons.add(reason);
        }
    }

    /**
     * Session giả lập không cần socket thật: socket null — {@code close()}
     * của {@link ClientSession} đã chống NPE (Task 2.3) nên an toàn khi gọi.
     */
    private ClientSession newSession(String clientId) {
        return new ClientSession(clientId, null, null, null, null);
    }

    @Test
    @DisplayName("register -> fire onClientConnected đúng session")
    void registerFiresConnectedCallback() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);

        manager.register(newSession("client-01"));

        assertEquals(List.of("client-01"), listener.connected);
        assertTrue(listener.disconnected.isEmpty());
    }

    @Test
    @DisplayName("remove(clientId, HEARTBEAT_TIMEOUT) -> fire onClientDisconnected kèm reason")
    void removeWithReasonFiresDisconnectedCallback() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);
        manager.register(newSession("client-01"));

        manager.remove("client-01", DisconnectReason.HEARTBEAT_TIMEOUT);

        assertEquals(List.of("client-01"), listener.disconnected);
        assertEquals(List.of(DisconnectReason.HEARTBEAT_TIMEOUT), listener.reasons);
    }

    @Test
    @DisplayName("remove clientId không tồn tại -> không fire callback")
    void removeUnknownClientDoesNotFireCallback() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);

        manager.remove("ghost", DisconnectReason.HEARTBEAT_TIMEOUT);

        assertTrue(listener.disconnected.isEmpty());
    }

    @Test
    @DisplayName("register đè clientId cũ -> phiên cũ bị đóng, callback REPLACED cho cũ + connected cho mới")
    void replaceOldSessionFiresReplacedCallback() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);

        ClientSession oldSession = newSession("client-01");
        manager.register(oldSession);
        manager.register(newSession("client-01")); // reconnect đè

        assertEquals(2, listener.connected.size(), "connected phải fire cho cả 2 phiên");
        assertEquals(List.of("client-01"), listener.disconnected);
        assertEquals(List.of(DisconnectReason.REPLACED), listener.reasons);
    }

    @Test
    @DisplayName("sweepDeadSessions -> callback HEARTBEAT_TIMEOUT cho từng phiên quá hạn")
    void sweepFiresHeartbeatTimeoutCallback() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);

        ClientSession alive = newSession("alive");
        alive.setDeviceInfo(new HandshakePayload("PC-1", "DESKTOP", "Windows 11", "127.0.0.1", "AA-BB"));
        alive.setLastHeartbeatTimestamp(java.time.Instant.now().getEpochSecond()); // mới đây
        manager.register(alive);

        ClientSession dead = newSession("dead");
        dead.setDeviceInfo(new HandshakePayload("PC-2", "DESKTOP", "Windows 11", "127.0.0.1", "AA-CC"));
        dead.setLastHeartbeatTimestamp(java.time.Instant.now().getEpochSecond() - 60); // quá hạn
        manager.register(dead);

        List<String> swept = manager.sweepDeadSessions(15);

        assertEquals(List.of("dead"), swept);
        assertEquals(List.of("dead"), listener.disconnected);
        assertEquals(List.of(DisconnectReason.HEARTBEAT_TIMEOUT), listener.reasons);
        assertEquals(1, manager.count());
    }

    @Test
    @DisplayName("closeAll -> đóng mọi phiên + callback SERVER_SHUTDOWN cho từng phiên")
    void closeAllFiresServerShutdownCallbacks() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);
        manager.register(newSession("c1"));
        manager.register(newSession("c2"));

        manager.closeAll(DisconnectReason.SERVER_SHUTDOWN);

        assertEquals(0, manager.count());
        assertEquals(2, listener.disconnected.size());
        assertTrue(listener.reasons.stream().allMatch(r -> r == DisconnectReason.SERVER_SHUTDOWN),
                "Mọi callback phải mang reason SERVER_SHUTDOWN");
    }

    @Test
    @DisplayName("removeSession(session, reason) chỉ fire khi session vẫn đang được ánh xạ")
    void removeSessionFiresOnlyWhenMapped() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);

        ClientSession session = newSession("client-01");
        manager.register(session);

        ClientSession stale = newSession("client-01"); // phiên cũ đã bị thay
        manager.removeSession(stale, DisconnectReason.CLIENT_DISCONNECTED);
        assertTrue(listener.disconnected.isEmpty(), "Phiên stale không được fire callback");

        manager.removeSession(session, DisconnectReason.CLIENT_DISCONNECTED);
        assertEquals(List.of("client-01"), listener.disconnected);
        assertEquals(List.of(DisconnectReason.CLIENT_DISCONNECTED), listener.reasons);
    }

    @Test
    @DisplayName("Listener ném RuntimeException -> callback khác vẫn được gọi, manager không chết")
    void listenerExceptionDoesNotBlockOtherListeners() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener good = new RecordingListener();
        manager.addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onClientConnected(ClientSession session) {
                throw new IllegalStateException("boom");
            }
        });
        manager.addConnectionListener(good);

        manager.register(newSession("client-01"));
        manager.remove("client-01", DisconnectReason.UNKNOWN);

        assertEquals(List.of("client-01"), good.connected);
        assertEquals(List.of("client-01"), good.disconnected);
    }

    @Test
    @DisplayName("removeConnectionListener -> không còn nhận callback")
    void removedListenerStopsReceivingEvents() {
        ClientSessionManager manager = new ClientSessionManager();
        RecordingListener listener = new RecordingListener();
        manager.addConnectionListener(listener);
        manager.removeConnectionListener(listener);

        manager.register(newSession("client-01"));
        manager.remove("client-01", DisconnectReason.UNKNOWN);

        assertTrue(listener.connected.isEmpty());
        assertTrue(listener.disconnected.isEmpty());
        assertFalse(manager.get("client-01") != null);
    }
}
