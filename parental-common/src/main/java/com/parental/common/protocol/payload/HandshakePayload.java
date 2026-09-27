package com.parental.common.protocol.payload;

/**
 * Payload của gói HANDSHAKE — Client gửi khi mới kết nối
 * (khớp Mục 3.1 của {@code docs/PROTOCOL.md}).
 *
 * @param deviceToken token cấp quyền kết nối (Mục 3.7); {@code null} khi
 *                    thiết bị CHƯA ghép đôi — server sẽ từ chối phiên
 *                    với mã {@code UNAUTHORIZED_DEVICE}
 */
public record HandshakePayload(
        String deviceName,
        String deviceType,
        String osVersion,
        String ipAddress,
        String macAddress,
        String deviceToken) {

    /** Constructor tiện lợi cho test/legacy: handshake không đính token. */
    public HandshakePayload(String deviceName, String deviceType,
                            String osVersion, String ipAddress, String macAddress) {
        this(deviceName, deviceType, osVersion, ipAddress, macAddress, null);
    }
}
