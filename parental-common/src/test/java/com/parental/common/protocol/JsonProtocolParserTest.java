package com.parental.common.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.common.protocol.payload.PairRequestPayload;
import com.parental.common.protocol.payload.PairResponsePayload;

@DisplayName("JsonProtocolParser (LDJSON Protocol Engine)")
class JsonProtocolParserTest {

    private JsonProtocolParser parser;

    @BeforeEach
    void setUp() {
        parser = new JsonProtocolParser();
    }

    // ------------------------------------------------------------------
    // Round-trip: serialize -> deserialize
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Round-trip 4 loại payload chính")
    class RoundTrip {

        @Test
        @DisplayName("HANDSHAKE giữ nguyên dữ liệu")
        void handshakeRoundTrip() throws Exception {
            HandshakePayload payload = new HandshakePayload(
                    "Laptop-ConTrai", "DESKTOP", "Windows 11", "192.168.1.50", "AA-BB-CC-DD-EE-FF");
            BaseMessage<HandshakePayload> sent =
                    BaseMessage.of(MessageType.HANDSHAKE, "AA-BB-CC-DD-EE-FF", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.HANDSHAKE, received.getType());
            assertEquals("AA-BB-CC-DD-EE-FF", received.getClientId());
            assertEquals(sent.getTimestamp(), received.getTimestamp());
            assertInstanceOf(HandshakePayload.class, received.getPayload());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("METRICS_REPORT giữ nguyên dữ liệu")
        void metricsRoundTrip() throws Exception {
            MetricsPayload payload = new MetricsPayload(
                    104857600L, 52428800L, 2097152L, 524288L, 3600L, "ACTIVE");
            BaseMessage<MetricsPayload> sent =
                    BaseMessage.of(MessageType.METRICS_REPORT, "client-01", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.METRICS_REPORT, received.getType());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("COMMAND giữ nguyên dữ liệu, cả enum lồng nhau")
        void commandRoundTrip() throws Exception {
            CommandPayload payload = new CommandPayload(
                    "f47ac10b-58cc-4372-a567-0e02b2c3d479", ActionType.PAUSE_NETWORK,
                    "Da vuot gioi han thoi gian su dung trong ngay");
            BaseMessage<CommandPayload> sent =
                    BaseMessage.of(MessageType.COMMAND, "server", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.COMMAND, received.getType());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("CONFIG_SYNC giữ nguyên dữ liệu, cả List<String>")
        void configSyncRoundTrip() throws Exception {
            ConfigSyncPayload payload = new ConfigSyncPayload(
                    List.of("tiktok.com", "facebook.com"), 14400L, 5368709120L, "06:00", "22:00");
            BaseMessage<ConfigSyncPayload> sent =
                    BaseMessage.of(MessageType.CONFIG_SYNC, "client-02", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.CONFIG_SYNC, received.getType());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("HEARTBEAT / ACK giải mã thành Map rỗng")
        void heartbeatAndAckRoundTrip() throws Exception {
            BaseMessage<Map<String, Object>> heartbeat =
                    BaseMessage.of(MessageType.HEARTBEAT, "client-01", Map.of());
            BaseMessage<?> receivedHeartbeat = parser.fromLine(parser.toLine(heartbeat));
            assertEquals(MessageType.HEARTBEAT, receivedHeartbeat.getType());
            assertInstanceOf(Map.class, receivedHeartbeat.getPayload());
            assertTrue(((Map<?, ?>) receivedHeartbeat.getPayload()).isEmpty());

            BaseMessage<?> ack = parser.fromLine(
                    "{\"type\":\"ACK\",\"clientId\":\"client-01\",\"timestamp\":1727280000,\"payload\":{}}\n");
            assertEquals(MessageType.ACK, ack.getType());
            assertInstanceOf(Map.class, ack.getPayload());
        }

        @Test
        @DisplayName("PAIR_REQUEST giữ nguyên dữ liệu (Device Pairing)")
        void pairRequestRoundTrip() throws Exception {
            PairRequestPayload payload = new PairRequestPayload(
                    "482913", "Laptop-ConTrai", "DESKTOP", "Windows 11",
                    "192.168.1.50", "AA-BB-CC-DD-EE-FF");
            BaseMessage<PairRequestPayload> sent =
                    BaseMessage.of(MessageType.PAIR_REQUEST, "550e8400-e29b-41d4-a716-446655440000", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.PAIR_REQUEST, received.getType());
            assertInstanceOf(PairRequestPayload.class, received.getPayload());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("PAIR_RESPONSE giữ nguyên dữ liệu (success + deviceToken)")
        void pairResponseRoundTrip() throws Exception {
            PairResponsePayload payload = new PairResponsePayload(
                    true, "550e8400-e29b-41d4-a716-446655440000", "PAIRED");
            BaseMessage<PairResponsePayload> sent =
                    BaseMessage.of(MessageType.PAIR_RESPONSE, "server", payload);

            BaseMessage<?> received = parser.fromLine(parser.toLine(sent));

            assertEquals(MessageType.PAIR_RESPONSE, received.getType());
            assertInstanceOf(PairResponsePayload.class, received.getPayload());
            assertEquals(payload, received.getPayload());
        }

        @Test
        @DisplayName("HANDSHAKE có/không deviceToken đều round-trip đúng (Mục 3.1 nullable)")
        void handshakeDeviceTokenRoundTrip() throws Exception {
            HandshakePayload paired = new HandshakePayload(
                    "Laptop-ConTrai", "DESKTOP", "Windows 11", "192.168.1.50",
                    "AA-BB-CC-DD-EE-FF", "token-uuid-123");
            BaseMessage<?> received = parser.fromLine(parser.toLine(
                    BaseMessage.of(MessageType.HANDSHAKE, "client-01", paired)));
            assertEquals("token-uuid-123", ((HandshakePayload) received.getPayload()).deviceToken());

            // Không token (legacy) → parse lenient, deviceToken = null
            BaseMessage<?> legacy = parser.fromLine(
                    "{\"type\":\"HANDSHAKE\",\"clientId\":\"client-01\",\"timestamp\":1727280000,"
                            + "\"payload\":{\"deviceName\":\"PC\",\"deviceType\":\"DESKTOP\","
                            + "\"osVersion\":\"Windows 11\",\"ipAddress\":\"127.0.0.1\","
                            + "\"macAddress\":\"AA\"}}\n");
            assertNull(((HandshakePayload) legacy.getPayload()).deviceToken());
        }
    }

    // ------------------------------------------------------------------
    // Framing Rule
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Framing Rule LDJSON")
    class Framing {

        @Test
        @DisplayName("toLine luôn kết thúc bằng đúng 1 ký tự \\n")
        void serializedLineEndsWithNewline() throws Exception {
            String line = parser.toLine(
                    BaseMessage.of(MessageType.HEARTBEAT, "client-01", Map.of()));
            assertTrue(line.endsWith("\n"), "Phải kết thúc bằng \\n");
            assertEquals('\n', line.charAt(line.length() - 1));
            assertTrue(line.charAt(line.length() - 2) != '\n', "Chỉ 1 ký tự \\n");
        }

        @Test
        @DisplayName("fromLine nhận cả dòng có và không có \\n (từ BufferedReader.readLine)")
        void fromLineToleratesMissingTrailingNewline() throws Exception {
            String withNewline = parser.toLine(
                    BaseMessage.of(MessageType.HEARTBEAT, "client-01", Map.of()));
            String withoutNewline = withNewline.substring(0, withNewline.length() - 1);

            BaseMessage<?> a = parser.fromLine(withNewline);
            BaseMessage<?> b = parser.fromLine(withoutNewline);

            assertEquals(MessageType.HEARTBEAT, a.getType());
            assertEquals(MessageType.HEARTBEAT, b.getType());
        }

        @Test
        @DisplayName("Nhiều dòng liên tiếp giữ nguyên ranh giới gói tin (LDJSON stream)")
        void consecutiveLinesKeepPacketBoundaries() throws Exception {
            String line1 = parser.toLine(BaseMessage.of(MessageType.HEARTBEAT, "c1", Map.of()));
            String line2 = parser.toLine(BaseMessage.of(MessageType.METRICS_REPORT, "c1",
                    new MetricsPayload(1, 2, 3, 4, 5, "ACTIVE")));

            // Mô phỏng BufferedReader.readLine() tách theo \n
            String[] frames = (line1 + line2).split("\n", -1);
            assertEquals(3, frames.length); // 2 gói tin + chuỗi rỗng sau \n cuối

            BaseMessage<?> m1 = parser.fromLine(frames[0]);
            BaseMessage<?> m2 = parser.fromLine(frames[1]);
            assertEquals(MessageType.HEARTBEAT, m1.getType());
            assertEquals(MessageType.METRICS_REPORT, m2.getType());
        }
    }

    // ------------------------------------------------------------------
    // Xử lý lỗi -> ProtocolParseException
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Xử lý lỗi")
    class ErrorHandling {

        @Test
        @DisplayName("JSON hỏng (cú pháp sai) -> ProtocolParseException")
        void malformedJsonThrowsProtocolException() {
            String broken = "{\"type\": HEARTBEAT,, \"payload\": {}}\n";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(broken));
            assertTrue(ex.getMessage().contains("Malformed JSON"));
        }

        @Test
        @DisplayName("Thiếu field bắt buộc 'type' -> ProtocolParseException")
        void missingTypeThrowsProtocolException() {
            String missingType = "{\"clientId\":\"c1\",\"timestamp\":1727280000,\"payload\":{}}";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(missingType));
            assertTrue(ex.getMessage().contains("type"));
        }

        @Test
        @DisplayName("type không nằm trong MessageType -> ProtocolParseException")
        void unknownTypeThrowsProtocolException() {
            assertThrows(ProtocolParseException.class, () -> parser.fromLine(
                    "{\"type\":\"NOT_A_TYPE\",\"clientId\":\"c1\",\"timestamp\":1,\"payload\":{}}"));
        }

        @Test
        @DisplayName("Thiếu field bắt buộc 'clientId' -> ProtocolParseException")
        void missingClientIdThrowsProtocolException() {
            String missingClientId = "{\"type\":\"HEARTBEAT\",\"timestamp\":1,\"payload\":{}}";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(missingClientId));
            assertTrue(ex.getMessage().contains("clientId"));
        }

        @Test
        @DisplayName("Thiếu field bắt buộc 'timestamp' -> ProtocolParseException")
        void missingTimestampThrowsProtocolException() {
            String missingTimestamp = "{\"type\":\"HEARTBEAT\",\"clientId\":\"c1\",\"payload\":{}}";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(missingTimestamp));
            assertTrue(ex.getMessage().contains("timestamp"));
        }

        @Test
        @DisplayName("Thiếu field bắt buộc 'payload' -> ProtocolParseException")
        void missingPayloadThrowsProtocolException() {
            String missingPayload = "{\"type\":\"HEARTBEAT\",\"clientId\":\"c1\",\"timestamp\":1}";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(missingPayload));
            assertTrue(ex.getMessage().contains("payload"));
        }

        @Test
        @DisplayName("Payload sai schema (field có kiểu lệch) -> ProtocolParseException")
        void wrongPayloadSchemaThrowsProtocolException() {
            // CommandPayload.action nhận enum, đây là số ngoài phạm vi enum -> lỗi payload
            String badPayload = "{\"type\":\"COMMAND\",\"clientId\":\"s\",\"timestamp\":1,"
                    + "\"payload\":{\"commandId\":\"abc\",\"action\":12345,\"reason\":\"x\"}}";
            ProtocolParseException ex = assertThrows(ProtocolParseException.class,
                    () -> parser.fromLine(badPayload));
            assertTrue(ex.getMessage().contains("COMMAND"));
        }

        @Test
        @DisplayName("Field DTO thiếu -> lenient default (null/0), không ném")
        void missingPayloadDtoFieldsDefaultLeniently() {
            String badPayload = "{\"type\":\"CONFIG_SYNC\",\"clientId\":\"s\",\"timestamp\":1,"
                    + "\"payload\":{\"blockedDomains\":[]}}";
            BaseMessage<?> message = assertDoesNotThrow(() -> parser.fromLine(badPayload));
            ConfigSyncPayload payload = assertInstanceOf(ConfigSyncPayload.class, message.getPayload());
            assertNull(payload.allowedStartTime());
            assertEquals(0L, payload.dailyUsageLimitSeconds());
        }

        @Test
        @DisplayName("Dòng rỗng/null -> ProtocolParseException")
        void nullOrBlankLineThrowsProtocolException() {
            assertThrows(ProtocolParseException.class, () -> parser.fromLine(null));
            assertThrows(ProtocolParseException.class, () -> parser.fromLine("   \n"));
        }

        @Test
        @DisplayName("Unknown field lạ không làm vỡ parser (FAIL_ON_UNKNOWN_PROPERTIES = false)")
        void unknownFieldsAreIgnored() {
            String extraField = "{\"type\":\"HEARTBEAT\",\"clientId\":\"c1\",\"timestamp\":1,"
                    + "\"payload\":{},\"futureField\":{\"x\":1}}";
            BaseMessage<?> message = assertDoesNotThrow(() -> parser.fromLine(extraField));
            assertEquals(MessageType.HEARTBEAT, message.getType());
        }
    }
}
