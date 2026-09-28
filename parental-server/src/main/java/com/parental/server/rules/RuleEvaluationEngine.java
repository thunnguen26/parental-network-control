package com.parental.server.rules;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.ActionType;
import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.CommandPayload;
import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.db.CommandLogDao;
import com.parental.server.db.MetricsDao;
import com.parental.server.db.RuleDao;
import com.parental.server.db.RuleDao.RuleRecord;
import com.parental.server.network.ClientHandler;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientSessionManager;

/**
 * Background Rule Engine (Task 4.2): quét định kỳ tất cả client ONLINE và tự
 * động phát lệnh {@code COMMAND} khi client vi phạm hạn mức do phụ huynh đặt
 * (khung giờ được phép / giới hạn thời gian trong ngày / giới hạn dung lượng
 * trong ngày).
 *
 * <p>Phân trách theo AGENTS.md: module này KHÔNG đụng socket/network engine —
 * chỉ đọc DAO (Task 4.1), đọc trạng thái phiên qua {@link ClientSessionManager}
 * và gửi COMMAND qua {@link ClientSession#send(BaseMessage)} (đã thread-safe
 * writeLock). Chạy trên scheduler riêng, thread {@code parental-server-rule-engine}.</p>
 *
 * <p><b>Chống spam lệnh</b> — trạng thái can thiệp là nguồn sự thật phía Server
 * lưu trong {@code ConcurrentHashMap} ({@code true} = đã gửi PAUSE chưa được
 * gỡ): PAUSE chỉ gửi khi vi phạm + chưa khóa; RESUME chỉ gửi khi đã khóa +
 * không còn vi phạm (kể cả khi phụ huynh nới lỏng/gỡ rule); ALERT pre-warning
 * chỉ gửi 1 lần cho mỗi chu kỳ vi phạm. Trạng thái được dọn khi client rời
 * mạng để phiên reconnect sau có trạng thái sạch.</p>
 */
public class RuleEvaluationEngine {

    /** Ngưỡng còn lại để gửi ALERT cảnh báo trước khi khóa — 5 phút. */
    public static final long PRE_WARNING_SECONDS = 5 * 60;

    /** Chuỗi reason cho 3 loại vi phạm + lệnh khôi phục. */
    public static final String REASON_TIME_SCHEDULE = "Outside allowed time schedule";
    public static final String REASON_USAGE_LIMIT = "Daily usage time limit exceeded";
    public static final String REASON_TRAFFIC_QUOTA = "Daily traffic quota exceeded";
    public static final String REASON_RESUME = "Network access restored: usage limits no longer exceeded";
    public static final String REASON_MANUAL_RESUME = "Network access restored manually by parent";

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationEngine.class);

    /** Đồng hồ cắm được cho unit test (mặc định giờ hệ thống). */
    public interface Clock {
        LocalTime now();
    }

    private final ClientSessionManager sessionManager;
    private final RuleDao ruleDao;
    private final MetricsDao metricsDao;
    private final CommandLogDao commandLogDao;
    private final long scanIntervalSeconds;
    private final Clock clock;

    /** Trạng thái can thiệp: clientId → TRUE = đang bị khóa (tự động hoặc thủ công). */
    private final ConcurrentHashMap<String, Boolean> clientEnforcedState = new ConcurrentHashMap<>();
    /**
     * Client bị khóa THỦ CÔNG bởi phụ huynh qua {@link #manualPause(String, String)}:
     * vòng quét tự động TUYỆT ĐỐI không gửi RESUME_NETWORK cho client trong set này
     * (kể cả khi hạn mức hợp lệ) — chỉ {@link #manualResume(String)} mới gỡ được.
     */
    private final Set<String> manualEnforcedClients = ConcurrentHashMap.newKeySet();
    /** Đã gửi ALERT pre-warning cho chu kỳ vi phạm hiện tại chưa. */
    private final ConcurrentHashMap<String, Boolean> preWarned = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    /** Chạy cùng DB thực (Task 4.1) — chu kỳ quét mặc định 30s. */
    public RuleEvaluationEngine(ClientSessionManager sessionManager,
                                RuleDao ruleDao, MetricsDao metricsDao, CommandLogDao commandLogDao) {
        this(sessionManager, ruleDao, metricsDao, commandLogDao, 30, LocalTime::now);
    }

    /** Chu kỳ quét cấu hình được (giây, tối thiểu 1) — test dùng 1s. */
    public RuleEvaluationEngine(ClientSessionManager sessionManager,
                                RuleDao ruleDao, MetricsDao metricsDao, CommandLogDao commandLogDao,
                                long scanIntervalSeconds) {
        this(sessionManager, ruleDao, metricsDao, commandLogDao, scanIntervalSeconds, LocalTime::now);
    }

    /** Đầy đủ: kèm {@link Clock} cắm được để unit test giả lập giờ trong ngày. */
    public RuleEvaluationEngine(ClientSessionManager sessionManager,
                                RuleDao ruleDao, MetricsDao metricsDao, CommandLogDao commandLogDao,
                                long scanIntervalSeconds, Clock clock) {
        this.sessionManager = sessionManager;
        this.ruleDao = ruleDao;
        this.metricsDao = metricsDao;
        this.commandLogDao = commandLogDao;
        this.scanIntervalSeconds = Math.max(1, scanIntervalSeconds);
        this.clock = clock != null ? clock : LocalTime::now;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Khởi động scheduler nền (idempotent). Lượt quét đầu sau 1 chu kỳ. */
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "parental-server-rule-engine");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::evaluateAll, scanIntervalSeconds, scanIntervalSeconds, TimeUnit.SECONDS);
        running = true;
        log.info("RuleEvaluationEngine started (scan interval {}s)", scanIntervalSeconds);
    }

    /** Graceful shutdown: ngừng nhận task mới, chờ tối đa 5s rồi interrupt. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Rule engine scheduler did not terminate in 5s — forced shutdownNow");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("RuleEvaluationEngine stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /** Client hiện có đang bị khóa bởi rule engine? (GUI Phase 5 sẽ đọc). */
    public boolean isEnforced(String clientId) {
        return Boolean.TRUE.equals(clientEnforcedState.get(clientId));
    }

    /**
     * Client có đang bị khóa THỦ CÔNG bởi phụ huynh không? Client trong trạng
     * thái này được vòng quét tự động bỏ qua hoàn toàn (không RESUME tự động).
     */
    public boolean isManuallyEnforced(String clientId) {
        return manualEnforcedClients.contains(clientId);
    }

    /** Danh sách client đang bị khóa (dành cho GUI/monitoring Phase 5). */
    public List<String> getEnforcedClients() {
        return List.copyOf(clientEnforcedState.keySet());
    }

    // ------------------------------------------------------------------
    // Core evaluation loop
    // ------------------------------------------------------------------

    /** Một lượt quét: duyệt mọi client ONLINE, dọn trạng thái client rời mạng. */
    void evaluateAll() {
        try {
            List<ClientSession> onlineSessions = sessionManager.getAllSessions();
            for (ClientSession session : onlineSessions) {
                evaluateClient(session);
            }
            cleanupStatesForOfflineClients(onlineSessions);
        } catch (RuntimeException e) {
            // Task nền không bao giờ được chết: nuốt + log đầy đủ stack trace
            log.error("Rule evaluation cycle failed: {}", e.getMessage(), e);
        }
    }

    void evaluateClient(ClientSession session) {
        final String clientId = session.getClientId();

        // Client phụ huynh khóa THỦ CÔNG → vòng quét tự động TUYỆT ĐỐI BỎ QUA:
        // không PAUSE spam, không RESUME tự động (chỉ manualResume mới gỡ được).
        if (manualEnforcedClients.contains(clientId)) {
            return;
        }

        RuleRecord rule = ruleDao.getRule(clientId);
        boolean hasActiveRule = hasActiveRule(rule);
        boolean enforced = isEnforced(clientId);

        if (!hasActiveRule) {
            if (enforced) {
                // Phụ huynh gỡ/bỏ cấu hình rule trong khi client còn bị khóa → gỡ khóa
                resumeNetwork(session);
            }
            return; // không có rule (hoặc unconfigured) → client tự do
        }

        String violationReason = detectViolationReason(clientId, session, rule);
        if (violationReason != null && !enforced) {
            enforcePause(session, violationReason);
        } else if (violationReason == null && enforced) {
            // Vi phạm hết (vào khung giờ mới / ngày mới reset / nới lỏng rule) → gỡ khóa
            resumeNetwork(session);
        } else if (violationReason == null) {
            sendPreWarningIfNeeded(session, clientId, rule);
        }
        // violationReason != null && enforced → đang khóa, không spam lệnh nữa
    }

    // ------------------------------------------------------------------
    // Violation detection
    // ------------------------------------------------------------------

    /**
     * Kiểm tra 3 điều kiện vi phạm theo thứ tự ưu tiên: khung giờ → thời gian
     * → dung lượng. Chỉ trả MỘT reason mỗi lượt quét (vi phạm mạnh nhất) để
     * reason rõ ràng, không spam nhiều lệnh. {@code null} = không vi phạm.
     */
    private String detectViolationReason(String clientId, ClientSession session, RuleRecord rule) {
        // 1) Khung giờ được phép (HH:mm) — theo giờ hệ thống qua Clock
        if (!isWithinAllowedSchedule(rule.allowedStartTime(), rule.allowedEndTime(), clock.now())) {
            return REASON_TIME_SCHEDULE;
        }
        // 2) Hạn mức thời gian dùng trong ngày (client tự báo qua metrics gần nhất)
        if (rule.dailyUsageLimitSeconds() > 0 && getUsedSeconds(session) > rule.dailyUsageLimitSeconds()) {
            return REASON_USAGE_LIMIT;
        }
        // 3) Hạn mức dung lượng trong ngày (tổng lưu lịch sử DB — Task 4.1)
        if (rule.dailyTrafficQuotaBytes() > 0) {
            long usedBytes = metricsDao.getTodayTrafficBytes(clientId);
            if (usedBytes > rule.dailyTrafficQuotaBytes()) {
                return REASON_TRAFFIC_QUOTA;
            }
        }
        return null;
    }

    /** Giây đã dùng trong ngày: tổng tích lũy từ metrics gần nhất client tự báo. */
    private long getUsedSeconds(ClientSession session) {
        MetricsPayload metrics = session.getLastMetrics();
        return metrics != null ? metrics.sessionDurationSeconds() : 0L;
    }

    // ------------------------------------------------------------------
    // Manual control API (Task 5.2 — Quick Actions Panel của Dashboard)
    // ------------------------------------------------------------------

    /**
     * Tạm dừng mạng THỦ CÔNG do phụ huynh yêu cầu (Quick Actions): gửi
     * PAUSE_NETWORK, ghi command_logs và đánh dấu trạng thái khóa — Rule
     * Engine tự động không đụng thêm client này cho tới khi
     * {@link #manualResume(String)} hoặc {@link #releaseEnforcement(String)}.
     *
     * @return commandId nếu gửi thành công, {@code null} nếu client offline/gửi hỏng
     */
    public String manualPause(String clientId, String reason) {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || session.isClosed()) {
            log.warn("Manual pause skipped: client [{}] not online", clientId);
            return null;
        }
        String safeReason = reason != null && !reason.isBlank() ? reason : "Manual pause by parent";
        String commandId = sendCommand(session, ActionType.PAUSE_NETWORK, safeReason, true);
        if (commandId != null) {
            // Đánh dấu khóa thủ công SAU khi gửi thành công — vòng quét tự động
            // từ đây bỏ qua client này cho tới khi manualResume/releaseEnforcement.
            manualEnforcedClients.add(clientId);
            log.info("[{}] Manual pause — auto rule engine will skip this client", clientId);
        }
        return commandId;
    }

    /**
     * Khôi phục mạng THỦ CÔNG (Quick Actions): gỡ trạng thái khóa (kể cả khóa
     * tự động của Rule Engine) + gửi RESUME_NETWORK + ghi command_logs.
     *
     * @return commandId nếu gửi thành công, {@code null} nếu client offline/gửi hỏng
     */
    public String manualResume(String clientId) {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || session.isClosed()) {
            log.warn("Manual resume skipped: client [{}] not online", clientId);
            return null;
        }
        String commandId = sendCommand(session, ActionType.RESUME_NETWORK, REASON_MANUAL_RESUME, false);
        if (commandId != null) {
            // Gỡ khóa thủ công → lượt quét kế tiếp tự đánh giá lại từ đầu (vi phạm
            // kéo dài sẽ bị PAUSE tự động lại).
            manualEnforcedClients.remove(clientId);
            clientEnforcedState.remove(clientId);
            preWarned.remove(clientId);
            log.info("[{}] Manual resume — enforcement released", clientId);
        }
        return commandId;
    }

    /**
     * Gửi ALERT thủ công xuống client (tin nhắn nhanh của phụ huynh).
     *
     * @return commandId nếu gửi thành công, {@code null} nếu client offline/gửi hỏng
     */
    public String sendAlert(String clientId, String message) {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || session.isClosed()) {
            log.warn("Alert skipped: client [{}] not online", clientId);
            return null;
        }
        String safeMessage = message != null && !message.isBlank()
                ? message : "Message from parent";
        return sendCommand(session, ActionType.ALERT, safeMessage, false);
    }

    /**
     * Chặn vĩnh viễn 1 tiến trình trên máy con (Mở rộng 10 — "Chặn app .exe
     * 1-click"): gửi COMMAND {@code BLOCK_PROCESS} với {@code reason} = tên
     * tiến trình. Client lưu vào {@code blocked_processes.json} + taskkill
     * tức thì + daemon quét 60s chống mở lại app.
     *
     * @param clientId    định danh client
     * @param processName tên tiến trình (vd {@code "somegame.exe"} hoặc
     *                    {@code "somegame"} — client tự chuẩn hóa)
     * @return commandId nếu gửi thành công; {@code null} nếu offline/gửi hỏng
     *         (không ghi DB, không có trạng thái cần dọn)
     */
    public String blockProcess(String clientId, String processName) {
        if (processName == null || processName.isBlank()) {
            log.warn("Block process skipped: empty process name for client [{}]", clientId);
            return null;
        }
        ClientSession session = sessionManager.get(clientId);
        if (session == null || session.isClosed()) {
            log.warn("Block process skipped: client [{}] not online", clientId);
            return null;
        }
        String safeName = processName.trim();
        return sendCommand(session, ActionType.BLOCK_PROCESS,
                safeName, false); // không mark enforced — lệnh chặn app, không khóa mạng
    }

    /**
     * Gỡ cờ cưỡng chế KHÔNG gửi lệnh — client được coi là tự do; lượt quét
     * Rule Engine kế tiếp sẽ tự đánh giá lại từ đầu (vi phạm kéo dài sẽ bị
     * PAUSE lại). Dùng khi muốn reset trạng thái khóa mà không can thiệp mạng.
     *
     * @return {@code true} nếu client trước đó đang bị khóa
     */
    public boolean releaseEnforcement(String clientId) {
        boolean wasManual = manualEnforcedClients.remove(clientId);
        boolean wasEnforced = clientEnforcedState.remove(clientId) != null | wasManual;
        preWarned.remove(clientId);
        if (wasEnforced) {
            log.info("[{}] Enforcement released (no command sent)", clientId);
        }
        return wasEnforced;
    }

    /**
     * Đồng bộ cấu hình chính sách hiện hành trong DB (Task 4.1) xuống client:
     * đóng gói {@link ConfigSyncPayload} (domain chặn + hạn mức + khung giờ)
     * và gửi {@code CONFIG_SYNC} qua phiên đang online. GUI gọi ngay sau khi
     * lưu rule mới để client áp dụng tức thì không đợi chu kỳ sync khác.
     *
     * @return {@code true} nếu client online và gửi thành công
     */
    public boolean syncConfigToClient(String clientId) {
        ClientSession session = sessionManager.get(clientId);
        if (session == null || session.isClosed()) {
            log.warn("Config sync skipped: client [{}] not online", clientId);
            return false;
        }
        RuleRecord rule = ruleDao.getRule(clientId);
        java.util.List<String> domains = ruleDao.getBlockedDomains(clientId);
        // Mở rộng 11: đồng bộ CẢ danh sách chặn tiến trình (REPLACE-ALL)
        java.util.List<String> blockedProcesses = ruleDao.getBlockedProcesses(clientId);
        ConfigSyncPayload payload = rule != null
                ? new ConfigSyncPayload(domains,
                        rule.dailyUsageLimitSeconds(), rule.dailyTrafficQuotaBytes(),
                        rule.allowedStartTime(), rule.allowedEndTime(), blockedProcesses)
                : new ConfigSyncPayload(domains, 0, 0, null, null, blockedProcesses);
        boolean sent = session.send(BaseMessage.of(
                MessageType.CONFIG_SYNC, ClientHandler.SERVER_ID, payload));
        if (sent) {
            log.info("[{}] CONFIG_SYNC pushed ({} domains, {} blocked processes, usage {}s, quota {}B, schedule {}-{})",
                    clientId, domains.size(), blockedProcesses.size(),
                    payload.dailyUsageLimitSeconds(), payload.dailyTrafficQuotaBytes(),
                    payload.allowedStartTime(), payload.allowedEndTime());
        }
        return sent;
    }

    // ------------------------------------------------------------------
    // Enforcement actions
    // ------------------------------------------------------------------

    /**
     * Luồng gửi COMMAND dùng chung (tự động + thủ công — Task 5.2): sinh
     * commandId UUID, gửi qua session; thành công → ghi command_logs (SENT)
     * và tùy chọn đánh dấu trạng thái khóa. Thất bại → trả {@code null},
     * KHÔNG ghi DB (chưa khóa; vòng quét tự động sẽ thử lại chu kỳ sau).
     */
    private String sendCommand(ClientSession session, ActionType action, String reason, boolean markEnforced) {
        String commandId = UUID.randomUUID().toString();
        CommandPayload payload = new CommandPayload(commandId, action, reason);
        if (session.send(BaseMessage.of(MessageType.COMMAND, ClientHandler.SERVER_ID, payload))) {
            commandLogDao.recordSent(commandId, session.getClientId(), action, reason);
            if (markEnforced) {
                clientEnforcedState.put(session.getClientId(), Boolean.TRUE);
                preWarned.remove(session.getClientId());
            }
            log.info("{} sent to [{}] (reason: {})", action, session.getClientId(), reason);
            return commandId;
        }
        log.warn("{} send failed to [{}] — will retry next cycle", action, session.getClientId());
        return null;
    }

    /** Gửi PAUSE_NETWORK + ghi SENT vào command_logs + đánh dấu trạng thái khóa. */
    private void enforcePause(ClientSession session, String reason) {
        sendCommand(session, ActionType.PAUSE_NETWORK, reason, true);
    }

    /** Gửi RESUME_NETWORK + ghi SENT + gỡ trạng thái khóa. */
    private void resumeNetwork(ClientSession session) {
        String commandId = sendCommand(session, ActionType.RESUME_NETWORK, REASON_RESUME, false);
        if (commandId != null) {
            clientEnforcedState.remove(session.getClientId());
            preWarned.remove(session.getClientId());
        }
    }

    /**
     * ALERT cảnh báo trước khi khóa: khi hạn mức thời gian còn lại ≤ 5 phút
     * (chưa vi phạm, chưa khóa) — gửi ĐÚNG 1 lần cho mỗi chu kỳ vi phạm.
     */
    private void sendPreWarningIfNeeded(ClientSession session, String clientId, RuleRecord rule) {
        if (rule.dailyUsageLimitSeconds() <= 0) {
            return; // pre-warning chỉ áp dụng cho hạn mức thời gian
        }
        long remainingSeconds = rule.dailyUsageLimitSeconds() - getUsedSeconds(session);
        if (remainingSeconds <= 0 || remainingSeconds > PRE_WARNING_SECONDS) {
            return;
        }
        if (Boolean.TRUE.equals(preWarned.get(clientId))) {
            return; // đã cảnh báo 1 lần rồi
        }
        long minutesLeft = Math.max(1, remainingSeconds / 60);
        String reason = "You have " + minutesLeft + " minutes of screen time left";
        String commandId = sendCommand(session, ActionType.ALERT, reason, false);
        if (commandId != null) {
            preWarned.put(clientId, Boolean.TRUE);
            log.info("Pre-warning ALERT sent to [{}] ({}s remaining)", clientId, remainingSeconds);
        }
    }

    /** Gỡ trạng thái can thiệp của client không còn ONLINE (reconnect có trạng thái sạch). */
    private void cleanupStatesForOfflineClients(List<ClientSession> onlineSessions) {
        Set<String> onlineIds = new HashSet<>();
        for (ClientSession session : onlineSessions) {
            onlineIds.add(session.getClientId());
        }
        clientEnforcedState.keySet().retainAll(onlineIds);
        manualEnforcedClients.retainAll(onlineIds);
        preWarned.keySet().retainAll(onlineIds);
    }

    // ------------------------------------------------------------------
    // Pure helpers (package-private — unit test trực tiếp)
    // ------------------------------------------------------------------

    /**
     * Client có rule hiệu lực không? Rule {@code null} hoặc tất cả hạn mức = 0
     * + khung giờ rỗng → không có rule hiệu lực (bỏ qua client).
     */
    static boolean hasActiveRule(RuleRecord rule) {
        if (rule == null) {
            return false;
        }
        boolean hasQuota = rule.dailyUsageLimitSeconds() > 0 || rule.dailyTrafficQuotaBytes() > 0;
        boolean hasSchedule = nonBlank(rule.allowedStartTime()) && nonBlank(rule.allowedEndTime());
        return hasQuota || hasSchedule;
    }

    /** Overload tiện dụng: kiểm tra khung giờ theo giờ hệ thống hiện tại. */
    static boolean isWithinAllowedSchedule(String startTime, String endTime) {
        return isWithinAllowedSchedule(startTime, endTime, LocalTime.now());
    }

    /**
     * Thời điểm {@code now} có nằm trong khung giờ cho phép không?
     * Format {@code HH:mm}; null/blank/format hỏng → không giới hạn giờ (true,
     * fail-open như không cấu hình). Hỗ trợ khung giờ thường ("08:00"→"22:00")
     * và khung giờ qua đêm ("22:00"→"06:00", {@code start > end}). Biên: điểm
     * đầu nằm trong, điểm cuối nằm ngoài; "00:00"→"00:00" coi như cả ngày.
     */
    static boolean isWithinAllowedSchedule(String startTime, String endTime, LocalTime now) {
        if (!nonBlank(startTime) || !nonBlank(endTime)) {
            return true; // không cấu hình khung giờ → luôn trong khung
        }
        LocalTime start = parseTime(startTime);
        LocalTime end = parseTime(endTime);
        if (start == null || end == null) {
            log.warn("Invalid allowed time format ('{}' - '{}'): schedule ignored (fail-open)",
                    startTime, endTime);
            return true;
        }
        if (start.equals(end)) {
            return true; // "00:00"-"00:00" → cả ngày (biên đặc biệt)
        }
        if (start.isBefore(end)) {
            // Khung giờ thông thường: 08:00 → 22:00
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Khung giờ qua đêm: 22:00 → 06:00 (vượt nửa đêm)
        return !now.isBefore(start) || now.isBefore(end);
    }

    /** Parse "HH:mm" (ISO LocalTime); trả null khi format hỏng. */
    private static LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean nonBlank(String text) {
        return text != null && !text.isBlank();
    }
}
