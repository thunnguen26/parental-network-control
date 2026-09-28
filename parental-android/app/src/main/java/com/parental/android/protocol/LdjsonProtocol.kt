package com.parental.android.protocol

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/** Lỗi parse giao thức — bọc mọi lỗi Jackson/malformed. */
class ProtocolParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Protocol Engine LDJSON — tương thích NGUYÊN VẸN với `JsonProtocolParser`
 * của parental-common (Mục 1–3 docs/PROTOCOL.md):
 *
 * - Framing Rule: mọi gói serialized kết thúc `\n` (LDJSON); deserialize
 *   chấp nhận dòng có/không `\n` cuối.
 * - Envelope Mục 2: `type`, `clientId`, `timestamp` (epoch seconds),
 *   `payload` — đủ 4 field bắt buộc khi parse.
 * - Lenient: field lạ BỎ QUA (FAIL_ON_UNKNOWN_PROPERTIES=false — không vỡ
 *   khi server mới hơn client); field thiếu nhận default.
 * - Type-dispatch payload giống hệt bảng parsePayload của Java.
 *
 * Envelope dùng Map thay data class để giữ hành vi NON_NULL + lenient
 * giống BaseMessage Java (field lạ trong envelope cũng được bỏ qua).
 */
class LdjsonProtocol private constructor(private val mapper: ObjectMapper) {

    companion object {
        const val FRAME_DELIMITER = '\n'

        /** Mapper chuẩn: Kotlin module + bỏ qua field lạ (giống configure() Java). */
        fun createMapper(): ObjectMapper =
            ObjectMapper()
                .registerModule(kotlinModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** Instance mặc định dùng chung (thread-safe — ObjectMapper stateless). */
        val DEFAULT = LdjsonProtocol(createMapper())
    }

    // ------------------------------------------------------------------
    // Serialize
    // ------------------------------------------------------------------

    /**
     * Chuyển envelope thành 1 dòng LDJSON kết thúc `\n`.
     *
     * @param type      loại gói tin (enum tên khớp Java)
     * @param clientId  định danh bền vững của thiết bị
     * @param timestamp epoch SECONDS (Mục 2)
     * @param payload   object payload tương ứng schema Mục 3
     */
    fun toLine(type: MessageType, clientId: String, timestamp: Long, payload: Any?): String {
        val envelope = linkedMapOf<String, Any?>(
            "type" to type.name,
            "clientId" to clientId,
            "timestamp" to timestamp,
            "payload" to payload
        )
        return try {
            // NON_NULL: property null bị bỏ (khớp @JsonInclude(NON_NULL) Java)
            mapper.copy().setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
            ).writeValueAsString(envelope) + FRAME_DELIMITER
        } catch (e: Exception) {
            throw ProtocolParseException("Serialization failed for $type", e)
        }
    }

    /** Tiện lợi: timestamp = hiện tại (epoch seconds). */
    fun toLine(type: MessageType, clientId: String, payload: Any?): String =
        toLine(type, clientId, System.currentTimeMillis() / 1000, payload)

    // ------------------------------------------------------------------
    // Deserialize
    // ------------------------------------------------------------------

    /** Envelope đã parse — payload typed theo [type]. */
    data class Parsed(
        val type: MessageType,
        val clientId: String,
        val timestamp: Long,
        val payload: Any?
    )

    /**
     * Giải mã 1 dòng LDJSON — type-dispatch payload khớp parsePayload() Java:
     * HANDSHAKE→HandshakePayload, METRICS_REPORT→MetricsPayload, COMMAND→
     * CommandPayload, CONFIG_SYNC→ConfigSyncPayload, PAIR_REQUEST→
     * PairRequestPayload, PAIR_RESPONSE→PairResponsePayload, UNPAIR→
     * UnpairPayload, ACTIVITY_REPORT→ActivityReportPayload, HEARTBEAT/ACK→Map.
     */
    fun fromLine(line: String?): Parsed {
        if (line.isNullOrBlank()) {
            throw ProtocolParseException("Cannot deserialize: line is null or blank")
        }
        val json = line.trim().trimEnd(FRAME_DELIMITER).trim()
        if (json.isEmpty()) {
            throw ProtocolParseException("Cannot deserialize: line is blank")
        }

        val root: Map<String, Any?> = try {
            mapper.readValue(json)
        } catch (e: Exception) {
            throw ProtocolParseException("Malformed JSON: ${json.take(120)}", e)
        }

        val typeName = requireText(root, "type")
        val type = try {
            MessageType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            throw ProtocolParseException("Unknown message type: $typeName")
        }

        val clientId = requireText(root, "clientId")
        val timestamp = (root["timestamp"] as? Number)?.toLong()
            ?: throw ProtocolParseException("Missing required field: timestamp")
        if (!root.containsKey("payload")) {
            throw ProtocolParseException("Missing required field: payload")
        }

        val payloadNode = root["payload"] ?: emptyPayload(type)
        return Parsed(type, clientId, timestamp, parsePayload(type, payloadNode))
    }

    private fun emptyPayload(type: MessageType): Map<String, Any?> =
        when (type) {
            MessageType.HEARTBEAT, MessageType.ACK -> emptyMap()
            else -> throw ProtocolParseException("Missing required field: payload")
        }

    private fun parsePayload(type: MessageType, raw: Any?): Any? = when (type) {
        MessageType.HANDSHAKE -> mapper.convertValue(raw, HandshakePayload::class.java)
        MessageType.METRICS_REPORT -> mapper.convertValue(raw, MetricsPayload::class.java)
        MessageType.COMMAND -> mapper.convertValue(raw, CommandPayload::class.java)
        MessageType.CONFIG_SYNC -> mapper.convertValue(raw, ConfigSyncPayload::class.java)
        MessageType.PAIR_REQUEST -> mapper.convertValue(raw, PairRequestPayload::class.java)
        MessageType.PAIR_RESPONSE -> mapper.convertValue(raw, PairResponsePayload::class.java)
        MessageType.UNPAIR -> mapper.convertValue(raw, UnpairPayload::class.java)
        MessageType.ACTIVITY_REPORT -> mapper.convertValue(raw, ActivityReportPayload::class.java)
        // HEARTBEAT / ACK: payload rỗng Map (khớp Java parse Map.class)
        MessageType.HEARTBEAT, MessageType.ACK ->
            mapper.convertValue(raw, Map::class.java)
    }

    private fun requireText(root: Map<String, Any?>, field: String): String {
        val value = root[field]
            ?: throw ProtocolParseException("Missing required field: $field")
        val text = value.toString()
        if (text.isBlank()) {
            throw ProtocolParseException("Missing required field: $field")
        }
        return text
    }
}
