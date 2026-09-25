package com.parental.common.protocol;

/**
 * Loại gói tin giao thức, khớp Mục 2 của {@code docs/PROTOCOL.md}.
 */
public enum MessageType {
    HANDSHAKE,
    HEARTBEAT,
    METRICS_REPORT,
    COMMAND,
    CONFIG_SYNC,
    ACK
}
