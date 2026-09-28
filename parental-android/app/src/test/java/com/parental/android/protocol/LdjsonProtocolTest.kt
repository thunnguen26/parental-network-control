package com.parental.android.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test JVM cho [LdjsonProtocol] — xác nhận tương thích NGUYÊN VẸN với
 * `JsonProtocolParser` của parental-common (tiêu chuẩn Mục 4 của nhiệm vụ:
 * "tuyệt đối tuân thủ PROTOCOL.md, không thay đổi định dạng gói tin JSON"):
 * mỗi test SERIALIZED từ Kotlin rồi DỊCH NGƯỢC bằng Jackson thuần (cách
 * server Java đọc) và so cấu trúc JSON đúng schema Mục 2/3.
 */
class LdjsonProtocolTest {

    private val protocol = LdjsonProtocol.DEFAULT
    private val javaStyleMapper = ObjectMapper() // "server Java" đọc — không Kotlin module

    // ------------------------------------------------------------------
    // Envelope Mục 2
    // ------------------------------------------------------------------

    @Test
    fun `envelope has exactly type clientIdd timestamp payload and ends with newline`() {
        val line = protocol.toLine(
            MessageType.HANDSHAKE, "client-1", 1727280000L,
            HandshakePayload("May Con", "MOBILE", "Android 14", "192.168.1.50", "AA-BB-CC")
        )
        assertTrue("LDJSON phải kết thúc \\n", line.endsWith("\n"))

        val root: JsonNode = javaStyleMapper.readTree(line)
        assertEquals(4, root.size(), "Envelope đúng 4 field Mục 2")
        assertEquals("HANDSHAKE", root.get("type").asText())
        assertEquals("client-1", root.get("clientId").asText())
        assertEquals(1727280000L, root.get("timestamp").asLong())
        assertTrue(root.get("payload").isObject)
    }

    @Test
    fun `timestamp is epoch seconds not millis`() {
        val line = protocol.toLine(MessageType.HEARTBEAT, "c", emptyMap<String, Any>())
        val ts = javaStyleMapper.readTree(line).get("timestamp").asLong()
        assertTrue("timestamp phải dạng giây (<10^12)", ts < 1_000_000_000_000L)
        assertTrue("timestamp phải gần hiện tại", ts > 1_500_000_000L)
    }

    // ------------------------------------------------------------------
    // HANDSHAKE Mục 3.1 — deviceToken null bị bỏ (NON_NULL)
    // ------------------------------------------------------------------

    @Test
    fun `handshake without deviceToken omits the field like Java NON_NULL`() {
        val line = protocol.toLine(
            MessageType.HANDSHAKE, "c",
            HandshakePayload("Samsung SM-A536E", "MOBILE", "Android 14", "192.168.1.50", "AA-BB-CC", null)
        )
        val payload = javaStyleMapper.readTree(line).get("payload")
        assertFalse(payload.has("deviceToken"), "token null phải bị bỏ — khớp @JsonInclude(NON_NULL)")
        assertEquals("MOBILE", payload.get("deviceType").asText())
        assertEquals("Android 14", payload.get("osVersion").asText())
        assertEquals("Samsung SM-A536E", payload.get("deviceName").asText())
        assertEquals("192.168.1.50", payload.get("ipAddress").asText())
        assertEquals("AA-BB-CC", payload.get("macAddress").asText())
    }

    @Test
    fun `handshake with deviceToken serializes the token`() {
        val line = protocol.toLine(
            MessageType.HANDSHAKE, "c",
            HandshakePayload("Dev", "MOBILE", "Android 14", "10.0.0.2", "MAC", "token-uuid-1")
        )
        assertEquals("token-uuid-1",
            javaStyleMapper.readTree(line).get("payload").get("deviceToken").asText())
    }

    // ------------------------------------------------------------------
    // HEARTBEAT / ACK Mục 3.5
    // ------------------------------------------------------------------

    @Test
    fun `heartbeat payload is empty object`() {
        val line = protocol.toLine(MessageType.HEARTBEAT, "c", emptyMap<String, Any>())
        val payload = javaStyleMapper.readTree(line).get("payload")
        assertTrue(payload.isObject)
        assertEquals(0, payload.size(), "HEARTBEAT payload là object rỗng {}")
    }

    @Test
    fun `ack with commandId serializes commandId`() {
        val line = protocol.toLine(
            MessageType.ACK, "c", AckPayload("cmd-42"))
        assertEquals("cmd-42",
            javaStyleMapper.readTree(line).get("payload").get("commandId").asText())
    }

    @Test
    fun `ack without commandId serializes empty payload`() {
        val line = protocol.toLine(MessageType.ACK, "c", AckPayload(null))
        assertEquals(0, javaStyleMapper.readTree(line).get("payload").size(),
            "ACK không commandId → payload rỗng (NON_NULL bỏ field null)")
    }

    // ------------------------------------------------------------------
    // PAIR_REQUEST / PAIR_RESPONSE Mục 3.6/3.7
    // ------------------------------------------------------------------

    @Test
    fun `pair_request matches section 3_6 schema exactly`() {
        val line = protocol.toLine(
            MessageType.PAIR_REQUEST, "uuid-1",
            PairRequestPayload("482913", "Samsung SM-A536E", "MOBILE", "Android 14",
                "192.168.1.50", "AA-BB-CC-DD-EE-FF")
        )
        val payload = javaStyleMapper.readTree(line).get("payload")
        assertEquals(6, payload.size(), "PAIR_REQUEST đúng 6 field")
        assertEquals("482913", payload.get("pin").asText())
        assertEquals("MOBILE", payload.get("deviceType").asText())
        assertEquals("Android 14", payload.get("osVersion").asText())
        assertEquals("192.168.1.50", payload.get("ipAddress").asText())
        assertEquals("AA-BB-CC-DD-EE-FF", payload.get("macAddress").asText())
        assertEquals("Samsung SM-A536E", payload.get("deviceName").asText())
    }

    @Test
    fun `parse pair_response success with token`() {
        val json = """{"type":"PAIR_RESPONSE","clientId":"server","timestamp":1727280000,
            "payload":{"success":true,"deviceToken":"tok-1","message":"PAIRED"}}"""
        val parsed = protocol.fromLine(json)
        assertEquals(MessageType.PAIR_RESPONSE, parsed.type)
        val response = parsed.payload as PairResponsePayload
        assertTrue(response.success)
        assertEquals("tok-1", response.deviceToken)
        assertEquals("PAIRED", response.message)
    }

    @Test
    fun `parse pair_response rejection without token`() {
        val json = """{"type":"PAIR_RESPONSE","clientId":"server","timestamp":1,
            "payload":{"success":false,"message":"INVALID_PIN"}}"""
        val response = protocol.fromLine(json).payload as PairResponsePayload
        assertFalse(response.success)
        assertNull(response.deviceToken)
        assertEquals("INVALID_PIN", response.message)
    }

    // ------------------------------------------------------------------
    // COMMAND / CONFIG_SYNC / UNPAIR Mục 3.3/3.4/3.8
    // ------------------------------------------------------------------

    @Test
    fun `parse command with all four actions`() {
        for (action in listOf("PAUSE_NETWORK", "RESUME_NETWORK", "ALERT", "BLOCK_PROCESS")) {
            val json = """{"type":"COMMAND","clientId":"server","timestamp":1,
                "payload":{"commandId":"cmd-1","action":"$action","reason":"ly do"}}"""
            val command = protocol.fromLine(json).payload as CommandPayload
            assertEquals("cmd-1", command.commandId)
            assertEquals(ActionType.valueOf(action), command.action)
            assertEquals("ly do", command.reason)
        }
    }

    @Test
    fun `parse config_sync with blockedDomains and blockedProcesses`() {
        val json = """{"type":"CONFIG_SYNC","clientId":"c","timestamp":1,"payload":{
            "blockedDomains":["tiktok.com","facebook.com"],
            "dailyUsageLimitSeconds":14400,
            "dailyTrafficQuotaBytes":5368709120,
            "allowedStartTime":"06:00","allowedEndTime":"22:00",
            "blockedProcesses":["somegame.exe"]}}"""
        val config = protocol.fromLine(json).payload as ConfigSyncPayload
        assertEquals(listOf("tiktok.com", "facebook.com"), config.blockedDomains)
        assertEquals(14400L, config.dailyUsageLimitSeconds)
        assertEquals(listOf("somegame.exe"), config.blockedProcesses)
        assertEquals("06:00", config.allowedStartTime)
    }

    @Test
    fun `parse unpair with reason`() {
        val json = """{"type":"UNPAIR","clientId":"server","timestamp":1,
            "payload":{"reason":"Thiết bị đã bị xóa khỏi danh sách quản lý"}}"""
        val unpair = protocol.fromLine(json).payload as UnpairPayload
        assertEquals("Thiết bị đã bị xóa khỏi danh sách quản lý", unpair.reason)
    }

    // ------------------------------------------------------------------
    // Lenient + defensive (khớp hành vi parser Java)
    // ------------------------------------------------------------------

    @Test
    fun `unknown fields are ignored like Java FAIL_ON_UNKNOWN_PROPERTIES false`() {
        val json = """{"type":"HEARTBEAT","clientId":"c","timestamp":1,"payload":{},
            "futureField":"x","another":{"nested":true}}"""
        val parsed = protocol.fromLine(json)
        assertEquals(MessageType.HEARTBEAT, parsed.type)
        assertEquals("c", parsed.clientId)
    }

    @Test
    fun `unknown payload fields inside CONFIG_SYNC are ignored`() {
        val json = """{"type":"CONFIG_SYNC","clientId":"c","timestamp":1,"payload":{
            "blockedDomains":["a.com"],"futureConfigField":123}}"""
        val config = protocol.fromLine(json).payload as ConfigSyncPayload
        assertEquals(listOf("a.com"), config.blockedDomains)
        assertEquals(0L, config.dailyUsageLimitSeconds, "field thiếu → default 0 (lenient)")
    }

    @Test(expected = ProtocolParseException::class)
    fun `missing payload field throws like Java requireField`() {
        protocol.fromLine("""{"type":"HEARTBEAT","clientId":"c","timestamp":1}""")
    }

    @Test(expected = ProtocolParseException::class)
    fun `unknown message type throws like Java`() {
        protocol.fromLine("""{"type":"NOT_A_TYPE","clientId":"c","timestamp":1,"payload":{}}""")
    }

    @Test(expected = ProtocolParseException::class)
    fun `malformed json throws`() {
        protocol.fromLine("{khong phai json")
    }

    @Test(expected = ProtocolParseException::class)
    fun `null or blank line throws`() {
        protocol.fromLine(null)
    }

    // ------------------------------------------------------------------
    // Round-trip: Kotlin serialize → Kotlin parse lại (kịch bản ACK loop)
    // ------------------------------------------------------------------

    @Test
    fun `round trip heartbeat through the same parser`() {
        val line = protocol.toLine(MessageType.HEARTBEAT, "round-trip", emptyMap<String, Any>())
        val parsed = protocol.fromLine(line)
        assertEquals(MessageType.HEARTBEAT, parsed.type)
        assertEquals("round-trip", parsed.clientId)
        assertTrue(parsed.payload is Map<*, *>)
    }
}
