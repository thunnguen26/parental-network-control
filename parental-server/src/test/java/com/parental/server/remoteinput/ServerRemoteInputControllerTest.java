package com.parental.server.remoteinput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/** Kiểm thử headless cho phiên điều khiển phía Server (PROTOCOL.md Mục 3.11). */
class ServerRemoteInputControllerTest {

    private final AtomicLong now = new AtomicLong(10_000L);
    private FakeTransport transport;
    private ServerRemoteInputController controller;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        controller = new ServerRemoteInputController("client-1", transport, now::get, 10, 60);
    }

    @Test
    @DisplayName("Yêu cầu điều khiển gửi REQUEST và chờ chấp thuận, chưa gửi input")
    void requestSendsControlAndWaits() {
        String sessionId = controller.requestControl();

        assertNotNull(sessionId);
        assertEquals(ServerRemoteInputController.State.WAITING_CONSENT,
                controller.getState());
        assertEquals(1, transport.count(MessageType.INPUT_CONTROL));
        RemoteInputControlPayload control = transport.last(MessageType.INPUT_CONTROL,
                RemoteInputControlPayload.class);
        assertEquals(RemoteInputControlPayload.ACTION_REQUEST, control.action());
        assertEquals(sessionId, control.sessionId());

        assertEquals(ServerRemoteInputController.SendResult.NOT_ACTIVE,
                controller.sendMouseMove(10, 10, 100, 100));
        assertEquals(0, transport.count(MessageType.INPUT_EVENT));
    }

    @Test
    @DisplayName("Thiết bị offline: không gửi yêu cầu, không bật phiên")
    void offlineClientCannotBeControlled() {
        transport.online = false;

        assertNull(controller.requestControl());
        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertEquals(0, transport.sent.size());
    }

    @Test
    @DisplayName("Chỉ ACCEPTED đúng session mới kích hoạt; ACK phiên khác bị bỏ qua")
    void onlyMatchingAcceptedAckActivates() {
        String sessionId = controller.requestControl();

        assertFalse(controller.onAck(ack("other-session",
                RemoteInputAckPayload.STATUS_ACCEPTED)));
        assertEquals(ServerRemoteInputController.State.WAITING_CONSENT,
                controller.getState());

        assertTrue(controller.onAck(ack(sessionId,
                RemoteInputAckPayload.STATUS_ACCEPTED)));
        assertEquals(ServerRemoteInputController.State.ACTIVE, controller.getState());

        assertEquals(ServerRemoteInputController.SendResult.SENT,
                controller.sendMouseMove(10, 20, 100, 100));
        RemoteInputEventPayload event = transport.last(MessageType.INPUT_EVENT,
                RemoteInputEventPayload.class);
        assertEquals(sessionId, event.sessionId());
        assertEquals(RemoteInputEventPayload.MOUSE_MOVE, event.eventType());
        assertEquals(1, event.seqNo());
    }

    @Test
    @DisplayName("Client từ chối: dừng phiên, mọi sự kiện sau bị từ chối")
    void declinedAckEndsSession() {
        String sessionId = controller.requestControl();
        controller.onAck(ack(sessionId, RemoteInputAckPayload.STATUS_DECLINED));

        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertEquals(ServerRemoteInputController.SendResult.NOT_ACTIVE,
                controller.sendMouseMove(1, 1, 100, 100));
        assertEquals(0, transport.count(MessageType.INPUT_EVENT));
    }

    @Test
    @DisplayName("Không phản hồi: hết hạn chờ, gửi STOP để Client thu hồi nếu ACK ACCEPTED bị mất")
    void consentTimeoutEndsWaiting() {
        String sessionId = controller.requestControl();
        now.addAndGet(10_001L);
        controller.expireIfNeeded();

        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertTrue(controller.getLastStatus().contains("không phản hồi")
                || controller.getLastStatus().contains("không hỗ trợ"));
        assertEquals(0, transport.count(MessageType.INPUT_EVENT));
        assertTrue(transport.sent.stream().anyMatch(message ->
                        message.getType() == MessageType.INPUT_CONTROL
                                && RemoteInputControlPayload.ACTION_STOP.equals(
                                        ((RemoteInputControlPayload) message.getPayload()).action())
                                && sessionId.equals(((RemoteInputControlPayload)
                                        message.getPayload()).sessionId())),
                "Hết hạn chờ phải gửi INPUT_CONTROL STOP cho đúng phiên");
    }

    @Test
    @DisplayName("Hết thời lượng: gửi STOP, thu hồi quyền, gói trễ không khôi phục")
    void sessionExpirySendsStopAndCannotBeRevived() {
        String sessionId = controller.requestControl();
        controller.onAck(new RemoteInputAckPayload(sessionId,
                RemoteInputAckPayload.STATUS_ACCEPTED, "", now.get(),
                now.get() + 1_000L));
        assertEquals(ServerRemoteInputController.State.ACTIVE, controller.getState());

        now.addAndGet(1_001L);
        controller.expireIfNeeded();

        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertTrue(transport.sent.stream().anyMatch(m ->
                m.getType() == MessageType.INPUT_CONTROL
                        && RemoteInputControlPayload.ACTION_STOP.equals(
                                ((RemoteInputControlPayload) m.getPayload()).action())));
        assertFalse(controller.onAck(ack(sessionId, RemoteInputAckPayload.STATUS_ACCEPTED)));
        assertEquals(ServerRemoteInputController.SendResult.NOT_ACTIVE,
                controller.sendMouseMove(1, 1, 100, 100));
    }

    @Test
    @DisplayName("Tọa độ/loại/nút/phím/frame không hợp lệ bị từ chối, không gửi")
    void invalidEventsAreRejected() {
        activate();

        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendMouseMove(100, 10, 100, 100));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendMouseMove(-1, 10, 100, 100));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendEvent("PASTE_TEXT", 0, 0, 0, 0, 100, 100));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendKey(true, 300));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendMouseButton(true, 9, 10, 10, 100, 100));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendMouseWheel(11, 10, 10, 100, 100));
        assertEquals(ServerRemoteInputController.SendResult.INVALID,
                controller.sendMouseMove(10, 10, 5_000, 100));

        assertEquals(0, transport.count(MessageType.INPUT_EVENT));
    }

    @Test
    @DisplayName("Vượt giới hạn tốc độ: phần dư bị bỏ, không gửi")
    void rateLimitDropsExcessEvents() {
        activate();
        int sent = 0;
        int limited = 0;
        for (int i = 0; i < ServerRemoteInputController.MAX_EVENTS_PER_SECOND + 30; i++) {
            ServerRemoteInputController.SendResult result = controller.sendKey(true, 65);
            if (result == ServerRemoteInputController.SendResult.SENT) {
                sent++;
            } else if (result == ServerRemoteInputController.SendResult.RATE_LIMITED) {
                limited++;
            }
        }
        assertEquals(ServerRemoteInputController.MAX_EVENTS_PER_SECOND, sent);
        assertEquals(30, limited);
        assertEquals(sent, transport.count(MessageType.INPUT_EVENT));
    }

    @Test
    @DisplayName("Viewer dừng: gửi STOP tới client, chặn mọi event sau")
    void stopSendsControlAndBlocksEvents() {
        activate();
        controller.stop("Viewer đóng");

        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertTrue(transport.sent.stream().anyMatch(m ->
                m.getType() == MessageType.INPUT_CONTROL
                        && RemoteInputControlPayload.ACTION_STOP.equals(
                                ((RemoteInputControlPayload) m.getPayload()).action())));
        assertEquals(ServerRemoteInputController.SendResult.NOT_ACTIVE,
                controller.sendMouseMove(10, 10, 100, 100));
    }

    @Test
    @DisplayName("Gửi input thất bại: phiên dừng ngay, không tiếp tục gửi")
    void sendFailureStopsSession() {
        activate();
        transport.failEvents = true;

        assertEquals(ServerRemoteInputController.SendResult.SEND_FAILED,
                controller.sendMouseMove(10, 10, 100, 100));
        assertEquals(ServerRemoteInputController.State.STOPPED, controller.getState());
        assertEquals(ServerRemoteInputController.SendResult.NOT_ACTIVE,
                controller.sendMouseMove(11, 11, 100, 100));
    }

    private void activate() {
        String sessionId = controller.requestControl();
        controller.onAck(ack(sessionId, RemoteInputAckPayload.STATUS_ACCEPTED));
        assertEquals(ServerRemoteInputController.State.ACTIVE, controller.getState());
    }

    private static RemoteInputAckPayload ack(String sessionId, String status) {
        return RemoteInputAckPayload.of(sessionId, status, "");
    }

    static final class FakeTransport implements ServerRemoteInputController.Transport {
        final List<BaseMessage<?>> sent = new CopyOnWriteArrayList<>();
        boolean online = true;
        boolean failEvents;

        @Override
        public boolean isOnline() {
            return online;
        }

        @Override
        public boolean send(BaseMessage<?> message) {
            if (failEvents && message.getType() == MessageType.INPUT_EVENT) {
                return false;
            }
            sent.add(message);
            return true;
        }

        long count(MessageType type) {
            return sent.stream().filter(m -> m.getType() == type).count();
        }

        <T> T last(MessageType type, Class<T> payloadType) {
            return sent.stream().filter(m -> m.getType() == type)
                    .map(BaseMessage::getPayload)
                    .filter(payloadType::isInstance)
                    .map(payloadType::cast)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("No " + type + " sent"));
        }
    }
}
