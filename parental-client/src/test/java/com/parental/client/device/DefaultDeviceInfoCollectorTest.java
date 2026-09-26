package com.parental.client.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Unit test cho {@link DefaultDeviceInfoCollector}: payload hợp lệ theo schema
 * Mục 3.1, deviceType = DESKTOP, các phương thức fallback hoạt động an toàn.
 */
class DefaultDeviceInfoCollectorTest {

    private final DefaultDeviceInfoCollector collector = new DefaultDeviceInfoCollector();

    @Test
    @DisplayName("collect() trả payload đủ 5 trường, không null, deviceType = DESKTOP")
    void collectReturnsCompletePayload() {
        HandshakePayload payload = collector.collect();

        assertNotNull(payload);
        assertNotNull(payload.deviceName());
        assertFalse(payload.deviceName().isBlank(), "deviceName không được rỗng");
        assertEquals("DESKTOP", payload.deviceType(), "deviceType phải là DESKTOP theo Task 2.2");
        assertNotNull(payload.osVersion());
        assertFalse(payload.osVersion().isBlank());
        assertNotNull(payload.ipAddress());
        assertFalse(payload.ipAddress().isBlank());
        assertNotNull(payload.macAddress());
        assertFalse(payload.macAddress().isBlank());
    }

    @Test
    @DisplayName("osVersion chứa tên OS thực tế của máy chạy test")
    void osVersionReflectsRealOs() {
        String os = collector.resolveOsVersion();
        String expectedName = System.getProperty("os.name");
        assertTrue(os.contains(expectedName), "osVersion phải chứa os.name thực tế: " + os);
    }

    @Test
    @DisplayName("deviceName khớp hostname thật hoặc fallback chứa '-host' khi không resolve được")
    void deviceNameMatchesHostnameOrFallback() {
        String name = collector.resolveDeviceName();
        assertFalse(name.isBlank());
        // Trên máy CI/local bình thường hostname phải resolve được
        String hostName;
        try {
            hostName = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException | SecurityException e) {
            hostName = null;
        }
        if (hostName != null && !hostName.isBlank()) {
            assertEquals(hostName.trim(), name);
        } else {
            assertTrue(name.endsWith("-host"), "fallback phải kết thúc '-host'");
        }
    }

    @Test
    @DisplayName("IP nội bộ hợp lệ (IPv4/IPv6) hoặc fallback 127.0.0.1")
    void localIpIsValidOrFallback() {
        String ip = collector.resolveLocalIp();
        assertFalse(ip.isBlank());
        boolean looksLikeIp = ip.contains(".") || ip.contains(":");
        boolean isFallback = "127.0.0.1".equals(ip);
        assertTrue(looksLikeIp || isFallback, "IP phải có dạng địa chỉ: " + ip);
    }

    @Test
    @DisplayName("MAC định dạng hex phân tách '-' hoặc fallback toàn 0")
    void macIsFormattedOrFallback() {
        String mac = collector.resolveMacAddress();
        assertFalse(mac.isBlank());
        if ("00-00-00-00-00-00".equals(mac)) {
            return; // fallback hợp lệ trên môi trường không có NIC thật
        }
        assertTrue(mac.matches("([0-9A-F]{2}-){5}[0-9A-F]{2}"),
                "MAC phải theo mẫu XX-XX-XX-XX-XX-XX: " + mac);
        assertEquals(17, mac.length());
    }

    @Test
    @DisplayName("collect() idempotent: gọi nhiều lần cho kết quả nhất quán")
    void collectIsStableAcrossCalls() {
        HandshakePayload first = collector.collect();
        HandshakePayload second = collector.collect();
        assertEquals(first.deviceName(), second.deviceName());
        assertEquals(first.osVersion(), second.osVersion());
        assertEquals(first.deviceType(), second.deviceType());
    }
}
