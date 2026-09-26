package com.parental.client.network;

import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;

/**
 * Listener mà Client Engine gọi khi nhận gói tin chủ động từ Server
 * (Task 2.2 — Phase này chỉ log và gọi stub listener, KHÔNG can thiệp OS;
 * thực thi PAUSE_NETWORK/RESUME_NETWORK qua netsh thuộc Phase 3).
 */
public interface ServerCommandHandler {

    /** Server gửi lệnh điều khiển ({@code COMMAND}). */
    default void onCommand(CommandPayload command) {
    }

    /** Server đẩy cấu hình chính sách ({@code CONFIG_SYNC}). */
    default void onConfigSync(ConfigSyncPayload config) {
    }
}
