package com.parental.client.activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ActivityReportPayload;

/**
 * Triển khai {@link ActivityCollector} trên <b>Linux</b> — môi trường desktop
 * không có Windows UI Automation; dùng 2 lớp ưu tiên:
 *
 * <ol>
 *   <li><b>xdotool (X11)</b> — bắt CHÍNH XÁC cửa sổ đang focus:
 *       {@code xdotool getactivewindow getwindowpid} → PID +
 *       {@code xdotool getactivewindow getwindowname} → tiêu đề; tên tiến
 *       trình tra từ PID qua {@code ps -p PID -o comm=}. xdotool thiếu/cài
 *       Wayland (lệnh lỗi) → bỏ qua lớp này.</li>
 *   <li><b>Fallback an toàn</b> — {@code ps -eo comm,pid --sort=-%cpu}
 *       (yêu cầu nhiệm vụ): lấy các tiến trình tiêu thụ CPU cao nhất, ưu
 *       tiên tiến trình đồ họa phổ biến (firefox/chrome/vlc...) theo
 *       {@link #GRAPHICAL_APP_HINTS}; không lọc được gì → payload rỗng.</li>
 * </ol>
 *
 * <p><b>Contract</b> (AGENTS.md — Defensive OS Interfacing): KHÔNG BAO GIỜ
 * ném exception, KHÔNG BAO GIỜ trả null — mọi lỗi (xdotool không cài, Wayland
 * headless, ps lỗi...) → payload rỗng an toàn. Chuỗi lỗi shell TUYỆT ĐỐI
 * không thành payload (stdout/stderr tách riêng, exitCode ≠ 0 → bỏ).</p>
 */
public class LinuxActivityCollector implements ActivityCollector {

    private static final Logger log = LoggerFactory.getLogger(LinuxActivityCollector.class);

    /** Timeout mỗi lệnh shell (giây) — treo → destroy + payload rỗng. */
    static final int COMMAND_TIMEOUT_SECONDS = 2;

    /** Độ dài tối đa stdout đọc về (chống pipe đầy block vô hạn). */
    static final int MAX_OUTPUT_CHARS = 8_000;

    /**
     * Tiến trình đồ họa phổ biến trên Linux (lowercase) — fallback ưu tiên
     * chọn active khi CPU sort không đủ ưu tiên; vẫn cho phép app khác
     * (không whitelist cứng — chỉ xếp hạng).
     */
    static final List<String> GRAPHICAL_APP_HINTS = List.of(
            "firefox", "chrome", "chromium", "opera", "brave", "vivaldi", "edge",
            "vlc", "code", "idea", "libreoffice", "soffice", "gimp", "krita",
            "thunderbird", "discord", "slack", "telegram", "zoom", "steam", "spotify");

    /** Nguồn hệ thống tách được để test (null = dùng lệnh thật). */
    private final CommandRunner commandRunner;

    /** Chạy lệnh shell thật. */
    public LinuxActivityCollector() {
        this(null);
    }

    /** Cho test inject runner giả lập (không đụng hệ Linux thật). */
    LinuxActivityCollector(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    /** Kết quả 1 lệnh shell: exitCode + stdout + stderr (tách riêng). */
    record CommandResult(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0 && stdout != null && !stdout.isBlank();
        }
    }

    /** Seam chạy lệnh — test stub được (default: ProcessBuilder thật). */
    interface CommandRunner {
        CommandResult run(String... command);
    }

    @Override
    public ActivityReportPayload collect() {
        try {
            return collectInner();
        } catch (RuntimeException e) {
            // Defensive: bất kỳ lỗi nào → payload rỗng, không chết scheduler.
            log.warn("Linux activity collect failed, returning empty payload: {}", e.getMessage());
            return emptyPayload();
        }
    }

    private ActivityReportPayload collectInner() {
        // Lớp 1 — xdotool: cửa sổ focus thật (X11).
        UserApp active = collectActiveWindowViaXdotool();
        if (active == null) {
            // Lớp 2 — fallback ps: tiến trình CPU cao nhất (ưu tiên đồ họa).
            active = collectFallbackFromPs();
        }

        // Đóng gói ACTIVITY_REPORT (Mục 3.9) — payload rỗng khi không thu được gì.
        if (active == null) {
            return emptyPayload();
        }

        List<String> topProcesses = collectTopProcesses();
        // Đảm bảo app active nằm trong topProcesses (khớp hành vi Windows).
        if (!topProcesses.contains(active.processName())) {
            topProcesses.add(0, active.processName());
        }
        if (topProcesses.size() > ActivityReportPayload.MAX_TOP_PROCESSES) {
            topProcesses = topProcesses.subList(0, ActivityReportPayload.MAX_TOP_PROCESSES);
        }

        return new ActivityReportPayload(
                active.title(),
                active.processName(),
                active.url(),
                List.copyOf(topProcesses),
                System.currentTimeMillis());
    }

    /**
     * Lớp 1: xdotool getactivewindow → PID + windowname; tên tiến trình tra
     * {@code ps -p PID -o comm=}. Bất kỳ bước lỗi → null (đi fallback).
     */
    private UserApp collectActiveWindowViaXdotool() {
        CommandResult pidResult = runCommand("xdotool", "getactivewindow", "getwindowpid");
        CommandResult nameResult = runCommand("xdotool", "getactivewindow", "getwindowname");
        // Cả 2 lệnh phải thành công (xdotool chưa cài → exitCode != 0; Wayland → lỗi)
        if (!pidResult.ok() || !nameResult.ok()) {
            return null;
        }
        String title = nameResult.stdout().trim();
        String pidRaw = pidResult.stdout().trim();
        if (title.isBlank() || pidRaw.isBlank()) {
            return null;
        }
        String processName = resolveProcessNameByPid(pidRaw);
        if (processName == null || processName.isBlank()) {
            // Có title nhưng không tra được PID → dùng chính title làm nhãn.
            return new UserApp("unknown", title, "");
        }
        return new UserApp(processName, title, "");
    }

    /** Tra tên tiến trình từ PID: {@code ps -p PID -o comm=} — lỗi → null. */
    private String resolveProcessNameByPid(String pid) {
        CommandResult result = runCommand("ps", "-p", pid, "-o", "comm=");
        if (!result.ok()) {
            return null;
        }
        String comm = result.stdout().trim().toLowerCase(Locale.ROOT);
        return comm.isBlank() ? null : comm;
    }

    /**
     * Lớp 2 (fallback): {@code ps -eo comm,pid --sort=-%cpu} — sort CPU giảm
     * dần; ưu tiên tiến trình đồ họa ({@link #GRAPHICAL_APP_HINTS}), loại
     * tiến trình nền hệ thống ({@code ps} itself, shell...). Chọn app active
     * = đồ họa có CPU cao nhất, hoặc app bất kỳ CPU cao nhất khi không có
     * đồ họa nào.
     */
    private UserApp collectFallbackFromPs() {
        CommandResult result = runCommand("ps", "-eo", "comm,pid", "--sort=-%cpu");
        if (!result.ok()) {
            return null;
        }
        List<String> ordered = parsePsCommLines(result.stdout());
        if (ordered.isEmpty()) {
            return null;
        }
        // Ưu tiên tiến trình đồ họa phổ biến (chrome/firefox/vlc...) theo CPU desc.
        for (String comm : ordered) {
            if (isGraphicalApp(comm)) {
                return new UserApp(comm, comm, ""); // ps không cho title cửa sổ
            }
        }
        // Không có app đồ họa → tiến trình CPU cao nhất (không phải hệ thống).
        String first = ordered.get(0);
        return new UserApp(first, first, "");
    }

    /**
     * Danh sách topProcesses cho payload: comm của tiến trình (CPU desc),
     * lọc hệ thống — tối đa {@link ActivityReportPayload#MAX_TOP_PROCESSES}.
     */
    private List<String> collectTopProcesses() {
        CommandResult result = runCommand("ps", "-eo", "comm,pid", "--sort=-%cpu");
        if (!result.ok()) {
            return List.of();
        }
        return parsePsCommLines(result.stdout()).stream()
                .limit(ActivityReportPayload.MAX_TOP_PROCESSES)
                .toList();
    }

    /** Parse stdout ps: bỏ header COMMAND, lowercase comm, loại tiến trình nền. */
    static List<String> parsePsCommLines(String output) {
        List<String> result = new ArrayList<>();
        if (output == null) {
            return result;
        }
        for (String line : output.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String comm = line.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (comm.isBlank() || comm.equals("command") || isSystemProcess(comm)) {
                continue; // 'command' = dòng header của ps — bỏ qua
            }
            if (!result.contains(comm)) {
                result.add(comm); // khử trùng lặp giữ thứ tự CPU desc
            }
        }
        return result;
    }

    /** Tiến trình nền hệ thống — không phải hoạt động người dùng. */
    static boolean isSystemProcess(String comm) {
        return switch (comm) {
            case "ps", "grep", "bash", "sh", "zsh", "systemd", "systemd-journal",
                 "dbus-daemon", "init", "snapd", "udisksd", "gnome-shell",
                 "gnome-session", "Xorg", "xwayland", "pipewire", "pulseaudio",
                 "dbus-launch", "kwin_wayland", "kwin_x11", "plasmashell",
                 "irq", "kworker", "ksoftirqd", "migration", "rcu_", "cpuhp" -> true;
            default -> comm.startsWith("kworker") || comm.startsWith("ksoftirqd")
                    || comm.startsWith("rcu_") || comm.startsWith("irq/");
        };
    }

    /** Có phải tiến trình đồ họa ứng dụng (theo {@link #GRAPHICAL_APP_HINTS})? */
    static boolean isGraphicalApp(String comm) {
        if (comm == null || comm.isBlank()) {
            return false;
        }
        String lower = comm.toLowerCase(Locale.ROOT);
        for (String hint : GRAPHICAL_APP_HINTS) {
            if (lower.startsWith(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Chạy lệnh qua seam (default ProcessBuilder + timeout + pump stdout/stderr). */
    private CommandResult runCommand(String... command) {
        if (commandRunner != null) {
            return commandRunner.run(command);
        }
        return runProcess(command);
    }

    /** Thực thi thật: timeout 2s, stdout/stderr tách riêng, destroyForcibly khi treo. */
    private static CommandResult runProcess(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread pumpOut = pump(process, stdout, true);
            Thread pumpErr = pump(process, stderr, false);
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.debug("Command '{}' timed out after {}s", String.join(" ", command),
                        COMMAND_TIMEOUT_SECONDS);
                return new CommandResult(-1, "", "timeout");
            }
            pumpOut.join(500);
            pumpErr.join(500);
            return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (IOException e) {
            // Lệnh không tồn tại (xdotool chưa cài...) — lớp trên đi fallback.
            log.debug("Command '{}' failed to start: {}", String.join(" ", command), e.getMessage());
            return new CommandResult(-1, "", e.getMessage() == null ? "io error" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "interrupted");
        } finally {
            if (process != null) {
                process.destroyForcibly(); // defensive — sạch nguồn lực
            }
        }
    }

    /** Pump 1 stream vào StringBuilder (daemon thread — không block lẫn nhau). */
    private static Thread pump(Process process, StringBuilder sink, boolean stdOut) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    stdOut ? process.getInputStream() : process.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) >= 0 && sink.length() <= MAX_OUTPUT_CHARS) {
                    sink.append(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // stream đóng khi destroy — chấp nhận
            }
        }, "parental-client-linux-activity-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Payload rỗng chuẩn (không xác định được hoạt động). */
    private static ActivityReportPayload emptyPayload() {
        return new ActivityReportPayload(UNKNOWN, UNKNOWN, List.of(), System.currentTimeMillis());
    }

    /** 1 ứng dụng thu được: tên tiến trình + tiêu đề + URL/domain (rỗng). */
    record UserApp(String processName, String title, String url) {
    }
}
