package com.parental.client.power;

import com.parental.common.protocol.ActionType;

/**
 * Thực thi lệnh nguồn/phiên từ xa (Tính năng 3.4) — CHỈ 4 hành động trong
 * allowlist, không nhận shell/command tùy ý từ Server.
 */
public interface PowerCommandExecutor {

    /**
     * Kết quả thực thi. {@code accepted=false} → Client KHÔNG gửi ACK, Server
     * hiển thị thất bại thay vì tưởng đã thành công.
     */
    record Result(boolean accepted, String message) {

        public static Result accepted(String message) {
            return new Result(true, message);
        }

        public static Result rejected(String message) {
            return new Result(false, message);
        }
    }

    /**
     * Chỉ chấp nhận {@code LOCK_SESSION}, {@code LOGOFF_SESSION},
     * {@code RESTART_DEVICE}, {@code SHUTDOWN_DEVICE}; mọi ActionType khác trả
     * {@code accepted=false}. Không bao giờ ném exception.
     */
    Result execute(ActionType action);
}
