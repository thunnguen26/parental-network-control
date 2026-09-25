package com.parental.common.protocol.payload;

/**
 * Payload của gói HANDSHAKE — Client gửi khi mới kết nối
 * (khớp Mục 3.1 của {@code docs/PROTOCOL.md}).
 */
public record HandshakePayload(
        String deviceName,
        String deviceType,
        String osVersion,
        String ipAddress,
        String macAddress) {
}
