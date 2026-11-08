package com.parental.server.network;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.ActivityReportPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.common.protocol.payload.PairRequestPayload;
import com.parental.common.protocol.payload.PairResponsePayload;
import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;
import com.parental.server.db.ActivityDao;
import com.parental.server.db.ClientDao;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.CommandLogDao;
import com.parental.server.db.MetricsDao;
import com.parental.server.db.PairingDao;
import com.parental.server.db.PairingDao.PinState;

/**
 * Xử lý một kết nối Client trên thread riêng (chạy trong ExecutorService của
 * {@link TcpServerEngine}).
 *
 * <p>Nghi thức bắt buộc: gói tin đầu tiên phải là {@code HANDSHAKE} hoặc
 * {@code PAIR_REQUEST} để xác định clientId — nếu không, kết nối bị từ chối.
 * Sau handshake, vòng lặp chính đọc LDJSON qua {@code BufferedReader.readLine()},
 * giải mã bằng {@link JsonProtocolParser}: HEARTBEAT cập nhật timestamp sống
 * và phản hồi ACK; các gói khác chỉ ghi log.</p>
 *
 * <p><b>Device Pairing (Client Identity Persistence)</b>:</p>
 * <ul>
 *   <li>{@code PAIR_REQUEST} (gói đầu): kiểm tra PIN qua {@link PairingDao} —
 *       đúng/còn hạn/chưa dùng → sinh UUID {@code deviceToken}, lưu bảng
 *       {@code clients} ({@code is_paired = 1}) và trả {@code PAIR_RESPONSE}
 *       success=true kèm token; sai/hết hạn/đã dùng → success=false kèm lý do.
 *       Sau khi trả lời, kết nối ghép đôi được đóng — client sẽ handshake lại
 *       bằng token vừa nhận.</li>
 *   <li>{@code HANDSHAKE} (gói đầu): so khớp {@code deviceToken} với DB —
 *       thiết bị chưa ghép đôi hoặc sai token bị ngắt kết nối với mã lỗi
 *       {@code UNAUTHORIZED_DEVICE} (chỉ khi chạy CÓ database; chế độ không
 *       DB giữ hành vi cũ cho test/legacy).</li>
 * </ul>
 */
public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    /** Định danh của Server trong trường clientId khi gửi gói xuống Client. */
    public static final String SERVER_ID = "server";

    /** Mã lỗi ngắt kết nối khi thiết bị chưa ghép đôi hoặc sai deviceToken. */
    public static final String ERROR_UNAUTHORIZED_DEVICE = "UNAUTHORIZED_DEVICE";

    /** Thời gian tối đa chờ gói HANDSHAKE đầu tiên (chống treo thread). */
    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;

    /**
     * Giới hạn phòng thủ cho STREAM_FRAME: client thật gửi tối đa 1600px cạnh
     * dài, JPEG ~ 0.7 nên vài trăm KB. Các ngưỡng dưới đây chặn payload độc hại
     * (Base64 khổng lồ) trước khi giữ vào session/giải mã — tránh OOM/treo handler.
     */
    static final int MAX_FRAME_DIMENSION = 4096;
    static final long MAX_FRAME_PIXELS = 8_000_000L;
    static final int MAX_FRAME_BASE64_CHARS = 4 * 1024 * 1024;

    private final Socket socket;
    private final ClientSessionManager sessionManager;
    private final JsonProtocolParser parser;
    /** DB layer (Task 4.1) — nullable: null = chạy không database. */
    private final ClientDao clientDao;
    private final MetricsDao metricsDao;
    private final CommandLogDao commandLogDao;
    /** Ghép đôi (Identity Persistence) — nullable: null = chạy không database. */
    private final PairingDao pairingDao;
    /** Nhật ký hoạt động (Activity Monitoring) — nullable: null = chạy không DB. */
    private final ActivityDao activityDao;

    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser) {
        this(socket, sessionManager, parser, null, null, null, null, null);
    }

    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser,
                  ClientDao clientDao, MetricsDao metricsDao, CommandLogDao commandLogDao) {
        this(socket, sessionManager, parser, clientDao, metricsDao, commandLogDao, null, null);
    }

    /** Constructor đầy đủ (Device Pairing): kèm {@link PairingDao} cho PAIR_REQUEST. */
    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser,
                  ClientDao clientDao, MetricsDao metricsDao, CommandLogDao commandLogDao,
                  PairingDao pairingDao) {
        this(socket, sessionManager, parser, clientDao, metricsDao, commandLogDao,
                pairingDao, null);
    }

    /** Constructor đầy đủ nhất (Activity Monitoring): kèm {@link ActivityDao}. */
    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser,
                  ClientDao clientDao, MetricsDao metricsDao, CommandLogDao commandLogDao,
                  PairingDao pairingDao, ActivityDao activityDao) {
        this.socket = socket;
        this.sessionManager = sessionManager;
        this.parser = parser;
        this.clientDao = clientDao;
        this.metricsDao = metricsDao;
        this.commandLogDao = commandLogDao;
        this.pairingDao = pairingDao;
        this.activityDao = activityDao;
    }

    @Override
    public void run() {
        ClientSession session = null;
        try (Socket socketRef = socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))) {

            // --- Giai đoạn Handshake (bắt buộc là gói đầu tiên) ---
            socketRef.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            String firstLine = reader.readLine();
            if (firstLine == null) {
                log.info("Connection from {} closed before handshake", socketRef.getRemoteSocketAddress());
                return;
            }

            BaseMessage<?> handshake;
            try {
                handshake = parser.fromLine(firstLine);
            } catch (ProtocolParseException e) {
                log.warn("Rejected connection from {}: invalid first message ({})",
                        socketRef.getRemoteSocketAddress(), e.getMessage());
                return;
            }

            // Device Pairing: gói đầu là PAIR_REQUEST → trả PAIR_RESPONSE rồi đóng
            // (không đăng ký phiên — client sẽ handshake lại bằng token vừa nhận).
            if (handshake.getType() == MessageType.PAIR_REQUEST) {
                handlePairRequest(socketRef, writer, handshake);
                return;
            }

            if (handshake.getType() != MessageType.HANDSHAKE) {
                log.warn("Rejected connection from {}: first message must be HANDSHAKE but was {}",
                        socketRef.getRemoteSocketAddress(), handshake.getType());
                return;
            }

            // Xác thực thiết bị: token phải khớp DB và is_paired = 1 (chỉ khi có DB).
            if (clientDao != null) {
                String token = handshake.getPayload() instanceof HandshakePayload hp
                        ? hp.deviceToken() : null;
                String clientIdForAuth = resolveClientId(handshake);
                if (!clientDao.isValidPairing(clientIdForAuth, token)) {
                    log.warn("[{}] Rejected connection from {}: {} (missing/invalid deviceToken)",
                            clientIdForAuth, socketRef.getRemoteSocketAddress(),
                            ERROR_UNAUTHORIZED_DEVICE);
                    return;
                }
            }

            String clientId = resolveClientId(handshake);
            if (clientId == null || clientId.isBlank()) {
                log.warn("Rejected connection from {}: missing clientId", socketRef.getRemoteSocketAddress());
                return;
            }

            // --- Đăng ký phiên và chuyển sang vòng lặp chính ---
            session = new ClientSession(clientId, socketRef, reader, writer, parser);
            if (handshake.getPayload() instanceof HandshakePayload payload) {
                session.setDeviceInfo(payload);
            }
            // Task 4.1: upsert client vào DB (set ONLINE + last_seen) khi handshake
            if (clientDao != null && handshake.getPayload() instanceof HandshakePayload device) {
                clientDao.upsertClient(new ClientRecord(clientId, device.deviceName(),
                        device.deviceType(), device.osVersion(), device.ipAddress(),
                        device.macAddress(), "ONLINE"));
            }
            session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond());
            sessionManager.register(session);
            socketRef.setSoTimeout(0); // không timeout khi đọc thường, dead-session do sweeper lo

            log.info("[{}] Handshake OK from {} (device: {})", clientId, session.getRemoteAddress(),
                    session.getDeviceInfo() == null ? "unknown" : session.getDeviceInfo().deviceName());

            messageLoop(session, reader);

        } catch (SocketTimeoutException e) {
            log.warn("Handshake timeout ({}ms) from {}", HANDSHAKE_TIMEOUT_MS, socket.getRemoteSocketAddress());
        } catch (SocketException e) {
            log.info("Client connection dropped abruptly: {}", e.getMessage());
        } catch (IOException e) {
            log.error("I/O error in client handler: {}", e.getMessage());
        } finally {
            // Dọn dẹp: gỡ session (nếu vẫn là phiên hiện hành) và đóng socket.
            if (session != null) {
                sessionManager.removeSession(session);
            }
        }
    }

    /** Vòng lặp chính: đọc từng dòng LDJSON cho tới khi client ngắt (EOF). */
    private void messageLoop(ClientSession session, BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            BaseMessage<?> message;
            try {
                message = parser.fromLine(line);
            } catch (ProtocolParseException e) {
                log.warn("[{}] Dropping malformed line: {}", session.getClientId(), e.getMessage());
                continue;
            }

            switch (message.getType()) {
                case HEARTBEAT -> {
                    session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond());
                    session.send(BaseMessage.of(MessageType.ACK, SERVER_ID, Map.of()));
                }
                case ACK -> {
                    // Task 3.3: client ACK lệnh COMMAND (payload chứa commandId) —
                    // Task 4.1: cập nhật status ACKED trong command_logs.
                    session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond());
                    if (message.getPayload() instanceof Map<?, ?> payload
                            && payload.get("commandId") != null) {
                        String commandId = String.valueOf(payload.get("commandId"));
                        log.info("[{}] COMMAND ACK received (commandId={})",
                                session.getClientId(), commandId);
                        if (commandLogDao != null) {
                            commandLogDao.markAcked(commandId);
                        }
                    }
                    // ACK CONFIG_SYNC (bổ sung 08/11): chỉ ghi nhận khi client
                    // thực sự gửi status — ACK heartbeat thường không có field này.
                    if (message.getPayload() instanceof Map<?, ?> configPayload
                            && configPayload.get("configStatus") != null) {
                        long revision = parseLong(configPayload.get("configRevision"));
                        String status = String.valueOf(configPayload.get("configStatus"));
                        String configMessage = configPayload.get("configMessage") == null
                                ? "" : String.valueOf(configPayload.get("configMessage"));
                        session.setLastConfigAck(revision, status, configMessage);
                        log.info("[{}] CONFIG_SYNC ACK rev={} status={} message={}",
                                session.getClientId(), revision, status, configMessage);
                        if (sessionManager != null) {
                            sessionManager.fireConfigAck(session, revision, status, configMessage);
                        }
                    }
                }
                case METRICS_REPORT -> {
                    // Task 2.3: MỌI dữ liệu inbound (không chỉ HEARTBEAT) là dấu hiệu
                    // client còn sống — làm mới timestamp để sweeper không gỡ nhầm.
                    session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond());
                    // Task 3.1: lưu metrics traffic mới nhất vào session (GUI/CSDL Phase sau đọc từ đây).
                    if (message.getPayload() instanceof MetricsPayload metrics) {
                        session.setLastMetrics(metrics);
                        // Task 4.1: ghi lịch sử traffic vào DB
                        if (metricsDao != null) {
                            metricsDao.insert(session.getClientId(), metrics);
                        }
                        log.debug("[{}] METRICS_REPORT: down={}B up={}B ({} B/s / {} B/s)",
                                session.getClientId(), metrics.downloadBytes(), metrics.uploadBytes(),
                                metrics.downloadSpeedBps(), metrics.uploadSpeedBps());
                    } else {
                        log.debug("[{}] METRICS_REPORT with unexpected payload: {}",
                                session.getClientId(), message.getPayload());
                    }
                }
                case ACTIVITY_REPORT -> {
                    // Activity Monitoring (Mục 3.9): mọi dữ liệu inbound là dấu
                    // hiệu sống — làm mới heartbeat để sweeper không gỡ nhầm.
                    session.setLastHeartbeatTimestamp(Instant.now().getEpochSecond());
                    if (message.getPayload() instanceof ActivityReportPayload activity) {
                        session.setLastActivity(activity); // Dashboard đọc trực tiếp (realtime)
                        // DEDUP (chống spam nhật ký): người dùng ở yên 1 app/video
                        // → client vẫn gửi mỗi 10s nhưng process+title+url KHÔNG đổi
                        // → KHÔNG insert thêm dòng, chỉ giữ chữ ký + timestamp.
                        // Chỉ khi app/tab/URL THAY ĐỔI mới ghi bản ghi mới vào DB
                        // (URL đổi = chuyển trang web → phải ghi dòng mới).
                        String signature = activity.activeProcessName() + "\u0000"
                                + activity.activeWindowTitle() + "\u0000"
                                + activity.activeUrlOrDomain();
                        if (!signature.equals(session.getActivitySignature())) {
                            if (activityDao != null) {
                                activityDao.logActivity(session.getClientId(),
                                        activity.activeProcessName(), activity.activeWindowTitle(),
                                        activity.activeUrlOrDomain());
                            }
                            session.setActivitySignature(signature);
                            log.debug("[{}] ACTIVITY_REPORT recorded (changed): {} — {}",
                                    session.getClientId(), activity.activeProcessName(),
                                    activity.activeWindowTitle());
                        } else {
                            log.debug("[{}] ACTIVITY_REPORT deduplicated (unchanged): {} — {}",
                                    session.getClientId(), activity.activeProcessName(),
                                    activity.activeWindowTitle());
                        }
                        // Kích hoạt event listener (Dashboard cập nhật "Đang mở: ...")
                        sessionManager.fireActivityReport(session, activity);
                    }
                }
                case HANDSHAKE ->
                        log.warn("[{}] Redundant HANDSHAKE ignored", session.getClientId());
                case STREAM_CONTROL -> {
                    // Live Screen Streaming: server gửi lệnh start/stop tới client.
                    // Không có ACK bắt buộc — client xử lý best-effort trên thread riêng.
                    if (message.getPayload() instanceof com.parental.common.protocol.payload.StreamControlPayload control) {
                        log.debug("[{}] STREAM_CONTROL {}: width={}, height={}, quality={}, fps={}",
                                session.getClientId(), control.action(), control.width(),
                                control.height(), control.quality(), control.fps());
                    } else {
                        log.warn("[{}] STREAM_CONTROL với payload không hợp lệ", session.getClientId());
                    }
                }
                case STREAM_FRAME -> {
                    // Client gửi frame ảnh về (chỉ dùng khi server đang mở RemoteScreenDialog).
                    // Validate trước khi lưu: payload null/rỗng/quá lớn/Base64 hỏng
                    // phải bị loại an toàn, KHÔNG được NPE hay giữ ảnh khổng lồ.
                    if (message.getPayload() instanceof ScreenFramePayload frame) {
                        String invalidReason = validateStreamFrame(frame);
                        if (invalidReason != null) {
                            log.warn("[{}] Dropping invalid STREAM_FRAME (seq={}, {}x{}, jpegChars={}): {}",
                                    session.getClientId(), frame.seqNo(), frame.width(),
                                    frame.height(),
                                    frame.jpegBase64() == null ? -1 : frame.jpegBase64().length(),
                                    invalidReason);
                            break;
                        }
                        session.setLastStreamFrame(frame);
                        log.debug("[{}] STREAM_FRAME seq={} {}x{} (jpeg {} chars) quality={} tsMs={}",
                                session.getClientId(), frame.seqNo(), frame.width(),
                                frame.height(), frame.jpegBase64().length(),
                                frame.quality(), frame.tsMs());
                    } else {
                        log.warn("[{}] STREAM_FRAME với payload không hợp lệ (null/sai kiểu)",
                                session.getClientId());
                    }
                }
                default ->
                        log.warn("[{}] Unexpected {} from client ignored", session.getClientId(), message.getType());
            }
        }
        log.info("[{}] Client disconnected (EOF)", session.getClientId());
    }

    /**
     * Kiểm tra một frame trước khi lưu/giải mã. Trả {@code null} nếu hợp lệ,
     * ngược lại trả lý do ngắn gọn để log.
     *
     * <p>Chỉ chấp nhận JPEG (magic Base64 {@code /9j/}) — đúng như
     * {@code ScreenCaptureService} phía client tạo ra. Kiểm tra kích thước
     * chuỗi Base64 TRƯỚC khi decode để không cấp phát bộ nhớ theo payload
     * độc hại; kiểm tra pixel để chặn ảnh giải nén quá lớn.</p>
     */
    static String validateStreamFrame(ScreenFramePayload frame) {
        if (frame == null) {
            return "null payload";
        }
        String base64 = frame.jpegBase64();
        if (base64 == null || base64.isBlank()) {
            return "empty jpegBase64";
        }
        if (frame.width() <= 0 || frame.height() <= 0) {
            return "invalid dimensions " + frame.width() + "x" + frame.height();
        }
        if (frame.width() > MAX_FRAME_DIMENSION || frame.height() > MAX_FRAME_DIMENSION) {
            return "dimension exceeds " + MAX_FRAME_DIMENSION + "px";
        }
        if ((long) frame.width() * frame.height() > MAX_FRAME_PIXELS) {
            return "pixel count exceeds " + MAX_FRAME_PIXELS;
        }
        if (base64.length() > MAX_FRAME_BASE64_CHARS) {
            return "jpegBase64 too large (" + base64.length() + " chars)";
        }
        if (base64.length() % 4 != 0 || !isBase64(base64)) {
            return "jpegBase64 is not valid Base64";
        }
        // JPEG luôn bắt đầu bằng FF D8 FF; Base64 của 3 byte này là "/9j/".
        if (!base64.startsWith("/9j/")) {
            return "not a JPEG payload";
        }
        return null;
    }

    /** Quét Base64 không cấp phát mảng byte (chỉ chấp nhận bảng chữ chuẩn + padding cuối). */
    private static boolean isBase64(String value) {
        int padding = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=') {
                padding++;
                if (padding > 2 || i < value.length() - 2) {
                    return false;
                }
                continue;
            }
            if (padding > 0) {
                return false; // ký tự hợp lệ sau '='
            }
            boolean valid = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** Ưu tiên clientId trong envelope; fallback sang MAC từ HandshakePayload. */
    private static String resolveClientId(BaseMessage<?> handshake) {
        if (handshake.getClientId() != null && !handshake.getClientId().isBlank()) {
            return handshake.getClientId();
        }
        if (handshake.getPayload() instanceof HandshakePayload payload) {
            return payload.macAddress();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Device Pairing (PAIR_REQUEST → PAIR_RESPONSE) — PROTOCOL.md Mục 3.6/3.7
    // ------------------------------------------------------------------

    /**
     * Xử lý gói ghép đôi: kiểm tra PIN qua {@link PairingDao}, cấp
     * {@code deviceToken} (UUID) khi hợp lệ rồi trả lời và ĐÓNG kết nối
     * (luồng ghép đôi không giữ phiên — client handshake lại sau).
     */
    private void handlePairRequest(Socket socketRef, PrintWriter writer, BaseMessage<?> message) {
        PairRequestPayload request = message.getPayload() instanceof PairRequestPayload pairRequest
                ? pairRequest : null;
        String clientId = resolveClientId(message);
        log.info("PAIR_REQUEST from {} (clientId={}, device={})",
                socketRef.getRemoteSocketAddress(), clientId,
                request == null ? "unknown" : request.deviceName());

        PairResponsePayload response = evaluatePairRequest(clientId, request);

        try {
            writer.println(parser.toLine(BaseMessage.of(MessageType.PAIR_RESPONSE,
                    SERVER_ID, response)));
            writer.flush();
        } catch (ProtocolParseException e) {
            log.error("Failed to serialize PAIR_RESPONSE: {}", e.getMessage());
        }
        if (response.success()) {
            log.info("[{}] Device paired successfully — deviceToken issued (device: {})",
                    clientId, request == null ? "unknown" : request.deviceName());
        } else {
            log.warn("[{}] Pairing rejected: {}", clientId, response.message());
        }
    }

    /** Kiểm tra PIN và cấp token (thuần logic — không đụng socket, dễ test). */
    private PairResponsePayload evaluatePairRequest(String clientId, PairRequestPayload request) {
        if (pairingDao == null) {
            return new PairResponsePayload(false, null, "PAIRING_NOT_AVAILABLE");
        }
        if (clientId == null || clientId.isBlank()) {
            return new PairResponsePayload(false, null, PairResponsePayload.MSG_INVALID_PIN);
        }
        PinState pinState = pairingDao.getPinState(request == null ? null : request.pin());
        if (pinState != PinState.ACTIVE) {
            String reason = switch (pinState) {
                case EXPIRED -> PairResponsePayload.MSG_PIN_EXPIRED;
                case USED -> PairResponsePayload.MSG_PIN_ALREADY_USED;
                default -> PairResponsePayload.MSG_INVALID_PIN;
            };
            return new PairResponsePayload(false, null, reason);
        }
        if (!pairingDao.validateAndConsumePin(request.pin())) {
            // Race cực hiếm: PIN bị thiết bị khác tiêu thụ giữa 2 lần đọc.
            return new PairResponsePayload(false, null, PairResponsePayload.MSG_PIN_ALREADY_USED);
        }
        String deviceToken = java.util.UUID.randomUUID().toString();
        if (clientDao == null || !clientDao.saveDeviceToken(clientId, deviceToken)) {
            return new PairResponsePayload(false, null, "STORE_FAILED");
        }
        return new PairResponsePayload(true, deviceToken, PairResponsePayload.MSG_PAIRED);
    }

    private static long parseLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
