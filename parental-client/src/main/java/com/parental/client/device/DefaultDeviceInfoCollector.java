package com.parental.client.device;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.HandshakePayload;

/**
 * Triển khai {@link DeviceInfoCollector} bằng Java Core (không bắt buộc OSHI —
 * Task 3.1 sẽ thay bằng OSHI khi cần metrics chi tiết).
 *
 * <p>Chiến lược phòng thủ (Defensive OS Interfacing theo AGENTS.md):
 * <ul>
 *   <li>Hostname: {@code InetAddress.getLocalHost().getHostName()}, fallback
 *       {@code os.name} → "unknown-host" khi gặp UnknownHostException/SecurityException.</li>
 *   <li>OS: {@code System.getProperty("os.name")} + {@code os.version}, fallback "Unknown OS".</li>
 *   <li>IP nội bộ + MAC: duyệt {@link NetworkInterface}, ưu tiên NIC có địa chỉ site-local
 *       (LAN), bỏ loopback/virtual/down; fallback "127.0.0.1" / "00-00-00-00-00-00".</li>
 *   <li>Không bao giờ quăng exception — luôn trả về payload hợp lệ để vòng đời
 *       HANDSHAKE của Engine không bao giờ bị gián đoạn.</li>
 * </ul></p>
 */
public class DefaultDeviceInfoCollector implements DeviceInfoCollector {

    public static final String DEVICE_TYPE_DESKTOP = "DESKTOP";
    static final String FALLBACK_HOSTNAME = "unknown-host";
    static final String FALLBACK_OS = "Unknown OS";
    static final String FALLBACK_IP = "127.0.0.1";
    static final String FALLBACK_MAC = "00-00-00-00-00-00";

    private static final Logger log = LoggerFactory.getLogger(DefaultDeviceInfoCollector.class);

    @Override
    public HandshakePayload collect() {
        return new HandshakePayload(resolveDeviceName(), DEVICE_TYPE_DESKTOP,
                resolveOsVersion(), resolveLocalIp(), resolveMacAddress());
    }

    // ------------------------------------------------------------------
    // Từng trường thông tin — mỗi trường có fallback riêng
    // ------------------------------------------------------------------

    /**
     * Tên máy: {@code InetAddress.getLocalHost().getHostName()}, fallback
     * {@code os.name} rồi "unknown-host" khi UnknownHostException/SecurityException.
     */
    String resolveDeviceName() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (hostName != null && !hostName.isBlank()) {
                return hostName.trim();
            }
        } catch (UnknownHostException | SecurityException e) {
            log.debug("Cannot resolve hostname ({}), falling back to os.name", e.getClass().getSimpleName());
        }
        String osName = safeGetProperty("os.name");
        return (osName == null || osName.isBlank()) ? FALLBACK_HOSTNAME : osName + "-host";
    }

    /** OS Name/Version từ System.getProperty, ví dụ "Windows 11 10.0". */
    String resolveOsVersion() {
        String name = safeGetProperty("os.name");
        String version = safeGetProperty("os.version");
        if (name == null || name.isBlank()) {
            return FALLBACK_OS;
        }
        return (version == null || version.isBlank()) ? name : name + " " + version;
    }

    /**
     * IP nội bộ: duyệt NIC, ưu tiên địa chỉ site-local (LAN, vd 192.168.x.x);
     * bỏ loopback, NIC down/virtual. Fallback "127.0.0.1".
     */
    String resolveLocalIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr.isSiteLocalAddress() && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException | SecurityException e) {
            log.debug("Cannot enumerate network interfaces ({}), using fallback IP",
                    e.getClass().getSimpleName());
        }
        return FALLBACK_IP;
    }

    /**
     * MAC address của NIC hợp lệ đầu tiên (ưu tiên NIC có IP site-local),
     * định dạng "AA-BB-CC-DD-EE-FF" khớp ví dụ Mục 3.1. Fallback
     * "00-00-00-00-00-00" khi không đọc được.
     */
    String resolveMacAddress() {
        try {
            String bestEffort = null;
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                byte[] mac = nif.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }
                boolean hasSiteLocal = false;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    hasSiteLocal |= addr.isSiteLocalAddress();
                }
                String formatted = formatMac(mac);
                if (hasSiteLocal) {
                    return formatted; // NIC LAN thật — ưu tiên cao nhất
                }
                if (bestEffort == null) {
                    bestEffort = formatted;
                }
            }
            if (bestEffort != null) {
                return bestEffort;
            }
        } catch (SocketException | SecurityException e) {
            log.debug("Cannot read MAC address ({}), using fallback MAC", e.getClass().getSimpleName());
        }
        return FALLBACK_MAC;
    }

    private static String formatMac(byte[] mac) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }

    private static String safeGetProperty(String key) {
        try {
            return System.getProperty(key);
        } catch (SecurityException e) {
            return null;
        }
    }
}
