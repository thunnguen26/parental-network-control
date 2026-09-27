package com.parental.client.identity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.ProtocolParseException;
import com.parental.common.protocol.payload.PairRequestPayload;
import com.parental.common.protocol.payload.PairResponsePayload;

/**
 * Client ghép đôi one-shot (Device Pairing): mở một kết nối TCP tới server,
 * gửi {@code PAIR_REQUEST} (PIN + thông tin máy — PROTOCOL.md Mục 3.6), chờ
 * {@code PAIR_RESPONSE} rồi ĐÓNG kết nối (không giữ phiên — phiên thật được
 * mở bởi {@code TcpClientEngine} sau khi có token).
 *
 * <p>Một instance chỉ dùng cho MỘT lần thử ghép đôi (bất biến sau khi chạy);
 * người dùng bấm nút lần nữa → tạo instance mới.</p>
 */
public class PairingClient {

    /** Timeout chờ PAIR_RESPONSE từ server (ms). */
    public static final int RESPONSE_TIMEOUT_MS = 10_000;
    /** Timeout kết nối TCP (ms). */
    public static final int CONNECT_TIMEOUT_MS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(PairingClient.class);

    private final JsonProtocolParser parser = new JsonProtocolParser();

    /**
     * Kết quả một lần thử ghép đôi.
     *
     * @param success     server xác nhận ghép đôi thành công
     * @param deviceToken token cấp quyền (null khi thất bại)
     * @param message     "PAIRED" hoặc lý do từ chối / mô tả lỗi kết nối
     */
    public record PairingResult(boolean success, String deviceToken, String message) {
    }

    /**
     * Chạy luồng ghép đôi đồng bộ (gọi từ SwingWorker — KHÔNG gọi trên EDT).
     *
     * @param serverHost địa chỉ máy chủ (vd "192.168.1.10")
     * @param serverPort cổng TCP server (8888)
     * @param clientId   UUID định danh bền vững từ {@code client_identity.json}
     * @param pin        mã PIN 6 số nhập từ giao diện ghép đôi
     * @param request    thông tin thiết bị (đóng gói vào PAIR_REQUEST)
     */
    public PairingResult pair(String serverHost, int serverPort, String clientId,
                              String pin, PairRequestPayload request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            log.info("PAIR_REQUEST → {}:{} (clientId={}, device={})",
                    serverHost, serverPort, clientId, request.deviceName());

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(parser.toLine(BaseMessage.of(MessageType.PAIR_REQUEST, clientId, request)));
            writer.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine(); // soTimeout đánh thức khi server im lặng
            if (line == null) {
                return new PairingResult(false, null,
                        "Server đóng kết nối mà không trả lời ghép đôi");
            }
            BaseMessage<?> response = parser.fromLine(line);
            if (response.getType() != MessageType.PAIR_RESPONSE
                    || !(response.getPayload() instanceof PairResponsePayload payload)) {
                return new PairingResult(false, null,
                        "Phản hồi không đúng PAIR_RESPONSE: " + response.getType());
            }
            if (payload.success()) {
                log.info("Pairing succeeded — deviceToken received from server");
            } else {
                log.warn("Pairing rejected by server: {}", payload.message());
            }
            return new PairingResult(payload.success(), payload.deviceToken(), payload.message());
        } catch (IOException e) {
            log.warn("Pairing connection to {}:{} failed: {}", serverHost, serverPort, e.getMessage());
            return new PairingResult(false, null,
                    "Không kết nối được tới server " + serverHost + ":" + serverPort
                            + " (" + e.getMessage() + ")");
        } catch (ProtocolParseException e) {
            log.warn("Malformed PAIR_RESPONSE: {}", e.getMessage());
            return new PairingResult(false, null, "Phản hồi ghép đôi hỏng (JSON sai schema)");
        }
    }
}
