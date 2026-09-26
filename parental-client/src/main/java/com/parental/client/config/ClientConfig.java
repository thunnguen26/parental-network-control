package com.parental.client.config;

import java.util.Objects;

/**
 * Cấu hình kết nối của Client (Task 2.2).
 *
 * <p>Chứa các tham số mạng với giá trị mặc định theo yêu cầu:
 * <ul>
 *   <li>{@code serverHost} = "127.0.0.1"</li>
 *   <li>{@code serverPort} = 8888</li>
 *   <li>{@code clientId} — tự sinh UUID nếu chưa chỉ định</li>
 *   <li>{@code heartbeatIntervalMs} = 5000</li>
 *   <li>{@code initialReconnectDelayMs} = 1000</li>
 *   <li>{@code maxReconnectDelayMs} = 30000</li>
 * </ul>
 *
 * <p>Bất biến (immutable), thread-safe, khởi tạo qua {@link Builder}.</p>
 */
public final class ClientConfig {

    public static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    public static final int DEFAULT_SERVER_PORT = 8888;
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000;
    /** Mặc định 15s = 3 chu kỳ nhịp tim 5s (Task 2.3 — Silent Server Detection). */
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000;
    public static final long DEFAULT_INITIAL_RECONNECT_DELAY_MS = 1_000;
    public static final long DEFAULT_MAX_RECONNECT_DELAY_MS = 30_000;

    private final String serverHost;
    private final int serverPort;
    private final String clientId;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long initialReconnectDelayMs;
    private final long maxReconnectDelayMs;

    private ClientConfig(Builder builder) {
        this.serverHost = builder.serverHost;
        this.serverPort = builder.serverPort;
        this.clientId = builder.clientId;
        this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;
        this.initialReconnectDelayMs = builder.initialReconnectDelayMs;
        this.maxReconnectDelayMs = builder.maxReconnectDelayMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Cấu hình mặc định với clientId tự sinh UUID. */
    public static ClientConfig defaults() {
        return builder().build();
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getClientId() {
        return clientId;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    /**
     * Ngưỡng coi server là "im lặng" (half-open socket / zombie connection):
     * đã gửi HEARTBEAT nhưng không nhận ACK/bản tin nào trong khoảng này
     * (mặc định {@value #DEFAULT_HEARTBEAT_TIMEOUT_MS} ms ≈ 3 nhịp tim).
     */
    public long getHeartbeatTimeoutMs() {
        return heartbeatTimeoutMs;
    }

    public long getInitialReconnectDelayMs() {
        return initialReconnectDelayMs;
    }

    public long getMaxReconnectDelayMs() {
        return maxReconnectDelayMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientConfig other)) {
            return false;
        }
        return serverPort == other.serverPort
                && heartbeatIntervalMs == other.heartbeatIntervalMs
                && heartbeatTimeoutMs == other.heartbeatTimeoutMs
                && initialReconnectDelayMs == other.initialReconnectDelayMs
                && maxReconnectDelayMs == other.maxReconnectDelayMs
                && Objects.equals(serverHost, other.serverHost)
                && Objects.equals(clientId, other.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverHost, serverPort, clientId,
                heartbeatIntervalMs, heartbeatTimeoutMs, initialReconnectDelayMs, maxReconnectDelayMs);
    }

    @Override
    public String toString() {
        return "ClientConfig{serverHost='" + serverHost + '\'' + ", serverPort=" + serverPort
                + ", clientId='" + clientId + '\'' + ", heartbeatIntervalMs=" + heartbeatIntervalMs
                + ", heartbeatTimeoutMs=" + heartbeatTimeoutMs
                + ", initialReconnectDelayMs=" + initialReconnectDelayMs
                + ", maxReconnectDelayMs=" + maxReconnectDelayMs + '}';
    }

    /** Builder pattern: chỉ validate phần cứng (host/port), giá trị thời gian đã chặn <= 0. */
    public static final class Builder {

        private String serverHost = DEFAULT_SERVER_HOST;
        private int serverPort = DEFAULT_SERVER_PORT;
        private String clientId = java.util.UUID.randomUUID().toString(); // tự sinh UUID nếu chưa có
        private long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
        private long heartbeatTimeoutMs = DEFAULT_HEARTBEAT_TIMEOUT_MS;
        private long initialReconnectDelayMs = DEFAULT_INITIAL_RECONNECT_DELAY_MS;
        private long maxReconnectDelayMs = DEFAULT_MAX_RECONNECT_DELAY_MS;

        private Builder() {
        }

        public Builder serverHost(String serverHost) {
            this.serverHost = requireNonBlank(serverHost, "serverHost");
            return this;
        }

        public Builder serverPort(int serverPort) {
            if (serverPort < 1 || serverPort > 65535) {
                throw new IllegalArgumentException("serverPort must be in range 1..65535, got: " + serverPort);
            }
            this.serverPort = serverPort;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = requireNonBlank(clientId, "clientId");
            return this;
        }

        public Builder heartbeatIntervalMs(long heartbeatIntervalMs) {
            if (heartbeatIntervalMs <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalMs must be positive, got: " + heartbeatIntervalMs);
            }
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            return this;
        }

        public Builder heartbeatTimeoutMs(long heartbeatTimeoutMs) {
            if (heartbeatTimeoutMs <= 0) {
                throw new IllegalArgumentException("heartbeatTimeoutMs must be positive, got: " + heartbeatTimeoutMs);
            }
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            return this;
        }

        public Builder initialReconnectDelayMs(long initialReconnectDelayMs) {
            if (initialReconnectDelayMs <= 0) {
                throw new IllegalArgumentException("initialReconnectDelayMs must be positive, got: "
                        + initialReconnectDelayMs);
            }
            this.initialReconnectDelayMs = initialReconnectDelayMs;
            return this;
        }

        public Builder maxReconnectDelayMs(long maxReconnectDelayMs) {
            if (maxReconnectDelayMs <= 0) {
                throw new IllegalArgumentException("maxReconnectDelayMs must be positive, got: "
                        + maxReconnectDelayMs);
            }
            this.maxReconnectDelayMs = maxReconnectDelayMs;
            return this;
        }

        /** {@code maxReconnectDelayMs} không được nhỏ hơn {@code initialReconnectDelayMs}. */
        public ClientConfig build() {
            if (maxReconnectDelayMs < initialReconnectDelayMs) {
                throw new IllegalStateException("maxReconnectDelayMs (" + maxReconnectDelayMs
                        + ") must be >= initialReconnectDelayMs (" + initialReconnectDelayMs + ")");
            }
            if (heartbeatTimeoutMs < heartbeatIntervalMs) {
                throw new IllegalStateException("heartbeatTimeoutMs (" + heartbeatTimeoutMs
                        + ") must be >= heartbeatIntervalMs (" + heartbeatIntervalMs + ")");
            }
            return new ClientConfig(this);
        }

        private static String requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be null or blank");
            }
            return value;
        }
    }
}
