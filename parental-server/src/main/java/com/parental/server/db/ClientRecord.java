package com.parental.server.db;

/**
 * Bản ghi bảng {@code clients} — DTO bất biến (Task 4.1 + Alias).
 *
 * @param clientId  định danh client (UUID/MAC)
 * @param deviceName tên thiết bị (từ HANDSHAKE)
 * @param deviceType "DESKTOP"/"MOBILE"
 * @param osVersion  hệ điều hành
 * @param ipAddress  IP nội bộ
 * @param macAddress địa chỉ MAC
 * @param status     "ONLINE"/"OFFLINE"
 * @param alias      biệt danh do phụ huynh đặt (null = chưa đặt, hiển thị tên gốc)
 */
public record ClientRecord(
        String clientId,
        String deviceName,
        String deviceType,
        String osVersion,
        String ipAddress,
        String macAddress,
        String status,
        String alias) {

    /** Constructor tương thích ngược (test cũ 7 tham số — không có alias). */
    public ClientRecord(String clientId, String deviceName, String deviceType,
                        String osVersion, String ipAddress, String macAddress, String status) {
        this(clientId, deviceName, deviceType, osVersion, ipAddress, macAddress, status, null);
    }
}
