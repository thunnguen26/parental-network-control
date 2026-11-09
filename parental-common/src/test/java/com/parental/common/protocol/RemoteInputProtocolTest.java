package com.parental.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;

/** Round-trip 3 payload điều khiển chuột/bàn phím (PROTOCOL.md Mục 3.11). */
class RemoteInputProtocolTest {

    private final JsonProtocolParser parser = new JsonProtocolParser();

    @Test
    @DisplayName("INPUT_CONTROL REQUEST/STOP round-trip")
    void controlRoundTrip() throws Exception {
        RemoteInputControlPayload payload = new RemoteInputControlPayload(
                "session-1", RemoteInputControlPayload.ACTION_REQUEST, 20, 300);
        BaseMessage<?> decoded = parser.fromLine(parser.toLine(
                BaseMessage.of(MessageType.INPUT_CONTROL, "server", payload)));

        assertEquals(MessageType.INPUT_CONTROL, decoded.getType());
        RemoteInputControlPayload control =
                assertInstanceOf(RemoteInputControlPayload.class, decoded.getPayload());
        assertEquals("session-1", control.sessionId());
        assertEquals("REQUEST", control.action());
        assertEquals(20, control.effectiveConsentTimeoutSeconds());
        assertEquals(300, control.effectiveMaxSessionSeconds());
    }

    @Test
    @DisplayName("INPUT_EVENT round-trip giữ session/type/tọa độ/frame/seq")
    void eventRoundTrip() throws Exception {
        RemoteInputEventPayload payload = new RemoteInputEventPayload(
                "session-2", RemoteInputEventPayload.MOUSE_MOVE,
                480, 270, 0, 0, 960, 540, 42);
        BaseMessage<?> decoded = parser.fromLine(parser.toLine(
                BaseMessage.of(MessageType.INPUT_EVENT, "server", payload)));

        assertEquals(MessageType.INPUT_EVENT, decoded.getType());
        RemoteInputEventPayload event =
                assertInstanceOf(RemoteInputEventPayload.class, decoded.getPayload());
        assertEquals(payload, event);
        assertTrue(RemoteInputEventPayload.isSupportedEventType(event.eventType()));
    }

    @Test
    @DisplayName("INPUT_ACK round-trip giữ status + mốc hết hạn")
    void ackRoundTrip() throws Exception {
        RemoteInputAckPayload payload = new RemoteInputAckPayload(
                "session-3", RemoteInputAckPayload.STATUS_ACCEPTED,
                "Người dùng tại Client đã chấp nhận", 1_700_000_000_000L,
                1_700_000_300_000L);
        BaseMessage<?> decoded = parser.fromLine(parser.toLine(
                BaseMessage.of(MessageType.INPUT_ACK, "client-1", payload)));

        assertEquals(MessageType.INPUT_ACK, decoded.getType());
        RemoteInputAckPayload ack =
                assertInstanceOf(RemoteInputAckPayload.class, decoded.getPayload());
        assertEquals(payload, ack);
        assertTrue(ack.isAccepted());
    }

    @Test
    @DisplayName("Field lạ trong payload mới được bỏ qua (tương thích mở rộng)")
    void unknownFieldsAreIgnored() throws Exception {
        BaseMessage<?> decoded = parser.fromLine(
                "{\"type\":\"INPUT_EVENT\",\"clientId\":\"server\",\"timestamp\":1,"
                        + "\"payload\":{\"sessionId\":\"s\",\"eventType\":\"KEY_DOWN\","
                        + "\"x\":0,\"y\":0,\"button\":0,\"keyCode\":65,"
                        + "\"frameWidth\":1,\"frameHeight\":1,\"seqNo\":1,"
                        + "\"futureField\":true}}");
        RemoteInputEventPayload event =
                assertInstanceOf(RemoteInputEventPayload.class, decoded.getPayload());
        assertEquals(65, event.keyCode());
    }
}
