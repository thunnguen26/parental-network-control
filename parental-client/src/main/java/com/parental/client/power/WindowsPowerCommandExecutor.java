package com.parental.client.power;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.ActionType;

/**
 * Triển khai {@link PowerCommandExecutor} trên Windows (Tính năng 3.4) —
 * <b>allowlist tuyệt đối</b>, không nhận chuỗi shell/command từ Server:
 *
 * <ul>
 *   <li>{@code LOCK_SESSION} → {@code rundll32.exe user32.dll,LockWorkStation}</li>
 *   <li>{@code LOGOFF_SESSION} → {@code shutdown.exe /l}</li>
 *   <li>{@code RESTART_DEVICE} → {@code shutdown.exe /r /t 5 /c "..."}</li>
 *   <li>{@code SHUTDOWN_DEVICE} → {@code shutdown.exe /s /t 5 /c "..."}</li>
 * </ul>
 *
 * <p>Đặt {@code /t 5} cho restart/shutdown để người dùng máy con còn 5 giây
 * hủy bằng {@code shutdown /a}; không dùng {@code /f} nên không ép đóng ứng
 * dụng. Timeout 10s, lỗi/thiếu quyền/OS khác → {@code accepted=false} (Client
 * không ACK), log rõ, KHÔNG ném exception.</p>
 *
 * <p>Test inject {@link CommandRunner} + cờ OS — không bao giờ tắt máy thật
 * trong test suite.</p>
 */
public class WindowsPowerCommandExecutor implements PowerCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(WindowsPowerCommandExecutor.class);

    public static final int PROCESS_TIMEOUT_SECONDS = 10;

    /** Seam test: chạy lệnh và trả exit code (không chạy process thật khi mock). */
    @FunctionalInterface
    interface CommandRunner {
        int run(String[] command, int timeoutSeconds) throws IOException, InterruptedException;
    }

    private final boolean windowsOs;
    private final CommandRunner runner;

    public WindowsPowerCommandExecutor() {
        this(isWindows(), WindowsPowerCommandExecutor::runProcess);
    }

    /** Constructor test — package-private, không chạy lệnh thật. */
    WindowsPowerCommandExecutor(boolean windowsOs, CommandRunner runner) {
        this.windowsOs = windowsOs;
        this.runner = runner;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public Result execute(ActionType action) {
        if (action == null) {
            return Result.rejected("missing action");
        }
        String[] command = commandFor(action);
        if (command == null) {
            log.warn("Power action {} is not in the allowlist — rejected", action);
            return Result.rejected("unsupported action");
        }
        if (!windowsOs) {
            log.warn("Power action {} rejected: OS is not Windows", action);
            return Result.rejected("power actions are only supported on Windows");
        }
        try {
            int exitCode = runner.run(command, PROCESS_TIMEOUT_SECONDS);
            if (exitCode == 0) {
                log.info("Power action {} accepted (command: {})", action, String.join(" ", command));
                return Result.accepted(action + " accepted");
            }
            log.warn("Power action {} failed: command exited with code {}", action, exitCode);
            return Result.rejected("command exited with code " + exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Power action {} interrupted", action);
            return Result.rejected("interrupted");
        } catch (IOException e) {
            log.warn("Power action {} failed to start: {}", action, e.getMessage());
            return Result.rejected("cannot start command: " + e.getMessage());
        } catch (SecurityException e) {
            log.warn("Power action {} blocked by security policy: {}", action, e.getMessage());
            return Result.rejected("blocked by security policy");
        } catch (RuntimeException e) {
            log.error("Unexpected error for power action {}: {}", action, e.getMessage());
            return Result.rejected("unexpected error");
        }
    }

    /**
     * Mapping allowlist (package-private để test kiểm tra từng lệnh). Trả
     * {@code null} cho action không được hỗ trợ.
     */
    static String[] commandFor(ActionType action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case LOCK_SESSION -> new String[]{"rundll32.exe", "user32.dll,LockWorkStation"};
            case LOGOFF_SESSION -> new String[]{"shutdown.exe", "/l"};
            case RESTART_DEVICE -> new String[]{"shutdown.exe", "/r", "/t", "5",
                    "/c", "Parental Control: restart requested by parent"};
            case SHUTDOWN_DEVICE -> new String[]{"shutdown.exe", "/s", "/t", "5",
                    "/c", "Parental Control: shutdown requested by parent"};
            default -> null;
        };
    }

    private static int runProcess(String[] command, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(new File(System.getProperty("java.io.tmpdir")));
        Process process = builder.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            log.warn("Power command timed out after {}s: {}", timeoutSeconds,
                    String.join(" ", command));
            return -1;
        }
        return process.exitValue();
    }
}
