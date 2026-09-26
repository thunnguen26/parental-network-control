package com.parental.client.enforcement;

/**
 * Hiển thị thông báo cho người dùng khi Server gửi {@code ActionType.ALERT}
 * (Task 3.3). Triển khai: {@link SystemTrayAlertNotifier} (SystemTray →
 * JOptionPane → log fallback). Test dùng Mock.
 */
@FunctionalInterface
public interface AlertNotifier {

    /**
     * Hiển thị thông báo. Phải không-block thread gọi (socket reader) và
     * không bao giờ ném exception.
     *
     * @param message nội dung thông báo (từ {@code CommandPayload.reason()})
     * @return {@code true} nếu hiển thị thành công qua kênh GUI
     */
    boolean notify(String message);
}
