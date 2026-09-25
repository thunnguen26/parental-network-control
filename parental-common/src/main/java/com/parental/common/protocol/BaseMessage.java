package com.parental.common.protocol;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Phong bì (envelope) chuẩn cho mọi gói tin LDJSON, khớp Mục 2 của
 * {@code docs/PROTOCOL.md}: type, clientId, timestamp (epoch seconds), payload.
 *
 * @param <T> kiểu payload tương ứng với {@link MessageType}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseMessage<T> {

    private MessageType type;
    private String clientId;
    private long timestamp; // epoch seconds
    private T payload;

    /** Bắt buộc cho Jackson khi giải mã LDJSON. */
    public BaseMessage() {
    }

    public BaseMessage(MessageType type, String clientId, long timestamp, T payload) {
        this.type = type;
        this.clientId = clientId;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    /** Tạo gói tin với timestamp = thời điểm hiện tại (epoch seconds). */
    public static <T> BaseMessage<T> of(MessageType type, String clientId, T payload) {
        return new BaseMessage<>(type, clientId, Instant.now().getEpochSecond(), payload);
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public T getPayload() {
        return payload;
    }

    public void setPayload(T payload) {
        this.payload = payload;
    }

    @Override
    public String toString() {
        return "BaseMessage{type=" + type + ", clientId='" + clientId
                + "', timestamp=" + timestamp + ", payload=" + payload + '}';
    }
}
