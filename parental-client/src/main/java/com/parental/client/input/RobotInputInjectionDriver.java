package com.parental.client.input;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thực thi input bằng {@link Robot}. Chỉ dùng sau khi người dùng tại Client đã
 * chấp thuận phiên; mọi lỗi khởi tạo/inject đều ném {@link IllegalStateException}
 * để {@link RemoteInputController} kết thúc phiên và nhả input an toàn.
 *
 * <p>Driver theo dõi phím/nút đang giữ để {@link #releaseAll()} luôn nhả sạch
 * khi phiên dừng — tránh kẹt phím/nút trên máy Client. Không ghi log nội dung
 * phím hay vị trí con trỏ.</p>
 */
public final class RobotInputInjectionDriver implements InputInjectionDriver {

    private static final Logger log = LoggerFactory.getLogger(RobotInputInjectionDriver.class);

    private final Set<Integer> heldKeys = new HashSet<>();
    private final Set<Integer> heldButtons = new HashSet<>();
    private Robot robot;

    @Override
    public boolean isReady() {
        try {
            return !GraphicsEnvironment.isHeadless()
                    && !WindowsSecureDesktopGuard.isSecureDesktopLikely();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private synchronized Robot ensureRobot() {
        if (robot != null) {
            return robot;
        }
        if (!isReady()) {
            throw new IllegalStateException("No interactive display is available");
        }
        try {
            Robot created = new Robot();
            created.setAutoDelay(0);
            created.setAutoWaitForIdle(false);
            robot = created;
            return created;
        } catch (AWTException | RuntimeException e) {
            throw new IllegalStateException("Cannot create Robot for input injection", e);
        }
    }

    @Override
    public synchronized void moveMouse(double normalizedX, double normalizedY) {
        Rectangle bounds = virtualScreenBounds();
        ensureRobot().mouseMove(toPixel(bounds.x, bounds.width, normalizedX),
                toPixel(bounds.y, bounds.height, normalizedY));
    }

    @Override
    public synchronized void pressMouse(int button) {
        ensureRobot().mousePress(maskFor(button));
        heldButtons.add(button);
    }

    @Override
    public synchronized void releaseMouse(int button) {
        ensureRobot().mouseRelease(maskFor(button));
        heldButtons.remove(button);
    }

    @Override
    public synchronized void wheel(double normalizedX, double normalizedY, int notches) {
        Rectangle bounds = virtualScreenBounds();
        Robot r = ensureRobot();
        r.mouseMove(toPixel(bounds.x, bounds.width, normalizedX),
                toPixel(bounds.y, bounds.height, normalizedY));
        r.mouseWheel(notches);
    }

    @Override
    public synchronized void pressKey(int keyCode) {
        ensureRobot().keyPress(keyCode);
        heldKeys.add(keyCode);
    }

    @Override
    public synchronized void releaseKey(int keyCode) {
        ensureRobot().keyRelease(keyCode);
        heldKeys.remove(keyCode);
    }

    /**
     * Nhả mọi phím/nút đang giữ. Không bao giờ ném exception — được gọi cả khi
     * Robot lỗi hoặc phiên đã bị thu hồi.
     */
    @Override
    public synchronized void releaseAll() {
        Robot r;
        try {
            r = robot;
        } catch (RuntimeException e) {
            r = null;
        }
        if (r != null) {
            for (Integer key : new HashSet<>(heldKeys)) {
                try {
                    r.keyRelease(key);
                } catch (RuntimeException e) {
                    log.debug("release key ignored: {}", e.getMessage());
                }
            }
            for (Integer button : new HashSet<>(heldButtons)) {
                try {
                    r.mouseRelease(maskFor(button));
                } catch (RuntimeException e) {
                    log.debug("release mouse button ignored: {}", e.getMessage());
                }
            }
        }
        heldKeys.clear();
        heldButtons.clear();
    }

    private static int maskFor(int button) {
        return switch (button) {
            case 1 -> InputEvent.BUTTON1_DOWN_MASK;
            case 2 -> InputEvent.BUTTON2_DOWN_MASK;
            case 3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> throw new IllegalArgumentException("Unsupported mouse button: " + button);
        };
    }

    private static int toPixel(int origin, int size, double normalized) {
        if (size <= 0) {
            return origin;
        }
        double bounded = Double.isFinite(normalized)
                ? Math.max(0.0, Math.min(1.0, normalized)) : 0.0;
        return origin + (int) Math.round(bounded * (size - 1));
    }

    private static Rectangle virtualScreenBounds() {
        Rectangle union = null;
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            union = union == null ? new Rectangle(bounds) : union.union(bounds);
        }
        if (union == null) {
            throw new IllegalStateException("No display bounds are available");
        }
        return union;
    }
}
