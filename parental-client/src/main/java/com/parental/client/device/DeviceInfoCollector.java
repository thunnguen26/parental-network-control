package com.parental.client.device;

import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Interface thu thập thông tin thiết bị để đóng gói {@link HandshakePayload}
 * (Task 2.2). Tách interface để test có thể stub và để Phase 3 (OSHI) có thể
 * thay thế triển khai mà không sửa Engine.
 */
@FunctionalInterface
public interface DeviceInfoCollector {

    /**
     * Thu thập thông tin phần cứng/hệ điều hành của máy.
     *
     * <p>Phải an toàn: không bao giờ quăng exception ra ngoài (SecurityException,
     * UnknownHostException... được xử lý bằng fallback hợp lý).</p>
     *
     * @return payload HANDSHAKE với đủ 5 trường theo Mục 3.1 của PROTOCOL.md
     */
    HandshakePayload collect();
}
