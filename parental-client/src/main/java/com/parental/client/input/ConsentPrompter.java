package com.parental.client.input;

import java.util.function.Consumer;

/**
 * Hộp thoại xin phép người dùng ĐANG THAO TÁC TRỰC TIẾP tại máy Client trước
 * khi bật phiên điều khiển. Không bao giờ tự chấp nhận, không mặc định bật.
 */
public interface ConsentPrompter {

    enum Decision {
        /** Người dùng bấm Chấp nhận. */
        ACCEPTED,
        /** Người dùng bấm Từ chối hoặc đóng hộp thoại. */
        DECLINED,
        /** Hết thời gian chờ mà không phản hồi. */
        EXPIRED,
        /** Không thể hiện hộp thoại (headless/không có GUI). */
        UNAVAILABLE
    }

    /**
     * Hiện hộp thoại xin phép. Callback phải được gọi ĐÚNG MỘT LẦN; có thể gọi
     * đồng bộ hoặc bất đồng bộ. Implementation phải không bao giờ tự trả
     * {@link Decision#ACCEPTED}.
     */
    void requestConsent(String sessionId, int timeoutSeconds, Consumer<Decision> callback);

    /** Đóng hộp thoại (nếu đang mở) khi phiên bị thu hồi/hết hạn. */
    void cancel();
}
