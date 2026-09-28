package com.parental.server.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;

/**
 * Đại diện cho một phiên kết nối Client đang hoạt động.
 *
 * <p>Thread-safety: {@code send()} được synchronize trên lock nội bộ để
 * nhiều thread (ClientHandler đọc, Engine/UI đẩy lệnh) có thể ghi xuống
 * cùng một socket mà không làm hỏng khung LDJSON.</p>
 */
public class ClientSession {

    private static final Logger log = LoggerFactory.getLogger(ClientSession.class);

    private final String clientId;
    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final JsonProtocolParser parser;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Thông tin thiết bị lấy từ gói HANDSHAKE đầu tiên. */
    private volatile HandshakePayload deviceInfo;

    /** Epoch seconds của HEARTBEAT gần nhất — dùng cho dead-session detection. */
    private volatile long lastHeartbeatTimestamp;

    /** Metrics traffic gần nhất nhận từ client (METRICS_REPORT — Task 3.1). */
    private volatile MetricsPayload lastMetrics;

    /** Hoạt động gần nhất (ACTIVITY_REPORT — Mục 3.9): "đang mở gì" cho Dashboard. */
    private volatile com.parental.common.protocol.payload.ActivityReportPayload lastActivity;

    /**
     * Chữ ký dedup nhật ký: {@code process + '\u0000' + title} của bản ghi
     * HOẠT ĐỘNG gần nhất ĐÃ ghi DB (khác {@code lastActivity} — thể hiện
     * hoạt động MỚI NHẬN kể cả chưa ghi). So khớp trước khi insert để người
     * dùng ở yên 1 video không sinh hàng chục dòng log giống nhau mỗi 10s.
     * Cố tình dùng String (immutable) thay record để an toàn volatile publish.
     */
    private volatile String activitySignature;

    public ClientSession(String clientId, Socket socket,
                         BufferedReader reader, PrintWriter writer, JsonProtocolParser parser) {
        this.clientId = clientId;
        this.socket = socket;
        this.reader = reader;
        this.writer = writer;
        this.parser = parser;
    }

    /**
     * Gửi một gói tin xuống Client một cách an toàn đa luồng (synchronized
     * write). Trả về {@code false} nếu session đã đóng hoặc ghi lỗi —
     * không quăng {@code IOException} ra ngoài.
     */
    public boolean send(BaseMessage<?> message) {
        if (closed.get()) {
            log.warn("[{}] Send skipped: session is closed", clientId);
            return false;
        }
        try {
            String line = parser.toLine(message);
            synchronized (writeLock) {
                if (closed.get() || socket.isClosed() || !socket.isConnected()) {
                    return false;
                }
                writer.write(line);
                writer.flush();
                if (writer.checkError()) {
                    // PrintWriter nuốt IOException nội bộ — phải hỏi checkError()
                    log.error("[{}] Writer error after flush (connection broken)", clientId);
                    return false;
                }
            }
            return true;
        } catch (ProtocolParseException e) {
            log.error("[{}] Serialize failed: {}", clientId, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.error("[{}] Send failed: {}", clientId, e.getMessage());
            return false;
        }
    }

    /**
     * Đóng phiên: ĐÓNG SOCKET TRƯỚC để đánh thức thread đang block trong
     * {@code readLine()} (socket read không phản ứng với interrupt), sau đó
     * mới đóng stream — tránh deadlock monitor khi gọi từ thread khác.
     * Không quăng lỗi unhandled. Chống NPE khi socket/stream null (session
     * giả lập trong unit test).
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // đã đóng rồi
        }
        if (socket == null) {
            return; // session không gắn socket thật (unit test) — không cần dọn
        }
        try {
            socket.close(); // đánh thức readLine() đang block + giải phóng socket
        } catch (IOException | RuntimeException e) {
            log.debug("[{}] Socket close ignored: {}", clientId, e.getMessage());
        }
        // Chờ ngắn cho handler thread thoát khỏi readLine() rồi mới đóng stream,
        // tránh chờ monitor InputStreamReader vô thời hạn.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            reader.close();
        } catch (IOException | RuntimeException e) {
            log.debug("[{}] Reader close ignored: {}", clientId, e.getMessage());
        }
        try {
            writer.close();
        } catch (RuntimeException e) {
            log.debug("[{}] Writer close ignored: {}", clientId, e.getMessage());
        }
        log.info("[{}] Session closed", clientId);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public String getClientId() {
        return clientId;
    }

    public HandshakePayload getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(HandshakePayload deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    /** Metrics traffic gần nhất (null nếu client chưa ever gửi METRICS_REPORT). */
    public MetricsPayload getLastMetrics() {
        return lastMetrics;
    }

    public void setLastMetrics(MetricsPayload lastMetrics) {
        this.lastMetrics = lastMetrics;
    }

    /** Hoạt động gần nhất (null nếu client chưa ever gửi ACTIVITY_REPORT). */
    public com.parental.common.protocol.payload.ActivityReportPayload getLastActivity() {
        return lastActivity;
    }

    public void setLastActivity(
            com.parental.common.protocol.payload.ActivityReportPayload lastActivity) {
        this.lastActivity = lastActivity;
    }

    /**
     * Chữ ký hoạt động gần nhất ĐÃ ghi DB ({@code process + '\u0000' + title};
     * {@code null} = chưa ghi gì). Dùng bởi {@code ClientHandler} cho bộ
     * chống spam nhật ký trùng lặp (dedup) — người dùng ở yên 1 app/video
     * thì KHÔNG insert thêm dòng, chỉ Dashboard "Đang mở" vẫn cập nhật realtime.
     */
    public String getActivitySignature() {
        return activitySignature;
    }

    /** Lưu chữ ký hoạt động vừa ghi DB (gọi từ ClientHandler sau khi insert). */
    public void setActivitySignature(String activitySignature) {
        this.activitySignature = activitySignature;
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    public void setLastHeartbeatTimestamp(long lastHeartbeatTimestamp) {
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
    }

    public Socket getSocket() {
        return socket;
    }

    /** Địa chỉ IP remote (dùng cho log/GUI). */
    public String getRemoteAddress() {
        if (socket.getRemoteSocketAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return String.valueOf(socket.getRemoteSocketAddress());
    }
}
