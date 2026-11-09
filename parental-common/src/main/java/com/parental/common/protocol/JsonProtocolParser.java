package com.parental.common.protocol;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parental.common.protocol.payload.ActivityReportPayload;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.common.protocol.payload.PairRequestPayload;
import com.parental.common.protocol.payload.PairResponsePayload;
import com.parental.common.protocol.payload.RemoteInputAckPayload;
import com.parental.common.protocol.payload.RemoteInputControlPayload;
import com.parental.common.protocol.payload.RemoteInputEventPayload;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.common.protocol.payload.UnpairPayload;

/**
 * Protocol Engine của giao thức LDJSON (khớp Mục 1-3 của
 * {@code docs/PROTOCOL.md}).
 *
 * <p>Framing Rule: mọi gói tin serialized luôn kết thúc bằng {@code \n}
 * (dùng kèm {@code BufferedReader.readLine()} / {@code PrintWriter.println()}).</p>
 *
 * <p>Deserialize: 4 field bắt buộc theo Mục 2 ({@code type}, {@code clientId},
 * {@code timestamp}, {@code payload}) phải tồn tại; field lạ bị bỏ qua
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES = false}); field DTO thiếu nhận giá trị
 * mặc định (lenient).</p>
 */
public final class JsonProtocolParser {

    private static final char FRAME_DELIMITER = '\n';

    private final ObjectMapper mapper;

    public JsonProtocolParser() {
        this(new ObjectMapper());
    }

    public JsonProtocolParser(ObjectMapper mapper) {
        this.mapper = configure(mapper);
    }

    /** Cấu hình an toàn: bỏ qua field lạ để không vỡ khi protocol mở rộng. */
    private static ObjectMapper configure(ObjectMapper mapper) {
        return mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------
    // Serialize
    // ------------------------------------------------------------------

    /**
     * Chuyển {@link BaseMessage} thành 1 dòng JSON hoàn chỉnh, luôn kết thúc
     * bằng {@code \n} theo Framing Rule LDJSON.
     */
    public String toLine(BaseMessage<?> message) throws ProtocolParseException {
        if (message == null || message.getType() == null) {
            throw new ProtocolParseException("Cannot serialize: message and type must not be null");
        }
        try {
            return mapper.writeValueAsString(message) + FRAME_DELIMITER;
        } catch (JsonProcessingException e) {
            throw new ProtocolParseException("Serialization failed for " + message.getType(), e);
        }
    }

    // ------------------------------------------------------------------
    // Deserialize
    // ------------------------------------------------------------------

    /**
     * Giải mã một dòng LDJSON (có/không {@code \n} cuối đều được, cho phép
     * đầu vào từ {@code BufferedReader.readLine()}) thành {@link BaseMessage}
     * với payload đã được parse sang DTO tương ứng với trường {@code type}:
     * <ul>     * <li>HANDSHAKE → {@link HandshakePayload}</li>
     *   <li>METRICS_REPORT → {@link MetricsPayload}</li>
     *   <li>COMMAND → {@link CommandPayload}</li>
     *   <li>CONFIG_SYNC → {@link ConfigSyncPayload}</li>     * <li>PAIR_REQUEST → {@link PairRequestPayload}</li>
     *   <li>PAIR_RESPONSE → {@link PairResponsePayload}</li>     * <li>UNPAIR → {@link UnpairPayload}</li>
     *   <li>ACTIVITY_REPORT → {@link ActivityReportPayload}</li>
     *   <li>HEARTBEAT / ACK → {@link Map} rỗng (payload không có field)</li>
     * </ul>
     */
    public BaseMessage<?> fromLine(String line) throws ProtocolParseException {
        if (line == null || line.isBlank()) {
            throw new ProtocolParseException("Cannot deserialize: line is null or blank");
        }

        String json = stripFrameDelimiter(line);

        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new ProtocolParseException("Malformed JSON: " + summarize(json), e);
        }

        if (root == null || !root.isObject()) {
            throw new ProtocolParseException("Malformed JSON: root is not an object");
        }

        // type
        JsonNode typeNode = requireField(root, "type");
        if (typeNode.asText().isBlank()) {
            throw new ProtocolParseException("Missing required field: type");
        }
        MessageType type;
        try {
            type = MessageType.valueOf(typeNode.asText());
        } catch (IllegalArgumentException e) {
            throw new ProtocolParseException("Unknown message type: " + typeNode.asText());
        }

        // clientId, timestamp, payload: bắt buộc theo Mục 2 của PROTOCOL.md
        JsonNode clientIdNode = requireField(root, "clientId");
        JsonNode timestampNode = requireField(root, "timestamp");
        JsonNode payloadNode = requireField(root, "payload");

        BaseMessage<Object> message = new BaseMessage<>();
        message.setType(type);
        message.setClientId(clientIdNode.asText());
        message.setTimestamp(timestampNode.asLong(0L));
        message.setPayload(parsePayload(type, payloadNode));
        return message;
    }

    /** Chuyển payload thô (JsonNode) sang DTO tương ứng loại gói tin. */
    private Object parsePayload(MessageType type, JsonNode payloadNode)
            throws ProtocolParseException {
        if (payloadNode == null || payloadNode.isNull()) {
            payloadNode = mapper.createObjectNode();
        }

        Class<?> payloadClass = switch (type) {
            case HANDSHAKE -> HandshakePayload.class;
            case METRICS_REPORT -> MetricsPayload.class;
            case COMMAND -> CommandPayload.class;
            case CONFIG_SYNC -> ConfigSyncPayload.class;
            case PAIR_REQUEST -> PairRequestPayload.class;
            case PAIR_RESPONSE -> PairResponsePayload.class;
            case UNPAIR -> UnpairPayload.class;
            case ACTIVITY_REPORT -> ActivityReportPayload.class;
            case STREAM_CONTROL -> StreamControlPayload.class;
            case STREAM_FRAME -> ScreenFramePayload.class;
            case INPUT_CONTROL -> RemoteInputControlPayload.class;
            case INPUT_EVENT -> RemoteInputEventPayload.class;
            case INPUT_ACK -> RemoteInputAckPayload.class;
            case HEARTBEAT, ACK -> Map.class; // payload rỗng: {}
        };

        try {
            return mapper.treeToValue(payloadNode, payloadClass);
        } catch (JsonProcessingException e) {
            throw new ProtocolParseException(
                    "Payload does not match schema for " + type + " (" + payloadClass.getSimpleName() + ")", e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonNode requireField(JsonNode root, String field)
            throws ProtocolParseException {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new ProtocolParseException("Missing required field: " + field);
        }
        return node;
    }

    private static String stripFrameDelimiter(String line) {
        return line.endsWith("\r\n") ? line.substring(0, line.length() - 2)
                : line.endsWith("\n") ? line.substring(0, line.length() - 1)
                : line;
    }

    private static String summarize(String json) {
        return json.length() <= 120 ? json : json.substring(0, 120) + "...";
    }
}
