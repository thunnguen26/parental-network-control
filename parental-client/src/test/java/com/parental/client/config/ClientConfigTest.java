package com.parental.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit test cho {@link ClientConfig}: giá trị mặc định, builder, validation. */
class ClientConfigTest {

    @Test
    @DisplayName("Builder không tham số: dùng đúng giá trị mặc định và clientId tự sinh UUID")
    void defaultsAreCorrect() {
        ClientConfig config = ClientConfig.builder().build();

        assertEquals("127.0.0.1", config.getServerHost());
        assertEquals(8888, config.getServerPort());
        assertEquals(5000, config.getHeartbeatIntervalMs());
        assertEquals(1000, config.getInitialReconnectDelayMs());
        assertEquals(30000, config.getMaxReconnectDelayMs());
        assertNotNull(config.getClientId());
        assertDoesNotThrowParseUuid(config.getClientId());
    }

    private static void assertDoesNotThrowParseUuid(String clientId) {
        // clientId phải là UUID hợp lệ
        UUID.fromString(clientId); // ném IllegalArgumentException nếu sai
    }

    @Test
    @DisplayName("Hai config mặc định khác nhau phải có clientId khác nhau (UUID riêng)")
    void eachDefaultConfigGetsUniqueClientId() {
        ClientConfig c1 = ClientConfig.defaults();
        ClientConfig c2 = ClientConfig.defaults();
        assertNotEquals(c1.getClientId(), c2.getClientId());
    }

    @Test
    @DisplayName("Builder cho phép ghi đè toàn bộ tham số")
    void builderOverridesAllFields() {
        ClientConfig config = ClientConfig.builder()
                .serverHost("192.168.1.10")
                .serverPort(9999)
                .clientId("fixed-client-id")
                .heartbeatIntervalMs(3000)
                .initialReconnectDelayMs(500)
                .maxReconnectDelayMs(8000)
                .build();

        assertEquals("192.168.1.10", config.getServerHost());
        assertEquals(9999, config.getServerPort());
        assertEquals("fixed-client-id", config.getClientId());
        assertEquals(3000, config.getHeartbeatIntervalMs());
        assertEquals(500, config.getInitialReconnectDelayMs());
        assertEquals(8000, config.getMaxReconnectDelayMs());
    }

    @Test
    @DisplayName("serverPort ngoài 1..65535 bị từ chối")
    void invalidPortRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().serverPort(0).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().serverPort(65536).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().serverPort(-1).build());
    }

    @Test
    @DisplayName("serverHost rỗng/null bị từ chối")
    void blankHostRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().serverHost(null).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().serverHost("  ").build());
    }

    @Test
    @DisplayName("clientId rỗng/null bị từ chối")
    void blankClientIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().clientId(null).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().clientId("").build());
    }

    @Test
    @DisplayName("Các tham số thời gian <= 0 bị từ chối")
    void nonPositiveTimingsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().heartbeatIntervalMs(0).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().heartbeatIntervalMs(-5).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().initialReconnectDelayMs(0).build());
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.builder().maxReconnectDelayMs(0).build());
    }

    @Test
    @DisplayName("maxReconnectDelayMs < initialReconnectDelayMs bị từ chối khi build")
    void maxDelaySmallerThanInitialRejected() {
        assertThrows(IllegalStateException.class, () -> ClientConfig.builder()
                .initialReconnectDelayMs(4000)
                .maxReconnectDelayMs(2000)
                .build());
    }

    @Test
    @DisplayName("Config bất biến: equals/hashCode/toString hoạt động đúng")
    void equalityAndHashCode() {
        ClientConfig a = ClientConfig.builder().clientId("same").build();
        ClientConfig b = ClientConfig.builder().clientId("same").build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("serverPort=8888"));
        assertTrue(a.toString().contains("clientId='same'"));
    }

    @Test
    @DisplayName("ClientConfig dễ dùng trong vòng lặp (mẫu dùng chung)")
    void reusedAcrossLoopSimulation() {
        AtomicInteger seen = new AtomicInteger();
        ClientConfig config = ClientConfig.defaults();
        for (int i = 0; i < 3; i++) {
            seen.set(config.getServerPort());
        }
        assertEquals(8888, seen.get());
    }
}
