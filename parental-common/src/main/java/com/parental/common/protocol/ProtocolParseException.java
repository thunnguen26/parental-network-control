package com.parental.common.protocol;

/**
 * Bọc mọi lỗi phân tích/tạo gói tin giao thức (LDJSON) — che giấu ngoại lệ
 * thô của Jackson ({@code JsonProcessingException} / {@code IOException}) để
 * tầng Network chỉ phải xử lý một loại exception duy nhất.
 */
public class ProtocolParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProtocolParseException(String message) {
        super(message);
    }

    public ProtocolParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
