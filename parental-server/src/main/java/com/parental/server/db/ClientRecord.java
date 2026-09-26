package com.parental.server.db;

/**
 * Bản ghi bảng {@code clients} — DTO bất biến (Task 4.1).
 *
 * @param clientId  định danh client (UUID/MAC)
 * @param deviceName tên thiết bị (từ HANDSHAKE)
 * @param deviceType "DESKTOP"/"MOBILE"
 * @param osVersion  hệ điều hành
 * @param ipAddress  IP nội bộ
 * @param macAddress địa chỉ MAC
 * @param status     "ONLINE"/"OFFLINE"
 */
public record ClientRecord(
        String clientId,
        String deviceName,
        String deviceType,
        String osVersion,
        String ipAddress,
        String macAddress,
        String status) {
}
