package com.parental.common.protocol;

/**
 * Hành động điều khiển mà Server gửi xuống Client qua gói COMMAND
 * (khớp Mục 3.3 của {@code docs/PROTOCOL.md}).
 */
public enum ActionType {
    PAUSE_NETWORK,
    RESUME_NETWORK,
    ALERT
}
