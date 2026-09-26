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
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.db.ClientDao;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.CommandLogDao;
import com.parental.server.db.MetricsDao;

/**
 * Xử lý một kết nối Client trên thread riêng (chạy trong ExecutorService của
 * {@link TcpServerEngine}).
 *
 * <p>Nghi thức bắt buộc: gói tin đầu tiên phải là {@code HANDSHAKE} để xác định
 * clientId — nếu không, kết nối bị từ chối. Sau handshake, vòng lặp chính đọc
 * LDJSON qua {@code BufferedReader.readLine()}, giải mã bằng
 * {@link JsonProtocolParser}: HEARTBEAT cập nhật timestamp sống và phản hồi
 * ACK; các gói khác chỉ ghi log (Business Logic thuộc Phase 4).</p>
 */
public class ClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    /** Định danh của Server trong trường clientId khi gửi gói xuống Client. */
    public static final String SERVER_ID = "server";

    /** Thời gian tối đa chờ gói HANDSHAKE đầu tiên (chống treo thread). */
    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;

    private final Socket socket;
    private final ClientSessionManager sessionManager;
    private final JsonProtocolParser parser;
    /** DB layer (Task 4.1) — nullable: null = chạy không database. */
    private final ClientDao clientDao;
    private final MetricsDao metricsDao;
    private final CommandLogDao commandLogDao;

    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser) {
        this(socket, sessionManager, parser, null, null, null);
    }

    ClientHandler(Socket socket, ClientSessionManager sessionManager, JsonProtocolParser parser,
                  ClientDao clientDao, MetricsDao metricsDao, CommandLogDao commandLogDao) {
        this.socket = socket;
        this.sessionManager = sessionManager;
        this.parser = parser;
        this.clientDao = clientDao;
        this.metricsDao = metricsDao;
        this.commandLogDao = commandLogDao;
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

            if (handshake.getType() != MessageType.HANDSHAKE) {
                log.warn("Rejected connection from {}: first message must be HANDSHAKE but was {}",
                        socketRef.getRemoteSocketAddress(), handshake.getType());
                return;
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
                case HANDSHAKE ->
                        log.warn("[{}] Redundant HANDSHAKE ignored", session.getClientId());
                default ->
                        log.warn("[{}] Unexpected {} from client ignored", session.getClientId(), message.getType());
            }
        }
        log.info("[{}] Client disconnected (EOF)", session.getClientId());
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
}
