package com.parental.client.enforcement;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.client.metrics.NetworkMetricsCollector;
import com.parental.client.power.PowerCommandExecutor;
import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.payload.CommandPayload;

/**
 * Action Dispatcher (Task 3.3): điều phối {@link CommandPayload} từ Server
 * sang đúng bộ thực thi:
 * <ul>
 *   <li>{@code PAUSE_NETWORK} → {@link NetworkEnforcer#pauseNetwork()} +
 *       đồng bộ {@code networkStatus="PAUSED"} vào
 *       {@link NetworkMetricsCollector} (gói METRICS_REPORT chu kỳ sau gửi
 *       trạng thái PAUSED);</li>
 *   <li>{@code RESUME_NETWORK} → {@link NetworkEnforcer#resumeNetwork()} +
 *       {@code networkStatus="ACTIVE"};</li>
 *   <li>{@code ALERT} → {@link AlertNotifier#notify(reason)}.</li>
 * </ul>
 *
 * <p>@return commandId của lệnh đã tiếp nhận (để Engine gửi ACK về Server
 * theo PROTOCOL.md Mục 3.5) — {@code null} nếu lệnh không hợp lệ/không thực
 * thi được. Không bao giờ ném exception.</p>
 */
public class ActionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatcher.class);

    private final NetworkEnforcer enforcer;
    private final AlertNotifier alertNotifier;
    private final NetworkMetricsCollector metricsCollector; // nullable
    /** Quản lý chặn tiến trình (Mở rộng 10) — nullable: null = tắt chặn app .exe. */
    private final ProcessBlockerManager processBlockerManager;
    /**
     * Thực thi lệnh nguồn/phiên (Tính năng 3.4) — nullable: null = từ chối
     * các action power (không ACK) thay vì giả vờ thành công.
     */
    private final PowerCommandExecutor powerCommandExecutor;

    public ActionDispatcher(NetworkEnforcer enforcer, AlertNotifier alertNotifier,
                            NetworkMetricsCollector metricsCollector) {
        this(enforcer, alertNotifier, metricsCollector, null);
    }

    /**
     * Constructor đầy đủ (Mở rộng 10): kèm {@link ProcessBlockerManager} để
     * xử lý lệnh {@code BLOCK_PROCESS} (chặn app .exe 1-click). Truyền null
     * = hành vi cũ (chỉ log, không kill process).
     */
    public ActionDispatcher(NetworkEnforcer enforcer, AlertNotifier alertNotifier,
                            NetworkMetricsCollector metricsCollector,
                            ProcessBlockerManager processBlockerManager) {
        this(enforcer, alertNotifier, metricsCollector, processBlockerManager, null);
    }

    /**
     * Constructor đầy đủ (Tính năng 3.4): kèm {@link PowerCommandExecutor} cho
     * LOCK/LOGOFF/RESTART/SHUTDOWN. Truyền null = từ chối 4 action này.
     */
    public ActionDispatcher(NetworkEnforcer enforcer, AlertNotifier alertNotifier,
                            NetworkMetricsCollector metricsCollector,
                            ProcessBlockerManager processBlockerManager,
                            PowerCommandExecutor powerCommandExecutor) {
        this.enforcer = Objects.requireNonNull(enforcer, "enforcer");
        this.alertNotifier = Objects.requireNonNull(alertNotifier, "alertNotifier");
        this.metricsCollector = metricsCollector; // nullable — không sync status khi null
        this.processBlockerManager = processBlockerManager; // nullable
        this.powerCommandExecutor = powerCommandExecutor; // nullable — chưa bật
        // Dead-man switch: nếu enforcer tự RESUME (quá hạn an toàn), metrics
        // phải phản ánh ACTIVE thay vì tiếp tục báo PAUSED.
        this.enforcer.setAutoResumeListener(() -> {
            syncStatus(NetworkMetricsCollector.STATUS_ACTIVE);
            log.warn("Network auto-resumed by local safety timer — metrics synced ACTIVE");
        });
    }

    /**
     * Điều phối lệnh.
     *
     * @return commandId nếu lệnh được tiếp nhận; {@code null} nếu payload
     *         hỏng hoặc action thực thi thất bại
     */
    public String dispatch(CommandPayload command) {
        if (command == null || command.action() == null
                || command.commandId() == null || command.commandId().isBlank()) {
            log.warn("Invalid COMMAND payload — dropped: {}", command);
            return null;
        }
        try {
            return switch (command.action()) {
                case PAUSE_NETWORK -> handlePause(command);
                case RESUME_NETWORK -> handleResume(command);
                case ALERT -> handleAlert(command);
                case BLOCK_PROCESS -> handleBlockProcess(command);
                case LOCK_SESSION, LOGOFF_SESSION, RESTART_DEVICE, SHUTDOWN_DEVICE ->
                        handlePower(command);
            };
        } catch (RuntimeException e) {
            log.error("Dispatch failed for command {}: {}", command.commandId(), e.getMessage());
            return null;
        }
    }

    private String handlePause(CommandPayload command) {
        if (!enforcer.pauseNetwork()) {
            log.warn("PAUSE_NETWORK failed (command {}) — no ACK sent", command.commandId());
            return null;
        }
        syncStatus(NetworkMetricsCollector.STATUS_PAUSED);
        log.info("Network paused (command={}, reason={})", command.commandId(), command.reason());
        return command.commandId();
    }

    private String handleResume(CommandPayload command) {
        if (!enforcer.resumeNetwork()) {
            log.warn("RESUME_NETWORK failed (command {}) — no ACK sent", command.commandId());
            return null;
        }
        syncStatus(NetworkMetricsCollector.STATUS_ACTIVE);
        log.info("Network resumed (command={})", command.commandId());
        return command.commandId();
    }

    private String handleAlert(CommandPayload command) {
        boolean shown = alertNotifier.notify(command.reason());
        log.info("ALERT processed (command={}, shown={}, reason={})",
                command.commandId(), shown, command.reason());
        // ALERT luôn coi như tiếp nhận thành công
        return command.commandId();
    }

    /**
     * BLOCK_PROCESS (Mở rộng 10 — chặn app .exe 1-click): {@code reason} mang
     * tên tiến trình cần chặn. Lưu danh sách + taskkill tức thì qua
     * {@link ProcessBlockerManager} — ACK khi đã tiếp nhận (kill là best-effort;
     * manager null → vẫn ACK để không treo Server, chỉ log).
     */
    private String handleBlockProcess(CommandPayload command) {
        String processName = command.reason();
        if (processName == null || processName.isBlank()) {
            log.warn("BLOCK_PROCESS without process name (command={}) — dropped", command.commandId());
            return null;
        }
        if (processBlockerManager != null) {
            ProcessBlockerManager.BlockResult result =
                    processBlockerManager.blockProcessDetailed(processName);
            if (!result.accepted()) {
                log.warn("BLOCK_PROCESS '{}' NOT accepted (persisted={}, kill={}, command={}) — no ACK",
                        processName, result.persisted(), result.killResult(), command.commandId());
                return null;
            }
            log.info("Process '{}' blocked permanently (command={}, kill={})",
                    processName, command.commandId(), result.killResult());
        } else {
            log.info("BLOCK_PROCESS received but process blocker disabled (command={}) — ACK anyway",
                    command.commandId());
        }
        return command.commandId();
    }

    /**
     * Lệnh nguồn/phiên (Tính năng 3.4): chuyển thẳng cho executor allowlist.
     * Executor null hoặc từ chối (OS khác/thiếu quyền) → KHÔNG ACK để Server
     * biết thao tác chưa được thực thi. Không đụng trạng thái mạng.
     */
    private String handlePower(CommandPayload command) {
        if (powerCommandExecutor == null) {
            log.warn("Power command {} rejected: executor is disabled on this client",
                    command.action());
            return null;
        }
        PowerCommandExecutor.Result result = powerCommandExecutor.execute(command.action());
        if (result == null || !result.accepted()) {
            log.warn("Power command {} failed: {}", command.action(),
                    result == null ? "no result" : result.message());
            return null;
        }
        log.info("Power command {} accepted (commandId={})",
                command.action(), command.commandId());
        return command.commandId();
    }

    /** Đồng bộ trạng thái vào metrics collector để METRICS_REPORT chu kỳ sau phản ánh đúng. */
    private void syncStatus(String status) {
        if (metricsCollector != null) {
            try {
                metricsCollector.setNetworkStatus(status);
            } catch (RuntimeException e) {
                log.warn("Failed to sync network status into metrics collector: {}", e.getMessage());
            }
        }
    }

    /**
     * Dọn dẹp khi tắt ứng dụng (gọi từ shutdown hook của Engine): luôn khôi
     * phục mạng qua {@link NetworkEnforcer#cleanup()} — không để lại máy con
     * trong trạng thái mất mạng vĩnh viễn. Không ném exception.
     */
    public void cleanup() {
        try {
            enforcer.cleanup();
        } catch (RuntimeException e) {
            log.warn("Enforcer cleanup failed: {}", e.getMessage());
        }
    }
}
