package com.parental.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.payload.ScreenFramePayload;

/**
 * Kiểm thử phòng thủ cho {@link ClientHandler#validateStreamFrame} — payload
 * STREAM_FRAME hỏng không được làm chết handler (Bug A).
 */
class StreamFrameValidationTest {

    /** Ảnh JPEG 1x1 hợp lệ (216 ký tự Base64, chia hết cho 4). */
    private static final String VALID_JPEG =
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDs0NDT/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";

    private static ScreenFramePayload frame(int width, int height, String base64) {
        return new ScreenFramePayload(width, height, base64, 0.7, 1, 1_700_000_000_000L);
    }

    @Test
    @DisplayName("Frame JPEG hợp lệ được chấp nhận")
    void validFrameIsAccepted() {
        assertNull(ClientHandler.validateStreamFrame(frame(960, 540, VALID_JPEG)));
    }

    @Test
    @DisplayName("Null payload bị loại, không NPE")
    void nullPayloadIsRejected() {
        assertNotNull(ClientHandler.validateStreamFrame(null));
    }

    @Test
    @DisplayName("jpegBase64 null/rỗng/toàn khoảng trắng bị loại")
    void emptyBase64IsRejected() {
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, null)));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "")));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "   ")));
    }

    @Test
    @DisplayName("Kích thước 0 hoặc âm bị loại")
    void invalidDimensionsAreRejected() {
        assertNotNull(ClientHandler.validateStreamFrame(frame(0, 540, VALID_JPEG)));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, -1, VALID_JPEG)));
    }

    @Test
    @DisplayName("Cạnh dài vượt giới hạn bị loại")
    void oversizedDimensionIsRejected() {
        assertNotNull(ClientHandler.validateStreamFrame(
                frame(ClientHandler.MAX_FRAME_DIMENSION + 1, 10, VALID_JPEG)));
    }

    @Test
    @DisplayName("Tổng số pixel vượt giới hạn bị loại")
    void oversizedPixelCountIsRejected() {
        int width = 4000;
        int height = (int) (ClientHandler.MAX_FRAME_PIXELS / width) + 1;
        assertNotNull(ClientHandler.validateStreamFrame(frame(width, height, VALID_JPEG)));
    }

    @Test
    @DisplayName("Chuỗi Base64 khổng lồ bị loại TRƯỚC khi decode")
    void oversizedBase64IsRejected() {
        String huge = "/9j/" + "A".repeat(ClientHandler.MAX_FRAME_BASE64_CHARS);
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, huge)));
    }

    @Test
    @DisplayName("Base64 sai ký tự / xuống dòng / sai padding bị loại")
    void malformedBase64IsRejected() {
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "/9j/abc$")));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "/9j/abc\n")));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "AAAAA")));
        assertNotNull(ClientHandler.validateStreamFrame(frame(960, 540, "A===")));
    }

    @Test
    @DisplayName("Dữ liệu Base64 hợp lệ nhưng không phải JPEG bị loại")
    void nonJpegPayloadIsRejected() {
        assertEquals("not a JPEG payload",
                ClientHandler.validateStreamFrame(frame(1, 1, "AAAA")));
    }
}
