package com.parental.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {

    @Test
    @DisplayName("Reconnect delay doubles once per failed attempt and caps at configured maximum")
    void delayDoublesAndCaps() {
        long delay = 1_000;
        delay = TcpClientEngine.nextReconnectDelay(delay, 30_000);
        assertEquals(2_000, delay);
        delay = TcpClientEngine.nextReconnectDelay(delay, 30_000);
        assertEquals(4_000, delay);
        delay = TcpClientEngine.nextReconnectDelay(delay, 30_000);
        assertEquals(8_000, delay);
        delay = TcpClientEngine.nextReconnectDelay(16_000, 30_000);
        assertEquals(30_000, delay);
    }

    @Test
    @DisplayName("Invalid reconnect delays are rejected")
    void rejectsNonPositiveDelays() {
        assertThrows(IllegalArgumentException.class,
                () -> TcpClientEngine.nextReconnectDelay(0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> TcpClientEngine.nextReconnectDelay(10, 0));
    }
}
