package com.parental.common.protocol.payload;

import java.util.Base64;

/**
 * Payload của gói {@code STREAM_FRAME} — Client gửi frame ảnh màn hình remote
 * về server (khớp Mục 3.10 của {@code docs/PROTOCOL.md}, Live Screen Streaming).
 *
 * <p>Client chụp màn hình bằng {@code java.awt.Robot}, nén JPEG (chất lượng ~ 0.7,
 * khung hình nhỏ / tỉ lệ webpage lành network LAN ~ 5 FPS / 200ms), mã hóa Base64
 * dòng và gửi xuống server. Server nhận vào {@link RemoteScreenDialog} hiển thị
 * mỗi frame trên EDT.</p>
 *
 * <p><b>Tương thích ngược (lenient)</b>: payload brand-new, không có trong
 * protocol cũ — server cũ không send/broadcast message này, client cũ không
 * nhận. Chỉ có ý nghĩa khi cả 2 phía implement message type mới.</p>
 *
 * @param width  độ rộng ảnh (px)
 * @param height độ cao ảnh (px)
 * @param jpegBase64  ảnh JPEG nén, encode Base64 dòng — raw bytes của
 *                    {@code BufferedImage} sau khi compress
 * @param quality  chỉ số chất lượng JPEG 0..1 dùng để nén (debug/metric, không
 *                 dùng để decode). Ví dụ {@code 0.7}.
 * @param seqNo    chỉ số tuần tự của frame trong phiên stream đang chạy (0-based,
 *                 tăng dần). Server có thể dùng để phát hiện thiếu frame / lại
 *                 trình tự.
 * @param tsMs     epoch millis thời điểm chụp (server hiển thị + đo latency tiềm
 *                 năng)
 */
public record ScreenFramePayload(
        int width,
        int height,
        String jpegBase64,
        double quality,
        long seqNo,
        long tsMs) {

    /** Chuỗi Base64 phủ thiết bị — không null. */
    public ScreenFramePayload {
        jpegBase64 = (jpegBase64 == null) ? "" : jpegBase64;
    }
}
