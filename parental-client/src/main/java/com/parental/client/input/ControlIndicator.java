package com.parental.client.input;

/**
 * Chỉ báo điều khiển từ xa phải HIỂN THỊ LIÊN TỤC trong suốt phiên và có nút
 * “Dừng điều khiển” để người dùng tại Client dừng ngay lập tức.
 */
public interface ControlIndicator {

    /**
     * Hiện chỉ báo. {@code stopAction} phải được gọi khi người dùng bấm nút
     * “Dừng điều khiển” hoặc đóng cửa sổ chỉ báo.
     */
    void show(Runnable stopAction);

    void hide();
}
