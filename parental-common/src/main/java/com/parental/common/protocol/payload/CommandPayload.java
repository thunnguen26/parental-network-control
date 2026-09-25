package com.parental.common.protocol.payload;

import com.parental.common.protocol.ActionType;

/**
 * Payload của gói COMMAND — Server gửi lệnh điều khiển xuống Client
 * (khớp Mục 3.3 của {@code docs/PROTOCOL.md}).
 */
public record CommandPayload(
        String commandId,
        ActionType action,
        String reason) {
}
