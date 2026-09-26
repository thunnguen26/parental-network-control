package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.server.db.ClientDao;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.MetricsDao;
import com.parental.server.db.RuleDao;
import com.parental.server.db.RuleDao.RuleRecord;
import com.parental.server.network.ClientConnectionListener;
import com.parental.server.network.ClientSession;
import com.parental.server.network.ClientSessionManager;
import com.parental.server.network.DisconnectReason;
import com.parental.server.network.TcpServerEngine;
import com.parental.server.rules.RuleEvaluationEngine;

/**
 * Cửa sổ chính Dashboard giám sát (Task 5.1) — bố cục 3 vùng:
 * <ul>
 *   <li><b>Left sidebar</b>: danh sách thiết bị (icon ● Online/Offline,
 *       tên máy, IP, trạng thái ACTIVE/PAUSED) từ {@link ClientDao} —
 *       cập nhật tức thời qua {@code ClientConnectionListener};</li>
 *   <li><b>Center</b>: chi tiết máy đang chọn — thông tin thiết bị, thẻ số
 *       liệu tức thời (download/upload speed, tổng dung lượng hôm nay) từ
 *       {@code ClientSession.getLastMetrics()} + {@code MetricsDao}, thanh
 *       đo tiến độ dung lượng/thời gian so với hạn mức rule;</li>
 *   <li><b>Bottom</b>: Event Log console ({@code JTextArea}) — luồng sự kiện
 *       kết nối/ngắt/lệnh COMMAND theo thời gian thực.</li>
 * </ul>
 *
 * <p><b>Swing Threading (Single-Thread Rule)</b>: mọi cập nhật state UI chạy
 * trong {@code SwingUtilities.invokeLater(...)}. Dữ liệu đọc từ engine/DAO
 * (DAO blocking nhẹ, SQLite local — chấp nhận được trên UI refresher 1s).
 * UI Refresher dùng {@code javax.swing.Timer} (bắn trên EDT nên an toàn
 * mặc định, không cần invokeLater bên trong tick).</p>
 */
public class ServerDashboardFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ServerDashboardFrame.class);

    /** Chu kỳ làm mới số liệu (ms) — spec Task 5.1: 1s. */
    static final int REFRESH_INTERVAL_MS = 1_000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TcpServerEngine serverEngine;
    private final ClientDao clientDao;
    private final MetricsDao metricsDao;
    private final RuleDao ruleDao;
    private final ClientSessionManager sessionManager;
    private final RuleEvaluationEngine ruleEngine;

    // --- Left sidebar ---
    private DefaultListModel<ClientRecord> deviceListModel;
    private JList<ClientRecord> deviceList;

    // --- Center detail ---
    private JTextField deviceNameField;
    private JTextField macField;
    private JTextField osField;
    private JTextField sessionDurationField;
    private JTextField downloadSpeedField;
    private JTextField uploadSpeedField;
    private JTextField todayTrafficField;
    private JProgressBar quotaBar;
    private JProgressBar usageBar;
    private JLabel quotaLabel;
    private JLabel usageLabel;

    // --- Bottom event log ---
    private JTextArea eventLogArea;

    // --- Quick actions (Task 5.2) ---
    private JButton pauseButton;
    private JButton resumeButton;
    private JTextField alertMessageField;
    private JButton sendAlertButton;

    private Timer uiRefresher;

    /** Dựng dashboard gắn với engine đang chạy (các thành phần không null). */
    public ServerDashboardFrame(TcpServerEngine serverEngine, ClientDao clientDao,
                                MetricsDao metricsDao, RuleDao ruleDao,
                                ClientSessionManager sessionManager,
                                RuleEvaluationEngine ruleEngine) {
        super("Parental Network Control - Server Dashboard");
        // Fail-fast các dependency bắt buộc — một DAO null (inject sai từ
        // ServerApp) phải bùng nổ NGAY tại constructor, không phải NPE muộn
        // trong vòng refresh định kỳ.
        this.serverEngine = Objects.requireNonNull(serverEngine, "serverEngine must not be null");
        this.clientDao = Objects.requireNonNull(clientDao, "clientDao must not be null");
        this.metricsDao = Objects.requireNonNull(metricsDao, "metricsDao must not be null");
        this.ruleDao = Objects.requireNonNull(ruleDao, "ruleDao must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        // Rule Engine tùy chọn (null = chạy không DB — các quick action tự tắt).
        this.ruleEngine = ruleEngine;
        initUi();
        registerEngineListeners();
        startUiRefresher();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void initUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // đóng qua windowClosing handler
        setSize(1000, 650);
        setMinimumSize(new Dimension(860, 560));
        setLocationRelativeTo(null); // canh giữa màn hình
        setLayout(new BorderLayout());

        add(buildSidebar(), BorderLayout.WEST);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        // Load danh sách thiết bị lần đầu + cập nhật trạng thái nút ngay
        reloadDeviceList();
        updateQuickActionButtons();
    }

    private JPanel buildSidebar() {
        deviceListModel = new DefaultListModel<>();
        deviceList = new JList<>(deviceListModel);
        deviceList.setCellRenderer(new DeviceCellRenderer());
        deviceList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshCenterPanel();
            }
        });

        JScrollPane scrollPane = new JScrollPane(deviceList);
        scrollPane.setPreferredSize(new Dimension(300, 0));
        scrollPane.setBorder(BorderFactory.createTitledBorder("Thiết bị đang quản lý"));

        // Nút mở dialog cấu hình chính sách (Task 5.2)
        JButton policyButton = new JButton("⚙ Cấu hình chính sách...");
        policyButton.addActionListener(event -> openPolicyDialog());

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(policyButton, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Quick Actions Panel (Task 5.2): Tạm dừng mạng / Khôi phục mạng / Gửi
     * cảnh báo — gọi thẳng manual API của {@link RuleEvaluationEngine}.
     */
    private JPanel buildQuickActionsPanel() {
        pauseButton = new JButton("⏸ Tạm dừng mạng");
        resumeButton = new JButton("▶ Khôi phục mạng");
        alertMessageField = new JTextField(18);
        sendAlertButton = new JButton("Gửi cảnh báo");

        pauseButton.addActionListener(event -> runQuickAction(() ->
                ruleEngine.manualPause(selectedClientId(), "Tạm dừng thủ công từ phụ huynh"),
                "đã gửi lệnh TẠM DỪNG mạng"));
        resumeButton.addActionListener(event -> runQuickAction(() ->
                ruleEngine.manualResume(selectedClientId()),
                "đã gửi lệnh KHÔI PHỤC mạng"));
        sendAlertButton.addActionListener(event -> {
            String message = alertMessageField.getText().trim();
            if (message.isEmpty()) {
                appendEventLog("⚠ Tin nhắn cảnh báo rỗng — bỏ qua");
                return;
            }
            runQuickAction(() -> ruleEngine.sendAlert(selectedClientId(), message),
                    "đã gửi cảnh báo: \"" + message + "\"");
            alertMessageField.setText("");
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Điều khiển nhanh (thủ công)"));
        panel.add(pauseButton);
        panel.add(resumeButton);
        panel.add(alertMessageField);
        panel.add(sendAlertButton);
        return panel;
    }

    /** Chạy quick action trên nền SwingWorker tránh block EDT, log kết quả. */
    private void runQuickAction(java.util.function.Supplier<String> action, String successMessage) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("⚠ Chưa chọn thiết bị nào");
            return;
        }
        String clientId = selected.clientId();
        String deviceName = selected.deviceName();
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                try {
                    String commandId = get();
                    if (commandId != null) {
                        appendEventLog("Client '" + deviceName + "' (" + commandId.substring(0, 8) + "...) "
                                + successMessage);
                    } else {
                        appendEventLog("⚠ Thao tác thất bại với client '" + deviceName
                                + "' (offline hoặc gửi lỗi)");
                    }
                } catch (Exception e) {
                    appendEventLog("⚠ Lỗi thao tác: " + e.getMessage());
                }
                updateQuickActionButtons();
            }
        }.execute();
    }

    /** ClientId đang chọn trong sidebar (null nếu không chọn gì). */
    private String selectedClientId() {
        ClientRecord selected = deviceList.getSelectedValue();
        return selected != null ? selected.clientId() : null;
    }

    /** Enable/disable nút pause/resume theo trạng thái online + enforced (EDT). */
    private void updateQuickActionButtons() {
        ClientRecord selected = deviceList.getSelectedValue();
        boolean online = selected != null && "ONLINE".equals(selected.status())
                && ruleEngine != null; // không có rule engine → vô hiệu hóa quick action
        boolean enforced = selected != null && ruleEngine != null && ruleEngine.isEnforced(selected.clientId());
        pauseButton.setEnabled(online && !enforced);
        resumeButton.setEnabled(online && enforced);
        sendAlertButton.setEnabled(online);
        alertMessageField.setEnabled(online);
    }

    private JPanel buildCenterPanel() {
        deviceNameField = new JTextField();
        macField = new JTextField();
        osField = new JTextField();
        sessionDurationField = new JTextField();
        deviceNameField.setEditable(false);
        macField.setEditable(false);
        osField.setEditable(false);
        sessionDurationField.setEditable(false);

        JPanel infoPanel = new JPanel(new GridBagLayout());
        infoPanel.setBorder(BorderFactory.createTitledBorder("Thông tin thiết bị"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        infoPanel.add(new JLabel("Tên máy:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        infoPanel.add(deviceNameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        infoPanel.add(new JLabel("MAC:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        infoPanel.add(macField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        infoPanel.add(new JLabel("Hệ điều hành:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        infoPanel.add(osField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        infoPanel.add(new JLabel("Thời gian phiên:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        infoPanel.add(sessionDurationField, gbc);

        // --- Thẻ số liệu tức thời (metric cards) ---
        JPanel cardsPanel = new JPanel(new GridLayout(1, 3, 10, 10));
        cardsPanel.setBorder(BorderFactory.createTitledBorder("Giám sát tức thời"));
        cardsPanel.add(buildMetricCard("Tốc độ tải về", downloadSpeedField = new JTextField()));
        cardsPanel.add(buildMetricCard("Tốc độ tải lên", uploadSpeedField = new JTextField()));
        cardsPanel.add(buildMetricCard("Tổng hôm nay", todayTrafficField = new JTextField()));
        for (Component component : cardsPanel.getComponents()) {
            if (component instanceof JPanel card) {
                for (Component child : card.getComponents()) {
                    if (child instanceof JTextField field) {
                        field.setEditable(false);
                        field.setHorizontalAlignment(JTextField.CENTER);
                        field.setFont(field.getFont().deriveFont(Font.BOLD, 15f));
                    }
                }
            }
        }

        // --- Thanh đo hạn mức (rule) ---
        quotaBar = new JProgressBar(0, 100);
        usageBar = new JProgressBar(0, 100);
        quotaBar.setStringPainted(true);
        usageBar.setStringPainted(true);
        quotaLabel = new JLabel("Dung lượng hôm nay / hạn mức:");
        usageLabel = new JLabel("Thời gian phiên / giới hạn:");

        JPanel rulesPanel = new JPanel(new GridBagLayout());
        rulesPanel.setBorder(BorderFactory.createTitledBorder("Hạn mức (Rule)"));
        GridBagConstraints rulesGbc = new GridBagConstraints();
        rulesGbc.insets = new Insets(4, 6, 4, 6);
        rulesGbc.fill = GridBagConstraints.HORIZONTAL;
        rulesGbc.weightx = 1.0;

        rulesGbc.gridx = 0;
        rulesGbc.gridy = 0;
        rulesPanel.add(quotaLabel, rulesGbc);
        rulesGbc.gridy = 1;
        rulesPanel.add(quotaBar, rulesGbc);
        rulesGbc.gridy = 2;
        rulesPanel.add(usageLabel, rulesGbc);
        rulesGbc.gridy = 3;
        rulesPanel.add(usageBar, rulesGbc);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        center.add(infoPanel, BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout());
        middle.add(cardsPanel, BorderLayout.NORTH);
        middle.add(rulesPanel, BorderLayout.CENTER);
        center.add(middle, BorderLayout.CENTER);
        center.add(buildQuickActionsPanel(), BorderLayout.SOUTH);
        return center;
    }

    /** Thẻ metric: label trên, giá trị lớn bên dưới. */
    private JPanel buildMetricCard(String title, JTextField valueField) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 190, 190)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 12f));
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueField, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildBottomPanel() {
        eventLogArea = new JTextArea(8, 40);
        eventLogArea.setEditable(false);
        eventLogArea.setLineWrap(true);
        eventLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(eventLogArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Event Log"));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /** Renderer dòng thiết bị: icon ● Online/Offline + tên máy + IP + trạng thái. */
    private static final class DeviceCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ClientRecord record && component instanceof JLabel label) {
                boolean online = "ONLINE".equals(record.status());
                String icon = online ? "●" : "○";
                String statusText = online ? "Online" : "Offline";
                label.setText(String.format("%s %s (%s) — %s", icon, record.deviceName(),
                        record.ipAddress(), statusText));
                label.setForeground(online ? new Color(0, 128, 0) : Color.GRAY);
            }
            return component;
        }
    }

    // ------------------------------------------------------------------
    // Engine bridge (event listeners + UI refresher)
    // ------------------------------------------------------------------

    private void registerEngineListeners() {
        // Kết nối/ngắt → log sự kiện + reload danh sách thiết bị (invokeLater đúng EDT)
        sessionManager.addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onClientConnected(ClientSession session) {
                SwingUtilities.invokeLater(() -> {
                    appendEventLog("Client '" + session.getClientId() + "' đã kết nối");
                    reloadDeviceList();
                });
            }

            @Override
            public void onClientDisconnected(String clientId, DisconnectReason reason) {
                SwingUtilities.invokeLater(() -> {
                    appendEventLog("Client '" + clientId + "' đã ngắt kết nối (" + reason + ")");
                    reloadDeviceList();
                });
            }
        });
    }

    /** UI Refresher 1s (javax.swing.Timer — tick trên EDT, an toàn mặc định). */
    private void startUiRefresher() {
        uiRefresher = new Timer(REFRESH_INTERVAL_MS, event -> refreshCenterPanel());
        uiRefresher.start();
    }

    /** Dừng UI refresher (gọi khi đóng dashboard trước khi stop engine). */
    public void stopRefresher() {
        if (uiRefresher != null) {
            uiRefresher.stop();
        }
    }

    // ------------------------------------------------------------------
    // Data → UI update (luôn chạy trên EDT)
    // ------------------------------------------------------------------

    private void reloadDeviceList() {
        if (clientDao == null) {
            return; // Defensive: DAO chưa sẵn sàng → không touch DB, giữ UI rỗng
        }
        // Giữ selection hiện tại qua lần refresh (theo clientId)
        String previousSelection = selectedClientId();
        deviceListModel.clear();
        for (ClientRecord record : clientDao.getAllClients()) {
            deviceListModel.addElement(record);
        }
        if (previousSelection != null) {
            for (int i = 0; i < deviceListModel.size(); i++) {
                if (previousSelection.equals(deviceListModel.get(i).clientId())) {
                    deviceList.setSelectedIndex(i);
                    deviceList.ensureIndexIsVisible(i);
                    break;
                }
            }
        }
        updateQuickActionButtons();
    }

    private void refreshCenterPanel() {
        // Defensive: vòng refresh 1s không được crash khi DAO/Engine chưa sẵn sàng
        if (clientDao == null || metricsDao == null || ruleDao == null || sessionManager == null) {
            return;
        }
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            // Không chọn gì: lấy client đầu tiên nếu có
            if (!deviceListModel.isEmpty()) {
                if (deviceList.getSelectedIndex() < 0) {
                    deviceList.setSelectedIndex(0);
                }
                selected = deviceList.getSelectedValue();
            }
        }
        if (selected == null) {
            clearCenterPanel();
            return;
        }
        String clientId = selected.clientId();

        // Thông tin thiết bị
        deviceNameField.setText(orDash(selected.deviceName()));
        macField.setText(orDash(selected.macAddress()));
        osField.setText(orDash(selected.osVersion()));

        // Metrics tức thời từ phiên đang online
        ClientSession session = sessionManager.get(clientId);
        MetricsPayload metrics = session != null ? session.getLastMetrics() : null;
        if (metrics != null) {
            downloadSpeedField.setText(GuiFormat.formatSpeedBps(metrics.downloadSpeedBps()));
            uploadSpeedField.setText(GuiFormat.formatSpeedBps(metrics.uploadSpeedBps()));
            sessionDurationField.setText(GuiFormat.formatDuration(metrics.sessionDurationSeconds()));
        } else {
            downloadSpeedField.setText("—");
            uploadSpeedField.setText("—");
            sessionDurationField.setText("—");
        }

        // Tổng dung lượng hôm nay (DB)
        long todayBytes = metricsDao.getTodayTrafficBytes(clientId);
        todayTrafficField.setText(GuiFormat.formatBytes(todayBytes));

        // Thanh đo hạn mức theo rule (không rule → ẩn thanh + ghi chú)
        RuleRecord rule = ruleDao.getRule(clientId);
        if (rule != null && rule.dailyTrafficQuotaBytes() > 0) {
            quotaBar.setValue(GuiFormat.clampPercent(todayBytes, rule.dailyTrafficQuotaBytes()));
            quotaBar.setString(GuiFormat.formatBytes(todayBytes) + " / " + GuiFormat.formatBytes(rule.dailyTrafficQuotaBytes()));
            quotaBar.setVisible(true);
        } else {
            quotaBar.setValue(0);
            quotaBar.setString("Không giới hạn dung lượng");
        }
        if (rule != null && rule.dailyUsageLimitSeconds() > 0) {
            long usedSeconds = metrics != null ? metrics.sessionDurationSeconds() : 0;
            usageBar.setValue(GuiFormat.clampPercent(usedSeconds, rule.dailyUsageLimitSeconds()));
            usageBar.setString(GuiFormat.formatDuration(usedSeconds) + " / " + GuiFormat.formatDuration(rule.dailyUsageLimitSeconds()));
            usageBar.setVisible(true);
        } else {
            usageBar.setValue(0);
            usageBar.setString("Không giới hạn thời gian");
        }

        // Trạng thái PAUSED (đang bị khóa bởi Rule Engine)
        boolean enforced = ruleEngine != null && ruleEngine.isEnforced(clientId);
        if (enforced) {
            quotaBar.setForeground(new Color(200, 0, 0));
            usageBar.setForeground(new Color(200, 0, 0));
        } else {
            quotaBar.setForeground(null);
            usageBar.setForeground(null);
        }
        updateQuickActionButtons();
    }

    private void clearCenterPanel() {
        deviceNameField.setText("—");
        macField.setText("—");
        osField.setText("—");
        sessionDurationField.setText("—");
        downloadSpeedField.setText("—");
        uploadSpeedField.setText("—");
        todayTrafficField.setText("—");
        quotaBar.setValue(0);
        quotaBar.setString("Chưa chọn thiết bị");
        usageBar.setValue(0);
        usageBar.setString("Chưa chọn thiết bị");
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    /** Ghi 1 dòng sự kiện vào console log kèm timestamp (đã trên EDT). */
    private void appendEventLog(String message) {
        eventLogArea.append("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + "\n");
        eventLogArea.setCaretPosition(eventLogArea.getDocument().getLength()); // auto-scroll cuối
    }

    /**
     * Mở dialog cấu hình chính sách cho client đang chọn (Task 5.2). Lưu
     * thành công → đẩy CONFIG_SYNC xuống client ngay + log sự kiện.
     */
    private void openPolicyDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("⚠ Chưa chọn thiết bị nào để cấu hình");
            return;
        }
        PolicyConfigDialog dialog = new PolicyConfigDialog(this, selected, ruleDao,
                () -> {
                    boolean pushed = ruleEngine != null
                            && ruleEngine.syncConfigToClient(selected.clientId());
                    appendEventLog(pushed
                            ? "Đã đẩy CONFIG_SYNC xuống client '" + selected.deviceName() + "'"
                            : "⚠ Không đồng bộ được tới client '" + selected.deviceName()
                                    + "' (offline) — cấu hình đã lưu trong DB");
                    refreshNow();
                });
        dialog.setVisible(true);
    }

    /** Reload danh sách thiết bị + chi tiết ngay lập tức (gọi từ ngoài EDT-safe). */
    public void refreshNow() {
        if (SwingUtilities.isEventDispatchThread()) {
            reloadDeviceList();
            refreshCenterPanel();
        } else {
            SwingUtilities.invokeLater(() -> {
                reloadDeviceList();
                refreshCenterPanel();
            });
        }
    }

    /** Hiển thị cửa sổ giữa màn hình (tiện cho ServerApp). */
    public void showFrame() {
        setVisible(true);
    }
}
