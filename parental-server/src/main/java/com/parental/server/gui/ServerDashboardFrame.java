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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.MetricsPayload;
import com.parental.common.protocol.payload.UnpairPayload;
import com.parental.server.db.ActivityDao;
import com.parental.server.db.BlockedDomainDao;
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

    // --- Activity Monitoring (Mục 3.9) ---
    private JTextField activeAppField;
    private javax.swing.table.DefaultTableModel activityTableModel;
    private JTable activityTable;
    private java.time.Instant lastActivityTableRefresh = java.time.Instant.EPOCH;

    // --- Bộ lọc + xóa lịch sử hoạt động (Mở rộng nhật ký) ---
    /** Số bản ghi nhật ký hiển thị trên Dashboard (tăng từ 10 → 30). */
    static final int ACTIVITY_LOG_LIMIT = 30;

    /** Chỉ số cột JTable nhật ký — dùng cho RowFilter. */
    private static final int ACTIVITY_COLUMN_TIME = 0;
    private static final int ACTIVITY_COLUMN_PROCESS = 1;
    private static final int ACTIVITY_COLUMN_TITLE = 2;

    private JTextField activityFilterField;
    private javax.swing.table.TableRowSorter<javax.swing.table.TableModel> activitySorter;
    private JButton clearActivityButton;

    // --- Quick actions (Task 5.2) ---
    private JButton pauseButton;
    private JButton resumeButton;
    private JTextField alertMessageField;
    private JButton sendAlertButton;

    // --- Quản trị thiết bị (Alias + Delete/Unpair) ---
    private JButton deleteDeviceButton;
    private JButton renameButton;

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
        // Dọn dữ liệu RÁC era script PowerShell lỗi (process "ACTIVE.exe",
        // title "not recognized") còn sót trong DB — chạy ĐÚNG 1 LẦN khi mở.
        if (serverEngine.getActivityDao() != null) {
            int purged = serverEngine.getActivityDao().purgeCorruptedRows();
            if (purged > 0) {
                appendEventLog("\uD83E\uDDF9 \u0110\u00E3 d\u1ECDn " + purged
                        + " b\u1EA3n ghi ho\u1EA1t \u0111\u1ED9ng r\u00E1c t\u1EEB era l\u1ED7i PowerShell");
            }
        }
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void initUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // đóng qua windowClosing handler
        // Bố cục v2: 1100x800 thoáng hơn cho cột phải (Hạn mức + Hoạt động
        // người dùng + JTable nhật ký cao 160px) — các panel không còn co đè
        // lên nhau ở kích thước mặc định.
        setSize(1100, 800);
        setMinimumSize(new Dimension(950, 680));
        setLocationRelativeTo(null); // canh giữa màn hình
        setLayout(new BorderLayout());

        add(buildSidebar(), BorderLayout.WEST);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        // Load danh sách thiết bị lần đầu + cập nhật trạng thái nút ngay
        reloadDeviceList();
        updateQuickActionButtons();
        updateDeviceAdminButtons();
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

        // Nút ghép đôi thiết bị mới (Device Pairing) + xóa thiết bị + dialog cấu hình chính sách (Task 5.2)
        JButton addDeviceButton = new JButton("\u2795 Th\u00EAm thi\u1EBFt b\u1ECB m\u1EDBi");
        addDeviceButton.addActionListener(event -> openPairingDialog());

        deleteDeviceButton = new JButton("\uD83D\uDDD1 X\u00F3a thi\u1EBFt b\u1ECB");
        deleteDeviceButton.setEnabled(false); // chỉ bật khi có thiết bị đang chọn
        deleteDeviceButton.addActionListener(event -> deleteSelectedDevice());

        JButton policyButton = new JButton("\u2699 C\u1EA5u h\u00ECnh ch\u00EDnh s\u00E1ch...");
        policyButton.addActionListener(event -> openPolicyDialog());

        JPanel buttonPanel = new JPanel(new GridLayout(3, 1, 0, 4));
        buttonPanel.add(addDeviceButton);
        buttonPanel.add(deleteDeviceButton);
        buttonPanel.add(policyButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Xóa thiết bị đang chọn (Delete / Unpair Client): hộp thoại xác nhận →
     * nếu đang ONLINE: gửi UNPAIR + ngắt phiên qua {@code TcpServerEngine.unpairClient}
     * → xóa sạch DB (transaction) qua {@code ClientDao.deleteClient} → cập nhật
     * JList + chi tiết + Event Log. Chạy trên SwingWorker (DAO blocking).
     */
    private void deleteSelectedDevice() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("\u26A0 Ch\u01B0a ch\u1ECDn thi\u1EBFt b\u1ECB n\u00E0o \u0111\u1EC3 x\u00F3a");
            return;
        }
        String deviceDisplay = selected.alias() != null && !selected.alias().isBlank()
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : selected.deviceName();
        int choice = JOptionPane.showConfirmDialog(this,
                "X\u00F3a thi\u1EBFt b\u1ECB '" + deviceDisplay + "' kh\u1ECFi danh s\u00E1ch qu\u1EA3n l\u00FD?\n\n"
                        + "\u2022 Thi\u1EBFt b\u1ECB \u0111ang online s\u1EBD b\u1ECB ng\u1EAFt k\u1EBFt n\u1ED1i v\u00E0 quay v\u1EC1 m\u00E0n h\u00ECnh gh\u00E9p \u0111\u00F4i\n"
                        + "\u2022 To\u00E0n b\u1ED9 d\u1EEF li\u1EC7u (h\u1EA1n m\u1EE9c, l\u1ECBch s\u1EED traffic, l\u1EC7nh) s\u1EBD b\u1ECB x\u00F3a s\u1EA1ch\n\n"
                        + "Thao t\u00E1c kh\u00F4ng th\u1EC3 ho\u00E0n t\u1EA3c!",
                "X\u00F3a thi\u1EBFt b\u1ECB",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        String clientId = selected.clientId();
        boolean wasOnline = "ONLINE".equals(selected.status());
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                // 1. ONLINE → gửi UNPAIR + ngắt phiên (reason UNPAIRED)
                if (wasOnline) {
                    serverEngine.unpairClient(clientId, UnpairPayload.DEFAULT_REASON);
                }
                // 2. Xóa sạch DB (transaction: clients + rules + metrics + commands + domains)
                return clientDao.deleteClient(clientId)
                        ? "\uD83D\uDDD1 \u0110\u00E3 x\u00F3a thi\u1EBFt b\u1ECB '" + deviceDisplay
                                + "' kh\u1ECFi danh s\u00E1ch qu\u1EA3n l\u00FD"
                                + (wasOnline ? " (\u0111\u00E3 g\u1EEDi UNPAIR + ng\u1EAFt k\u1EBFt n\u1ED1i)" : " (offline)")
                        : null;
            }

            @Override
            protected void done() {
                try {
                    String successMessage = get();
                    if (successMessage != null) {
                        appendEventLog(successMessage);
                    } else {
                        appendEventLog("\u26A0 X\u00F3a thi\u1EBFt b\u1ECB th\u1EA5t b\u1EA1i (l\u1ED7i c\u01A1 s\u1EDF d\u1EEF li\u1EC7u)");
                    }
                } catch (Exception e) {
                    appendEventLog("\u26A0 L\u1ED7i khi x\u00F3a thi\u1EBFt b\u1ECB: " + e.getMessage());
                    log.error("Delete device failed: {}", e.getMessage(), e);
                }
                refreshNow();
            }
        }.execute();
    }

    /**
     * Đặt biệt danh (Alias) cho thiết bị đang chọn: Input Dialog → lưu DB
     * qua {@code ClientDao.updateAlias} (nhập r\u00F5ng → x\u00F3a biệt danh) →
     * cập nhật danh sách + tiêu đề thông tin ngay trên EDT.
     */
    private void renameSelectedDevice() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("\u26A0 Ch\u01B0a ch\u1ECDn thi\u1EBFt b\u1ECB n\u00E0o \u0111\u1EC3 \u0111\u1ED5i t\u00EAn");
            return;
        }
        String input = (String) JOptionPane.showInputDialog(this,
                "Nh\u1EADp bi\u1EC7t danh m\u1EDBi cho '" + selected.deviceName() + "':\n"
                        + "(\u0111\u1EC3 tr\u1ED1ng r\u1ED3i b\u1EA5m OK \u2192 x\u00F3a bi\u1EC7t danh, v\u1EC1 t\u00EAn m\u00E1y g\u1ED1c)",
                "\u270F\uFE0F \u0110\u1ED5i t\u00EAn thi\u1EBFt b\u1ECB",
                JOptionPane.PLAIN_MESSAGE, null, null,
                selected.alias() != null ? selected.alias() : selected.deviceName());
        if (input == null) {
            return; // người dùng bấm Cancel — không đổi gì
        }
        String newAlias = input.trim();
        String clientId = selected.clientId();
        String deviceName = selected.deviceName();

        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                boolean saved = clientDao.updateAlias(clientId, newAlias);
                if (saved) {
                    clientDao.getAllClients().stream()
                            .filter(record -> clientId.equals(record.clientId()))
                            .findFirst()
                            .ifPresent(updated -> latestRecord.set(updated));
                }
                return saved ? newAlias : null;
            }

            private final java.util.concurrent.atomic.AtomicReference<ClientRecord> latestRecord
                    = new java.util.concurrent.atomic.AtomicReference<>();

            @Override
            protected void done() {
                try {
                    String savedAlias = get();
                    if (savedAlias == null) {
                        appendEventLog("\u26A0 Kh\u00F4ng l\u01B0u \u0111\u01B0\u1EE3c bi\u1EC7t danh (thi\u1EBFt b\u1ECB kh\u00F4ng t\u1ED3n t\u1EA1i trong DB)");
                        return;
                    }
                    appendEventLog(savedAlias.isEmpty()
                            ? "\u270F\uFE0F \u0110\u00E3 x\u00F3a bi\u1EC7t danh c\u1EE7a '" + deviceName + "' — v\u1EC1 t\u00EAn m\u00E1y g\u1ED1c"
                            : "\u270F\uFE0F \u0110\u1EB7t bi\u1EC7t danh '" + savedAlias + "' cho thi\u1EBFt b\u1ECB '" + deviceName + "'");
                } catch (Exception e) {
                    appendEventLog("\u26A0 L\u1ED7i khi \u0111\u1ED5i t\u00EAn: " + e.getMessage());
                }
                refreshNow(); // cập nhật JList + chi tiết ngay trên EDT
            }
        }.execute();
    }

    /**
     * Mở {@link PairingDialog} hiển thị mã PIN ghép đôi (Device Pairing):
     * PIN 6 số cỡ to + đếm ngược 5 phút + đổi PIN; tự đóng và log sự kiện khi
     * một client ghép đôi thành công. Chỉ khả dụng khi server chạy CÓ database
     * (cần bảng {@code pairing_sessions} + {@code clients.device_token}).
     */
    private void openPairingDialog() {
        if (serverEngine.getPairingDao() == null) {
            appendEventLog("\u26A0 Server \u0111ang ch\u1EA1y kh\u00F4ng database \u2014 kh\u00F4ng th\u1EC3 gh\u00E9p \u0111\u00F4i thi\u1EBFt b\u1ECB");
            return;
        }
        PairingDialog dialog = new PairingDialog(this, serverEngine.getPairingDao(), () -> {
            appendEventLog("\uD83C\uDF89 M\u1ED9t thi\u1EBFt b\u1ECB m\u1EDBi \u0111\u00E3 gh\u00E9p \u0111\u00F4i th\u00E0nh c\u00F4ng \u2014 c\u00F3 th\u1EC3 k\u1EBFt n\u1ED1i b\u1EB1ng deviceToken");
            refreshNow();
        });
        dialog.open();
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

        // Nút Đổi tên đặt ngay cạnh trường Tên máy
        renameButton = new JButton("\u270F\uFE0F \u0110\u1ED5i t\u00EAn");
        renameButton.addActionListener(event -> renameSelectedDevice());
        renameButton.setEnabled(false); // bật khi có thiết bị đang chọn

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
        gbc.gridx = 2;
        gbc.weightx = 0;
        infoPanel.add(renameButton, gbc);

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

        // --- Hoạt động tức thời (Activity Monitoring — Mục 3.9) ---
        JPanel activityPanel = new JPanel(new BorderLayout(8, 0));
        activityPanel.setBorder(BorderFactory.createTitledBorder("Hoạt động người dùng"));
        activeAppField = new JTextField("—");
        activeAppField.setEditable(false);
        activeAppField.setHorizontalAlignment(JTextField.CENTER);
        activeAppField.setFont(activeAppField.getFont().deriveFont(Font.BOLD, 13f));
        activityPanel.add(activeAppField, BorderLayout.CENTER);

        // --- Nhật ký hoạt động gần đây (JTable 10 bản ghi mới nhất) ---
        activityTableModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Thời điểm", "Tiến trình", "Tiêu đề cửa sổ"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // chỉ đọc
            }
        };
        activityTable = new JTable(activityTableModel);
        activityTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        activityTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        activityTable.getColumnModel().getColumn(2).setPreferredWidth(420);
        activityTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        // Title cửa sổ dài → cắt trong cell (chuỗi dài không phá bố cục cột)
        activityTable.getColumnModel().getColumn(2).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public java.awt.Component getTableCellRendererComponent(JTable table,
                            Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        setText(value == null ? "" : GuiFormat.truncateForDisplay(value.toString(), 80));
                        setToolTipText(value == null ? null : value.toString());
                        return this;
                    }
                });
        // Sắp xếp + lọc dòng JTable ( ô "Lọc tiến trình/tiêu đề" )
        activitySorter = new javax.swing.table.TableRowSorter<>(activityTable.getModel());
        activityTable.setRowSorter(activitySorter);
        // Menu chuột phải: chặn nhanh tên miền từ dòng nhật ký được chọn
        attachQuickBlockPopup(activityTable);
        JScrollPane activityScroll = new JScrollPane(activityTable);
        // Cao 160px cố định định + chặn co hẹp — bảng nhật ký luôn trọn trong JScrollPane,
        // không bị panel khác đè bẹp (Box.Y_AXIS tôn trọng preferred size).
        activityScroll.setPreferredSize(new Dimension(880, 160));
        activityScroll.setMinimumSize(new Dimension(600, 160));
        activityScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        activityScroll.setBorder(BorderFactory.createTitledBorder(
                "Nhật ký hoạt động gần đây (30 bản ghi — gõ vào ô lọc để tìm nhanh)"));

        // --- Ô lọc tìm kiếm tiến trình/tiêu đề (TableRowSorter) ---
        JPanel filterPanel = new JPanel(new BorderLayout(6, 0));
        filterPanel.add(new JLabel("🔍 Lọc tiến trình/tiêu đề:"), BorderLayout.WEST);
        activityFilterField = new JTextField();
        activityFilterField.setToolTipText("Ví dụ: chrome, edge, youtube — lọc theo tên tiến trình hoặc tiêu đề cửa sổ");
        activityFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyActivityFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyActivityFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyActivityFilter();
            }
        });
        filterPanel.add(activityFilterField, BorderLayout.CENTER);

        // --- Nút xóa sạch lịch sử hoạt động (activity_logs) của client đang chọn ---
        clearActivityButton = new JButton("🗑️ Xóa lịch sử hoạt động");
        clearActivityButton.setToolTipText("Dọn sạch bản ghi nhật ký hoạt động của thiết bị đang chọn trong DB");
        clearActivityButton.addActionListener(event -> clearActivityHistory());

        // --- Nút mở rộng: xem 100 bản ghi gần nhất trong dialog lớn (title full) ---
        JButton expandButton = new JButton("⛶ Xem toàn bộ / Mở rộng");
        expandButton.setToolTipText("Mở cửa sổ lớn hiển thị tối đa 200 bản ghi gần nhất — tiêu đề đầy đủ, có lọc tìm kiếm");
        expandButton.addActionListener(event -> openExpandedActivityDialog());

        JPanel activityControlRow = new JPanel(new BorderLayout(8, 0));
        activityControlRow.add(filterPanel, BorderLayout.CENTER);
        JPanel eastButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        eastButtons.add(clearActivityButton);
        eastButtons.add(expandButton);
        activityControlRow.add(eastButtons, BorderLayout.EAST);

        // --- Cột phải xếp DỌC bằng BoxLayout (Y-AXIS) + strut chống co đè:
        // Hạn mức và Hoạt động người dùng không còn bị đè bẹp lên nhau. ---
        JPanel cardsRow = new JPanel(new GridLayout(1, 1));
        cardsRow.add(cardsPanel); // bọc để giữ chiều cao preferred của cardsPanel
        JPanel rulesRow = new JPanel(new GridLayout(1, 1));
        rulesRow.add(rulesPanel);
        JPanel activityZone = new JPanel();
        activityZone.setLayout(new BoxLayout(activityZone, BoxLayout.Y_AXIS));
        activityZone.add(activityPanel);
        activityZone.add(Box.createVerticalStrut(6));
        activityZone.add(activityControlRow);
        activityZone.add(Box.createVerticalStrut(6));
        activityZone.add(activityScroll); // preferred 160px được tôn trọng trong Box

        JPanel middle = new JPanel();
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));
        middle.add(infoPanel);
        middle.add(Box.createVerticalStrut(8));
        middle.add(cardsRow);
        middle.add(Box.createVerticalStrut(8));
        middle.add(rulesRow);
        middle.add(Box.createVerticalStrut(8));
        middle.add(activityZone);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        center.add(middle, BorderLayout.CENTER);
        center.add(buildQuickActionsPanel(), BorderLayout.SOUTH);
        return center;
    }

    /**
     * Áp dụng bộ lọc tìm kiếm cho bảng nhật ký hoạt động — so khớp KHÔNG
     * phân biệt hoa/thường trên cả 3 cột (Thời điểm/Tiến trình/Tiêu đề).
     */
    private void applyActivityFilter() {
        if (activitySorter == null) {
            return; // bảng chưa khởi tạo xong (trong initUi)
        }
        // Logic dựng filter tách vào test hook (pure function — unit test headless)
        activitySorter.setRowFilter(
                ServerDashboardFrameTestHook.buildActivityRowFilter(activityFilterField.getText())
                        .orElse(null));
    }

    /**
     * Xóa sạch lịch sử hoạt động của thiết bị đang chọn: confirm dialog →
     * {@link ActivityDao#deleteAll(String)} trên SwingWorker (DAO blocking) →
     * reload bảng + log sự kiện.
     */
    private void clearActivityHistory() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("⚠ Chưa chọn thiết bị nào để xóa lịch sử hoạt động");
            return;
        }
        String deviceDisplay = selected.alias() != null && !selected.alias().isBlank()
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : selected.deviceName();
        int choice = JOptionPane.showConfirmDialog(this,
                "Xóa TOÀN BỘ nhật ký hoạt động của thiết bị '" + deviceDisplay + "'?\n\n"
                        + "Thao tác không thể hoàn tác!",
                "Xóa lịch sử hoạt động",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        String clientId = selected.clientId();
        // XÓA TRẮNG NGAY trên EDT — phản hồi tức thì trước cả khi SwingWorker
        // chạy xong: bảng nhật ký + ô "Hoạt động người dùng" + bộ lọc.
        clearActivityTable();
        activeAppField.setText("—");
        // Xóa luôn cache "Đang mở" trong phiên (nếu client online) — nếu không
        // refreshNow() sẽ nạp lại hoạt động cũ từ session.lastActivity.
        ClientSession liveSession = sessionManager.get(clientId);
        if (liveSession != null) {
            liveSession.setLastActivity(null);
        }
        new javax.swing.SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return serverEngine.getActivityDao() != null
                        ? serverEngine.getActivityDao().deleteAll(clientId)
                        : false;
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        appendEventLog("🗑️ Đã xóa toàn bộ lịch sử hoạt động của '"
                                + deviceDisplay + "'");
                    } else {
                        appendEventLog("⚠ Xóa lịch sử hoạt động thất bại (lỗi cơ sở dữ liệu)");
                    }
                } catch (Exception e) {
                    appendEventLog("⚠ Lỗi khi xóa lịch sử hoạt động: " + e.getMessage());
                    log.error("Clear activity history failed: {}", e.getMessage(), e);
                }
                lastActivityTableRefresh = java.time.Instant.EPOCH; // buộc reload bảng
                refreshNow();
            }
        }.execute();
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

    /**
     * Renderer dòng thiết bị: icon ● Online/Offline + tên máy + IP + trạng thái.
     * <p>Có alias: {@code ● [Biệt danh] (Tên gốc) — Trạng thái}; không alias →
     * giữ nguyên định dạng cũ {@code ● Tên máy (IP) — Trạng thái}.</p>
     *
     * <p><b>Hotfix tương phản khi chọn</b>: mặc định Swing vẽ nền xanh đậm khi
     * item được chọn → chữ xanh lá (Online) bị "chìm", không đọc được tên máy.
     * Khi selected: nền xanh xám nhạt {@code #E2E8F0} + toàn bộ chữ (kể cả
     * chấm trạng thái) chuyển sang đen đậm {@code #0F172A} — luôn sắc nét ở
     * CẢ HAI trạng thái selected/unselected.</p>
     */
    private static final class DeviceCellRenderer extends DefaultListCellRenderer {

        /** Nền dịu mắt khi dòng được chọn (xanh xám nhạt). */
        private static final Color SELECTED_BACKGROUND = new Color(0xE2, 0xE8, 0xF0);

        /** Chữ đen đậm tương phản cao khi dòng được chọn. */
        private static final Color SELECTED_FOREGROUND = new Color(0x0F, 0x17, 0x2A);

        /** Khoảng đệm dòng để chữ không dính mép danh sách. */
        private static final javax.swing.border.Border CELL_PADDING =
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEmptyBorder(4, 8, 4, 8),
                        BorderFactory.createEmptyBorder());

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ClientRecord record && component instanceof JLabel label) {
                boolean online = "ONLINE".equals(record.status());
                String icon = online ? "●" : "○";
                String statusText = online ? "Online" : "Offline";
                boolean hasAlias = record.alias() != null && !record.alias().isBlank();
                if (hasAlias) {
                    label.setText(String.format("%s [%s] (%s) — %s", icon, record.alias(),
                            record.deviceName(), statusText));
                } else {
                    label.setText(String.format("%s %s (%s) — %s", icon, record.deviceName(),
                            record.ipAddress(), statusText));
                }
                if (selected) {
                    // Selected: nền dịu + chữ đen đậm — TƯƠNG PHẢN CAO, dễ đọc.
                    label.setBackground(SELECTED_BACKGROUND);
                    label.setForeground(SELECTED_FOREGROUND);
                    label.setOpaque(true);
                } else {
                    // Unselected: giữ màu trạng thái gốc (xanh lá Online / xám Offline).
                    label.setForeground(online ? new Color(0, 128, 0) : Color.GRAY);
                }
                label.setBorder(CELL_PADDING);
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

            @Override
            public void onActivityReport(ClientSession session,
                    com.parental.common.protocol.payload.ActivityReportPayload activity) {
                // Activity Monitoring (Mục 3.9): cập nhật "Đang mở" tức thời +
                // nhắc refresher nạp lại bảng nhật ký (DB đã có bản ghi mới).
                SwingUtilities.invokeLater(() -> {
                    lastActivityTableRefresh = java.time.Instant.EPOCH; // buộc reload bảng
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
        updateDeviceAdminButtons();
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

        // Thông tin thiết bị (có alias → hiển thị "alias (tên gốc)"; không → tên gốc)
        boolean hasAlias = selected.alias() != null && !selected.alias().isBlank();
        deviceNameField.setText(hasAlias
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : orDash(selected.deviceName()));
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

        // Hoạt động tức thời từ phiên (Activity Monitoring — Mục 3.9);
        // cắt ngắn tiêu đề dài để không phình chiều ngang panel (hotfix bố cục).
        com.parental.common.protocol.payload.ActivityReportPayload activity =
                session != null ? session.getLastActivity() : null;
        if (activity != null && !activity.activeProcessName().isEmpty()) {
            String title = activity.activeWindowTitle();
            String display = activity.activeProcessName()
                    + (title.isEmpty() ? "" : " — " + title);
            activeAppField.setText(GuiFormat.truncateForDisplay(display, 90));
        } else {
            activeAppField.setText("—");
        }

        // Nhật ký hoạt động gần đây (reload khi đổi thiết bị hoặc có bản ghi mới,
        // tối đa 1 lần/2s để không đập DB liên tục)
        java.time.Instant now = java.time.Instant.now();
        if (java.time.Duration.between(lastActivityTableRefresh, now).getSeconds() >= 2) {
            lastActivityTableRefresh = now;
            reloadActivityTable(clientId);
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
        activeAppField.setText("—");
        clearActivityTable(); // xóa trắng + bỏ lọc (thay cho setRowCount(0) trực tiếp)
        quotaBar.setValue(0);
        quotaBar.setString("Chưa chọn thiết bị");
        usageBar.setValue(0);
        usageBar.setString("Chưa chọn thiết bị");
    }

    /** Nạp 30 bản ghi hoạt động gần nhất của client vào JTable (EDT, DAO blocking nhẹ). */
    private void reloadActivityTable(String clientId) {
        if (serverEngine.getActivityDao() == null) {
            return; // chạy không DB — không có nhật ký
        }
        List<ActivityDao.ActivityRecord> records =
                serverEngine.getActivityDao().getRecentActivities(clientId, ACTIVITY_LOG_LIMIT);
        activityTableModel.setRowCount(0);
        // recorded_at ghi bằng giờ LOCAL tường minh (ActivityDao) — format qua
        // test hook dùng chung (pure function, test headless được).
        for (ActivityDao.ActivityRecord record : records) {
            activityTableModel.addRow(ServerDashboardFrameTestHook.buildActivityTableRow(record));
        }
        activityTable.repaint();
    }

    /**
     * Mở dialog "⛶ Xem toàn bộ / Mở rộng" — tối đa 200 bản ghi hoạt động gần
     * nhất: bảng lớn 1000x650 resizable canh giữa, cột Tiêu đề hiển thị ĐẦY
     * ĐỦ (không cắt, scroll ngang/dọc thoải mái), ô lọc thời gian thực riêng,
     * nút 🔄 Làm mới + Đóng.
     */
    private void openExpandedActivityDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("⚠ Chưa chọn thiết bị nào để xem nhật ký");
            return;
        }
        ActivityDao activityDao = serverEngine.getActivityDao();
        if (activityDao == null) {
            appendEventLog("⚠ Server đang chạy không database — không có nhật ký hoạt động");
            return;
        }
        String clientId = selected.clientId();
        String deviceDisplay = selected.alias() != null && !selected.alias().isBlank()
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : selected.deviceName();

        JDialog dialog = new JDialog(this, "Nhật ký hoạt động đầy đủ — " + deviceDisplay, true);
        dialog.setSize(1000, 650);
        dialog.setResizable(true);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(8, 8));

        // --- Mô hình bảng riêng: title HIỂN THỊ ĐẦY ĐỦ (không cắt) ---
        javax.swing.table.DefaultTableModel fullModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Thời điểm", "Tiến trình", "Tiêu đề cửa sổ"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable fullTable = new JTable(fullModel);
        fullTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        fullTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        fullTable.getColumnModel().getColumn(2).setPreferredWidth(680);
        // AUTO_RESIZE_OFF → cột title giữ nguyên độ rộng, tràn thì scroll ngang
        fullTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> fullSorter =
                new javax.swing.table.TableRowSorter<>(fullModel);
        fullTable.setRowSorter(fullSorter);
        // Menu chuột phải: chặn nhanh tên miền từ dòng nhật ký được chọn
        attachQuickBlockPopup(fullTable);
        JScrollPane fullScroll = new JScrollPane(fullTable);
        fullScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        fullScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        Runnable reloadFullTable = () -> {
            List<ActivityDao.ActivityRecord> records = activityDao
                    .getRecentActivitiesForDialog(clientId,
                            ServerDashboardFrameTestHook.DIALOG_ROW_LIMIT);
            fullModel.setRowCount(0);
            for (ActivityDao.ActivityRecord record : records) {
                // KHÔNG truncate — cột Tiêu đề cửa sổ giữ nguyên vẹn
                fullModel.addRow(ServerDashboardFrameTestHook.buildActivityTableRow(record));
            }
        };
        reloadFullTable.run();

        // --- Ô lọc thời gian thực (dùng chung logic buildActivityRowFilter) ---
        JTextField fullFilterField = new JTextField();
        fullFilterField.setToolTipText("Ví dụ: chrome, browser (Cốc Cốc), youtube — lọc theo tiến trình hoặc tiêu đề");
        fullFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyFullDialogFilter(fullFilterField, fullSorter);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyFullDialogFilter(fullFilterField, fullSorter);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyFullDialogFilter(fullFilterField, fullSorter);
            }
        });

        JButton refreshButton = new JButton("🔄 Làm mới");
        refreshButton.addActionListener(event -> reloadFullTable.run());
        JButton closeButton = new JButton("Đóng");
        closeButton.addActionListener(event -> dialog.dispose());

        JPanel controlRow = new JPanel(new BorderLayout(8, 0));
        controlRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        controlRow.add(new JLabel("🔍 Lọc tiến trình/tiêu đề:"), BorderLayout.WEST);
        controlRow.add(fullFilterField, BorderLayout.CENTER);
        JPanel dialogButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        dialogButtons.add(refreshButton);
        dialogButtons.add(closeButton);
        controlRow.add(dialogButtons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        content.add(controlRow, BorderLayout.NORTH);
        content.add(fullScroll, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.setVisible(true); // modal — đóng bằng nút Đóng hoặc Esc qua X
    }

    /** Áp filter cho bảng dialog mở rộng (dùng chung logic test hook). */
    private static void applyFullDialogFilter(JTextField filterField,
            javax.swing.table.TableRowSorter<? extends javax.swing.table.TableModel> sorter) {
        sorter.setRowFilter(ServerDashboardFrameTestHook
                .buildActivityRowFilter(filterField.getText()).orElse(null));
    }

    // ------------------------------------------------------------------
    // Chặn nhanh tên miền từ Nhật ký hoạt động (chuột phải trên JTable)
    // ------------------------------------------------------------------

    /**
     * Gắn menu chuột phải "🚫 Chặn tên miền này" cho 1 bảng nhật ký hoạt động
     * (dùng chung cho bảng Dashboard lẫn dialog "Xem toàn bộ"): trích domain
     * gợi ý từ tiêu đề → confirm → {@code addDomain} + đẩy CONFIG_SYNC tức thì.
     */
    private void attachQuickBlockPopup(JTable table) {
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                maybeShowQuickBlockPopup(table, event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                maybeShowQuickBlockPopup(table, event);
            }
        });
    }

    /** MouseEvent là popup trigger → chọn dòng dưới chuột và mở menu chặn. */
    private void maybeShowQuickBlockPopup(JTable table, java.awt.event.MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow); // click dòng nào chặn dòng đó
        int modelRow = table.convertRowIndexToModel(viewRow);
        String title = String.valueOf(table.getModel().getValueAt(modelRow,
                ServerDashboardFrameTestHook.ACTIVITY_COLUMN_TITLE));

        JMenuItem blockItem = new JMenuItem(ServerDashboardFrameTestHook
                .buildQuickBlockLabel(title));
        blockItem.addActionListener(actionEvent -> quickBlockDomainForClient(title));

        JPopupMenu popup = new JPopupMenu();
        popup.add(blockItem);
        popup.show(table, event.getX(), event.getY());
    }

    /**
     * Luồng chặn nhanh cho client đang chọn: gợi ý domain từ tiêu đề →
     * confirm → lưu DB qua {@link BlockedDomainDao#addDomain} → đẩy
     * CONFIG_SYNC xuống client ngay → mở hosts trắng danh sách phía client.
     */
    private void quickBlockDomainForClient(String windowTitle) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("⚠ Chưa chọn thiết bị nào để chặn tên miền");
            return;
        }
        ActivityDao activityDao = serverEngine.getActivityDao();
        BlockedDomainDao blockedDomainDao = serverEngine.getBlockedDomainDao();
        if (blockedDomainDao == null) {
            appendEventLog("⚠ Server đang chạy không database — không chặn tên miền được");
            return;
        }
        String suggestion = WindowTitleDomainExtractor.suggestDomain(windowTitle);

        // Ô nhập cho phép sửa gợi ý hoặc nhập tay khi không trích được
        String input = (String) JOptionPane.showInputDialog(this,
                "Tên miền cần chặn cho thiết bị '" + selected.deviceName() + "':",
                "🚫 Chặn tên miền này",
                JOptionPane.PLAIN_MESSAGE, null, null,
                suggestion != null ? suggestion : "");
        if (input == null || input.isBlank()) {
            return; // hủy
        }
        String domain = BlockedDomainDialogHelper.normalize(input);
        if (domain == null) {
            JOptionPane.showMessageDialog(this,
                    "Tên miền không hợp lệ: " + input
                            + "\n(ví dụ hợp lệ: youtube.com, facebook.com)",
                    "Tên miền không hợp lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn chặn tên miền [" + domain + "] cho thiết bị này?",
                "Xác nhận chặn tên miền",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        String clientId = selected.clientId();
        String deviceDisplay = selected.alias() != null && !selected.alias().isBlank()
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : selected.deviceName();
        boolean added = activityDao != null && blockedDomainDao.addDomain(clientId, domain);
        if (!added) {
            appendEventLog("⚠ Không chặn được '" + domain + "' cho '"
                    + deviceDisplay + "' (lỗi DB hoặc đã có sẵn)");
            return;
        }
        boolean pushed = serverEngine.pushConfigSync(clientId);
        appendEventLog("🚫 Đã chặn '" + domain + "' cho '" + deviceDisplay + "'"
                + (pushed ? " — CONFIG_SYNC đã đẩy xuống client"
                          : " — client offline, sẽ nhận khi kết nối lại"));
    }

    /** null/rỗng → "—" (dòng "—" trên UI thay cho dữ liệu thiếu). */
    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    /** Xóa trắng bảng nhật ký + bỏ bộ lọc (khi không chọn thiết bị). */
    private void clearActivityTable() {
        activityTableModel.setRowCount(0);
        if (activityFilterField != null && !activityFilterField.getText().isEmpty()) {
            activityFilterField.setText(""); // reset DocumentListener → setRowFilter(null)
        }
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

    /** Bật/tắt nút Xóa + Đổi tên theo việc có thiết bị đang chọn hay không. */
    private void updateDeviceAdminButtons() {
        boolean hasSelection = deviceList != null && deviceList.getSelectedValue() != null;
        if (deleteDeviceButton != null) {
            deleteDeviceButton.setEnabled(hasSelection);
        }
        if (renameButton != null) {
            renameButton.setEnabled(hasSelection);
        }
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
