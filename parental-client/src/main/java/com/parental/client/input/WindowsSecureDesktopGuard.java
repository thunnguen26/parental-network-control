package com.parental.client.input;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * Phát hiện thô nhưng an toàn rằng Windows đang ở màn hình khóa/UAC (desktop
 * bảo mật) để KHÔNG gửi input.
 *
 * <p>Chỉ đọc danh sách tiến trình hiện có, không can thiệp, không yêu cầu
 * quyền Administrator và tuyệt đối không tìm cách vượt qua cơ chế bảo vệ của
 * hệ điều hành. Khi Windows chuyển sang desktop bảo mật, {@code LogonUI.exe}
 * (màn hình khóa/đăng nhập), {@code LockApp.exe} (lock screen) hoặc
 * {@code consent.exe} (hộp thoại UAC) xuất hiện. Nếu không đọc được thông tin
 * tiến trình, guard trả {@code false} — Robot vẫn bị Windows chặn tự nhiên
 * trên desktop bảo mật; guard chỉ là lớp phòng thủ bổ sung.</p>
 */
final class WindowsSecureDesktopGuard {

    private static final long CACHE_MS = 800L;

    private static volatile long lastCheckMs;
    private static volatile boolean lastResult;

    private WindowsSecureDesktopGuard() {
    }

    static boolean isSecureDesktopLikely() {
        if (!isWindows()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CACHE_MS) {
            return lastResult;
        }
        boolean detected = scan();
        lastResult = detected;
        lastCheckMs = now;
        return detected;
    }

    private static boolean scan() {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return processes.anyMatch(WindowsSecureDesktopGuard::isSecureProcess);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isSecureProcess(ProcessHandle handle) {
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        if (isSecureExecutable(command)) {
            return true;
        }
        return info.commandLine()
                .map(WindowsSecureDesktopGuard::containsSecureExecutable)
                .orElse(false);
    }

    private static boolean containsSecureExecutable(String commandLine) {
        String lower = commandLine.toLowerCase(Locale.ROOT);
        return lower.contains("logonui.exe") || lower.contains("lockapp.exe")
                || lower.contains("consent.exe");
    }

    private static boolean isSecureExecutable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = lower.lastIndexOf('/');
        String name = slash >= 0 ? lower.substring(slash + 1) : lower;
        return name.equals("logonui.exe") || name.equals("lockapp.exe")
                || name.equals("consent.exe");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
