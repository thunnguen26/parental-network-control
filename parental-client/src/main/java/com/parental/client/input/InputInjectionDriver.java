package com.parental.client.input;

/**
 * Lớp trừu tượng thực thi sự kiện chuột/bàn phím trên máy Client.
 *
 * <p>Production dùng {@link RobotInputInjectionDriver} (java.awt.Robot); test
 * dùng driver giả để KHÔNG thao tác thật trên máy khi chạy headless. Mọi tọa
 * độ là tọa độ chuẩn hóa {@code 0..1} của desktop ảo — driver tự quy đổi sang
 * pixel tuyệt đối, nên Server không bao giờ gửi tọa độ tuyệt đối.</p>
 */
public interface InputInjectionDriver {

    /** Có thể inject input trên máy này không (có display, Robot khả dụng). */
    boolean isReady();

    void moveMouse(double normalizedX, double normalizedY);

    /** @param button 1 = trái, 2 = giữa, 3 = phải */
    void pressMouse(int button);

    void releaseMouse(int button);

    void wheel(double normalizedX, double normalizedY, int notches);

    void pressKey(int keyCode);

    void releaseKey(int keyCode);

    /** Nhả toàn bộ phím/nút đang giữ — gọi khi phiên kết thúc để tránh kẹt input. */
    void releaseAll();
}
