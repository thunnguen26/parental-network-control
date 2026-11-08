package com.parental.client.power;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.parental.common.protocol.ActionType;

/**
 * Test allowlist lệnh nguồn (Tính năng 3.4) — KHÔNG chạy process thật.
 */
class WindowsPowerCommandExecutorTest {

    /** Runner giả: ghi nhận lệnh, trả exit code cấu hình. */
    private static final class FakeRunner implements WindowsPowerCommandExecutor.CommandRunner {
        final List<String[]> commands = new CopyOnWriteArrayList<>();
        final int exitCode;
        final RuntimeException failure;

        FakeRunner(int exitCode) {
            this(exitCode, null);
        }

        FakeRunner(int exitCode, RuntimeException failure) {
            this.exitCode = exitCode;
            this.failure = failure;
        }

        @Override
        public int run(String[] command, int timeoutSeconds) {
            commands.add(command.clone());
            if (failure != null) {
                throw failure;
            }
            assertEquals(WindowsPowerCommandExecutor.PROCESS_TIMEOUT_SECONDS, timeoutSeconds);
            return exitCode;
        }
    }

    @Test
    @DisplayName("Allowlist đúng 4 lệnh Windows, không shell tùy ý, restart/shutdown có 5s hủy")
    void commandMappingIsStrictAllowlist() {
        assertArrayEquals(new String[]{"rundll32.exe", "user32.dll,LockWorkStation"},
                WindowsPowerCommandExecutor.commandFor(ActionType.LOCK_SESSION));
        assertArrayEquals(new String[]{"shutdown.exe", "/l"},
                WindowsPowerCommandExecutor.commandFor(ActionType.LOGOFF_SESSION));

        String[] restart = WindowsPowerCommandExecutor.commandFor(ActionType.RESTART_DEVICE);
        assertEquals("shutdown.exe", restart[0]);
        assertTrue(List.of(restart).contains("/r"));
        assertTrue(List.of(restart).contains("/t"));
        assertTrue(List.of(restart).contains("5"), "5 giây để người dùng hủy");
        assertFalse(List.of(restart).contains("/f"), "Không ép đóng ứng dụng");

        String[] shutdown = WindowsPowerCommandExecutor.commandFor(ActionType.SHUTDOWN_DEVICE);
        assertTrue(List.of(shutdown).contains("/s"));
        assertTrue(List.of(shutdown).contains("5"));

        assertNull(WindowsPowerCommandExecutor.commandFor(ActionType.PAUSE_NETWORK));
        assertNull(WindowsPowerCommandExecutor.commandFor(ActionType.BLOCK_PROCESS));
        assertNull(WindowsPowerCommandExecutor.commandFor(null));
    }

    @Test
    @DisplayName("Windows + exit 0: chấp nhận, truyền đúng lệnh cho runner")
    void acceptedCommandReturnsSuccess() {
        FakeRunner runner = new FakeRunner(0);
        WindowsPowerCommandExecutor executor = new WindowsPowerCommandExecutor(true, runner);

        PowerCommandExecutor.Result result = executor.execute(ActionType.RESTART_DEVICE);

        assertTrue(result.accepted());
        assertEquals(1, runner.commands.size());
        assertEquals("shutdown.exe", runner.commands.get(0)[0]);
    }

    @Test
    @DisplayName("Exit code khác 0 (thiếu quyền) → rejected, không ném")
    void nonZeroExitIsRejected() {
        WindowsPowerCommandExecutor executor =
                new WindowsPowerCommandExecutor(true, new FakeRunner(5));

        PowerCommandExecutor.Result result = executor.execute(ActionType.LOGOFF_SESSION);

        assertFalse(result.accepted());
        assertTrue(result.message().contains("5"));
    }

    @Test
    @DisplayName("Không phải Windows → rejected, KHÔNG gọi runner")
    void nonWindowsIsRejected() {
        FakeRunner runner = new FakeRunner(0);
        WindowsPowerCommandExecutor executor = new WindowsPowerCommandExecutor(false, runner);

        PowerCommandExecutor.Result result = executor.execute(ActionType.SHUTDOWN_DEVICE);

        assertFalse(result.accepted());
        assertTrue(runner.commands.isEmpty(), "Không được chạy shutdown trên non-Windows");
    }

    @Test
    @DisplayName("Action ngoài allowlist → rejected, không gọi runner")
    void unsupportedActionIsRejected() {
        FakeRunner runner = new FakeRunner(0);
        WindowsPowerCommandExecutor executor = new WindowsPowerCommandExecutor(true, runner);

        assertFalse(executor.execute(ActionType.PAUSE_NETWORK).accepted());
        assertFalse(executor.execute(ActionType.BLOCK_PROCESS).accepted());
        assertFalse(executor.execute(null).accepted());
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    @DisplayName("IOException/SecurityException → rejected, không crash Client")
    void ioAndSecurityFailuresAreRejected() {
        WindowsPowerCommandExecutor ioExecutor = new WindowsPowerCommandExecutor(
                true, new FakeRunner(-1, new java.io.UncheckedIOException(
                        new IOException("Access denied"))));

        PowerCommandExecutor.Result result = ioExecutor.execute(ActionType.LOCK_SESSION);

        assertFalse(result.accepted(), "RuntimeException từ runner cũng không được làm sập Client");
        assertTrue(result.message().toLowerCase().contains("unexpected")
                || result.message().toLowerCase().contains("cannot"));
    }
}
