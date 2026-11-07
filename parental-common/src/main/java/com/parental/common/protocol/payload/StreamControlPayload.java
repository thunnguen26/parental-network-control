package com.parental.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Base64;

/**
 * Payload của gói {@code STREAM_CONTROL} — Server gửi lệnh điều khiển stream
 * màn hình remote (Mục 3.10 của {@code docs/PROTOCOL.md}, Live Screen Streaming).
 *
 * <p>Chỉ có 2 action hợp lệ: {@value #ACTION_START} và {@value #ACTION_STOP}.</p>
 *
 * <pre>{@code
 * {
 *   "type": "STREAM_CONTROL",
 *   "clientId": "server",
 *   "timestamp": 1729912000000,
 *   "payload": {
 *     "action": "START",
 *     "width": 960,
 *     "height": 540,
 *     "quality": 0.7,
 *     "fps": 5
 *   }
 * }
 * }</pre>
 *
 * @param action  "START" hoặc "STOP"
 * @param width   chiều rộng khuyến nghị của mỗi frame (px); 0 = tắt client-side scale
 * @param height  chiều cao khuyến nghị; 0 = client tự chọn theo tỉ lệ 16:9
 * @param quality chỉ số nén JPEG 0..1 (0.1..1.0 sau validation)
 * @param fps     tần suất khung hình khuyến nghị (FPS); 0 = tắt stream sau khi nhận START
 */
public record StreamControlPayload(
        String action,
        int width,
        int height,
        double quality,
        long fps) {

    public static final String ACTION_START = "START";
    public static final String ACTION_STOP = "STOP";

    /** Mặc định an toàn: giữ stream tắt và dùng request hiện có của client. */
    public static final StreamControlPayload DEFAULT =
            new StreamControlPayload(ACTION_START, 960, 540, 0.7, 5L);

    @JsonCreator
    public StreamControlPayload {
        if (action == null) {
            action = ACTION_STOP;
        }
        quality = Math.max(0.1, Math.min(1.0, quality));
        if (width <= 0) {
            width = 960;
        }
        if (height <= 0) {
            height = (int) Math.round(width * (16.0 / 9.0));
        }
        if (fps <= 0) {
            fps = 5L;
        }
    }

    /**
     * Tạo request mặc định bằng payload chuỗi {@code Base64} an toàn (không có
     * thông tin mật).
     *
     * <p>Dùng để xác nhận đọc payload Base64 không bị sai — chỉ dùng cho test
     * năng lực đọc, không phải payload mã hóa dài hạn.</p>
     */
    public static StreamControlPayload fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return DEFAULT;
        }
        return new StreamControlPayload(ACTION_START, 0, 0, 0.7, 5L);
    }
}
