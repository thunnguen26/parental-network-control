package com.parental.client.config;

import java.util.Objects;

/**
 * Cấu hình kết nối của Client (Task 2.2).
 *
 * <p>Chứa các tham số mạng với giá trị mặc định theo yêu cầu:
 * <ul>
 *   <li>{@code serverHost} = "127.0.0.1"</li>
 *   <li>{@code serverPort} = 8888</li>
 *   <li>{@code clientId} — ĐỊNH DANH BỀN VỮNG, không tự sinh ở đây nữa: app
 *       đọc từ {@code client_identity.json} (ClientIdentityManager) và set
 *       qua builder; nếu chưa set vẫn tự sinh UUID (fallback legacy/test)</li>
 *   <li>{@code deviceToken} — token ghép đôi (null khi thiết bị chưa ghép đôi)</li>
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
    /** Chu kỳ gửi METRICS_REPORT mặc định (Task 3.1). */
    public static final long DEFAULT_METRICS_INTERVAL_MS = 5_000;
    /** Chu kỳ gửi ACTIVITY_REPORT mặc định (Activity Monitoring — PROTOCOL.md Mục 3.9). */
    public static final long DEFAULT_ACTIVITY_INTERVAL_MS = 10_000;
    public static final long DEFAULT_INITIAL_RECONNECT_DELAY_MS = 1_000;
    public static final long DEFAULT_MAX_RECONNECT_DELAY_MS = 30_000;

    private final String serverHost;
    private final int serverPort;
    private final String clientId;
    /** Token ghép đôi đính kèm HANDSHAKE — null khi thiết bị chưa ghép đôi. */
    private final String deviceToken;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;
    private final long metricsIntervalMs;
    /** Chu kỳ gửi ACTIVITY_REPORT (0 = tắt giám sát hoạt động). */
    private final long activityIntervalMs;
    private final long initialReconnectDelayMs;
    private final long maxReconnectDelayMs;

    private ClientConfig(Builder builder) {
        this.serverHost = builder.serverHost;
        this.serverPort = builder.serverPort;
        this.clientId = builder.clientId;
        this.deviceToken = builder.deviceToken;
        this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;
        this.metricsIntervalMs = builder.metricsIntervalMs;
        this.activityIntervalMs = builder.activityIntervalMs;
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

    /** Token ghép đôi (null = thiết bị chưa ghép đôi — server sẽ từ chối). */
    public String getDeviceToken() {
        return deviceToken;
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

    /** Chu kỳ gửi gói METRICS_REPORT lên Server (mặc định {@value #DEFAULT_METRICS_INTERVAL_MS} ms). */
    public long getMetricsIntervalMs() {
        return metricsIntervalMs;
    }

    /**
     * Chu kỳ gửi gói ACTIVITY_REPORT (mặc định
     * {@value #DEFAULT_ACTIVITY_INTERVAL_MS} ms; 0 = tắt giám sát hoạt động).
     */
    public long getActivityIntervalMs() {
        return activityIntervalMs;
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
                && metricsIntervalMs == other.metricsIntervalMs
                && activityIntervalMs == other.activityIntervalMs
                && initialReconnectDelayMs == other.initialReconnectDelayMs
                && maxReconnectDelayMs == other.maxReconnectDelayMs
                && Objects.equals(serverHost, other.serverHost)
                && Objects.equals(clientId, other.clientId)
                && Objects.equals(deviceToken, other.deviceToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverHost, serverPort, clientId, deviceToken,
                heartbeatIntervalMs, heartbeatTimeoutMs, metricsIntervalMs, activityIntervalMs,
                initialReconnectDelayMs, maxReconnectDelayMs);
    }

    @Override
    public String toString() {
        return "ClientConfig{serverHost='" + serverHost + '\'' + ", serverPort=" + serverPort
                + ", clientId='" + clientId + '\'' + ", deviceToken="
                + (deviceToken == null ? "null" : "***") + ", heartbeatIntervalMs=" + heartbeatIntervalMs
                + ", heartbeatTimeoutMs=" + heartbeatTimeoutMs
                + ", metricsIntervalMs=" + metricsIntervalMs
                + ", activityIntervalMs=" + activityIntervalMs
                + ", initialReconnectDelayMs=" + initialReconnectDelayMs
                + ", maxReconnectDelayMs=" + maxReconnectDelayMs + '}';
    }

    /** Builder pattern: chỉ validate phần cứng (host/port), giá trị thời gian đã chặn <= 0. */
    public static final class Builder {

        private String serverHost = DEFAULT_SERVER_HOST;
        private int serverPort = DEFAULT_SERVER_PORT;
        private String clientId = java.util.UUID.randomUUID().toString(); // fallback legacy/test
        private String deviceToken; // null = chưa ghép đôi
        private long heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
        private long heartbeatTimeoutMs = DEFAULT_HEARTBEAT_TIMEOUT_MS;
        private long metricsIntervalMs = DEFAULT_METRICS_INTERVAL_MS;
        private long activityIntervalMs = DEFAULT_ACTIVITY_INTERVAL_MS;
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

        /**
         * Token ghép đôi lấy từ {@code client_identity.json}. {@code null}/
         * blank → không có token (thiết bị chưa ghép đôi).
         */
        public Builder deviceToken(String deviceToken) {
            this.deviceToken = (deviceToken == null || deviceToken.isBlank()) ? null : deviceToken.trim();
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

        public Builder metricsIntervalMs(long metricsIntervalMs) {
            if (metricsIntervalMs <= 0) {
                throw new IllegalArgumentException("metricsIntervalMs must be positive, got: " + metricsIntervalMs);
            }
            this.metricsIntervalMs = metricsIntervalMs;
            return this;
        }

        /**
         * Chu kỳ gửi ACTIVITY_REPORT (ms). 0 = TẮT giám sát hoạt động (test
         * headless dùng để không spawn PowerShell); giá trị dương phải > 0.
         */
        public Builder activityIntervalMs(long activityIntervalMs) {
            if (activityIntervalMs < 0) {
                throw new IllegalArgumentException("activityIntervalMs must be >= 0 (0 = disabled), got: "
                        + activityIntervalMs);
            }
            this.activityIntervalMs = activityIntervalMs;
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
