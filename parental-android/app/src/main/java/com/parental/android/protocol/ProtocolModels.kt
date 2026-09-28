package com.parental.android.protocol

/**
 * Các payload của giao thức — MIRROR 1:1 schema Mục 3.1–3.9 của
 * docs/PROTOCOL.md (tên field, kiểu, nullable khớp tuyệt đối records Java
 * trong parental-common). Jackson Kotlin module serialize data class bằng
 * tên property = tên field JSON; property nullable bỏ null (NON_NULL) khớp
 * hành vi @JsonInclude(NON_NULL) của BaseMessage Java.
 */

/** Mục 3.1 — Client gửi khi mới kết nối. */
data class HandshakePayload(
    val deviceName: String,
    val deviceType: String,
    val osVersion: String,
    val ipAddress: String,
    val macAddress: String,
    val deviceToken: String? = null
)

/** Mục 3.2 — Client gửi định kỳ báo cáo lưu lượng & thời gian. */
data class MetricsPayload(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val downloadSpeedBps: Long,
    val uploadSpeedBps: Long,
    val sessionDurationSeconds: Long,
    val networkStatus: String
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PAUSED = "PAUSED"
    }
}

/** Mục 3.3 — Server gửi lệnh điều khiển xuống Client. */
data class CommandPayload(
    val commandId: String,
    val action: ActionType,
    val reason: String
)

/** Mục 3.4 — Server gửi cấu hình chính sách cho Client (Mở rộng 11: + blockedProcesses). */
data class ConfigSyncPayload(
    val blockedDomains: List<String> = emptyList(),
    val dailyUsageLimitSeconds: Long = 0,
    val dailyTrafficQuotaBytes: Long = 0,
    val allowedStartTime: String? = null,
    val allowedEndTime: String? = null,
    val blockedProcesses: List<String> = emptyList()
)

/** Mục 3.5 — ACK: payload rỗng hoặc mang commandId của lệnh đã tiếp nhận. */
data class AckPayload(
    val commandId: String? = null
)

/** Mục 3.6 — Client gửi khi ghép đôi lần đầu (PIN 6 số từ Server GUI). */
data class PairRequestPayload(
    val pin: String,
    val deviceName: String,
    val deviceType: String,
    val osVersion: String,
    val ipAddress: String,
    val macAddress: String
)

/** Mục 3.7 — Server trả lời PAIR_REQUEST. */
data class PairResponsePayload(
    val success: Boolean,
    val deviceToken: String?,
    val message: String
) {
    companion object {
        const val MSG_PAIRED = "PAIRED"
        const val MSG_INVALID_PIN = "INVALID_PIN"
        const val MSG_PIN_EXPIRED = "PIN_EXPIRED"
        const val MSG_PIN_ALREADY_USED = "PIN_ALREADY_USED"
    }
}

/** Mục 3.8 — Server gửi khi phụ huynh XÓA thiết bị khỏi danh sách quản lý. */
data class UnpairPayload(
    val reason: String
) {
    companion object {
        const val DEFAULT_REASON = "Thiết bị đã bị xóa khỏi danh sách quản lý"
    }
}

/**
 * Mục 3.9 — Activity Monitoring. Client ANDROID KHÔNG GỬI gói này
 * (thu thập cửa sổ active là đặc thù desktop); parser vẫn hiểu để
 * tương thích nếu server gửi nhầm — làm tròn payload rỗng.
 */
data class ActivityReportPayload(
    val activeWindowTitle: String = "",
    val activeProcessName: String = "",
    val activeUrlOrDomain: String = "",
    val topProcesses: List<String> = emptyList(),
    val timestamp: Long = 0
)
