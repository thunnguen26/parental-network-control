package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.ActionType;
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
 * Cửa sổ chính Dashboard giám sát (Task 5.1 & Phase 5) — thiết kế chuẩn theo ảnh mẫu:
 * Header xanh đậm có logo khiên địa cầu và đồng hồ; Sidebar tối phân khu điều hướng,
 * sự kiện nhanh và trạng thái hệ thống có sóng mini; Main Canvas với thẻ thiết bị,
 * thông tin chi tiết, 3 thẻ tốc độ sóng động, thanh hạn mức rule, nhật ký người dùng
 * và bảng Event Log chuyên dụng.
 * Sử dụng bộ icon vector thuần và cơ chế vẽ Swing chuẩn, đảm bảo hiển thị sắc nét,
 * không bị mất chữ hay ô vuông tofu.
 */
public class ServerDashboardFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ServerDashboardFrame.class);

    static final int REFRESH_INTERVAL_MS = 1_000;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter HEADER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss  dd/MM/yyyy");

    // Bảng màu hiện đại theo phong cách dịu mắt, tương phản chuẩn, chuyên nghiệp
    private static final Color HEADER_BG = new Color(0x0B, 0x19, 0x2E);
    private static final Color SIDEBAR_BG = new Color(0x0E, 0x1B, 0x2D);
    private static final Color CANVAS_BG = new Color(0xF1, 0xF5, 0xF9);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color CARD_BORDER_COLOR = new Color(0xE2, 0xE8, 0xF0);
    private static final Color PRIMARY_ACCENT = new Color(0x25, 0x63, 0xEB);
    private static final Color TEXT_DARK = new Color(0x0F, 0x17, 0x2A);
    private static final Color TEXT_MUTED = new Color(0x64, 0x74, 0x8B);

    private final TcpServerEngine serverEngine;
    private final ClientDao clientDao;
    private final MetricsDao metricsDao;
    private final RuleDao ruleDao;
    private final ClientSessionManager sessionManager;
    private final RuleEvaluationEngine ruleEngine;

    // --- Left sidebar ---
    private DefaultListModel<ClientRecord> deviceListModel;
    private JList<ClientRecord> deviceList;
    private JLabel deviceCountLabel;
    private JLabel serverStatusLabel;
    private JLabel headerClockLabel;

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
    private JLabel quotaTextLabel;
    private JLabel usageTextLabel;

    // --- Bottom event log ---
    private JTextArea eventLogArea;

    // --- Activity Monitoring (Mục 3.9) ---
    private JTextField activeAppField;
    private JLabel activeAppBadge;
    private javax.swing.table.DefaultTableModel activityTableModel;
    private JTable activityTable;
    private java.time.Instant lastActivityTableRefresh = java.time.Instant.EPOCH;

    static final int ACTIVITY_LOG_LIMIT = 30;
    static final long ACTIVITY_DIALOG_AUTO_REFRESH_MIN_MS = 2000;

    private JTextField activityFilterField;
    private javax.swing.table.TableRowSorter<javax.swing.table.TableModel> activitySorter;
    private JButton clearActivityButton;

    // --- Quick actions ---
    private JButton pauseButton;
    private JButton resumeButton;
    private JTextField alertMessageField;
    private JButton sendAlertButton;

    // --- Quản trị thiết bị ---
    private JButton deleteDeviceButton;
    private JButton renameButton;
    private JButton remoteScreenButton;

    private Timer uiRefresher;

    public ServerDashboardFrame(TcpServerEngine serverEngine, ClientDao clientDao,
                                MetricsDao metricsDao, RuleDao ruleDao,
                                ClientSessionManager sessionManager,
                                RuleEvaluationEngine ruleEngine) {
        super("Parental Network Control - Server Dashboard");
        this.serverEngine = Objects.requireNonNull(serverEngine, "serverEngine must not be null");
        this.clientDao = Objects.requireNonNull(clientDao, "clientDao must not be null");
        this.metricsDao = Objects.requireNonNull(metricsDao, "metricsDao must not be null");
        this.ruleDao = Objects.requireNonNull(ruleDao, "ruleDao must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.ruleEngine = ruleEngine;

        initUi();
        registerEngineListeners();
        startUiRefresher();

        if (serverEngine.getActivityDao() != null) {
            int purged = serverEngine.getActivityDao().purgeCorruptedRows();
            if (purged > 0) {
                appendEventLog("[OK] Đã dọn " + purged
                        + " bản ghi hoạt động rác từ era lỗi PowerShell");
            }
        }
    }

    // ------------------------------------------------------------------
    // UI Construction
    // ------------------------------------------------------------------

    private void initUi() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        if (!GraphicsEnvironment.isHeadless()) {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            setSize(Math.min(1320, screen.width), Math.min(880, screen.height));
            setLocationRelativeTo(null);
        } else {
            setSize(1320, 880);
        }
        setMinimumSize(new Dimension(1080, 700));
        getContentPane().setBackground(CANVAS_BG);
        setLayout(new BorderLayout());

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(CANVAS_BG);
        body.add(buildSidebar(), BorderLayout.WEST);
        body.add(buildCenterPanel(), BorderLayout.CENTER);

        add(buildHeaderPanel(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        reloadDeviceList();
        updateQuickActionButtons();
        updateDeviceAdminButtons();
    }

    private JPanel buildHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(HEADER_BG);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x1E, 0x2E, 0x44)),
                BorderFactory.createEmptyBorder(10, 18, 10, 22)));

        HeaderBrandIcon brandIcon = new HeaderBrandIcon();

        JLabel title = new JLabel("Parental Network Control");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JLabel subtitle = new JLabel("Server Dashboard — Quản lý mạng gia đình / văn phòng");
        subtitle.setForeground(new Color(0x94, 0xA3, 0xB8));
        subtitle.setFont(subtitle.getFont().deriveFont(12f));

        JPanel brandText = new JPanel();
        brandText.setOpaque(false);
        brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        brandText.add(title);
        brandText.add(Box.createVerticalStrut(2));
        brandText.add(subtitle);

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brand.setOpaque(false);
        brand.add(brandIcon);
        brand.add(brandText);

        serverStatusLabel = new JLabel("● Server Online");
        serverStatusLabel.setForeground(new Color(0x22, 0xC5, 0x5E));
        serverStatusLabel.setFont(serverStatusLabel.getFont().deriveFont(Font.BOLD, 12f));

        JLabel divider = new JLabel("|");
        divider.setForeground(new Color(0x33, 0x41, 0x55));
        divider.setFont(divider.getFont().deriveFont(13f));

        headerClockLabel = new JLabel();
        headerClockLabel.setForeground(new Color(0xCB, 0xD5, 0xE1));
        headerClockLabel.setFont(headerClockLabel.getFont().deriveFont(12f));

        JPanel status = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        status.setOpaque(false);
        status.add(serverStatusLabel);
        status.add(divider);
        status.add(headerClockLabel);

        updateHeaderStatus();

        header.add(brand, BorderLayout.WEST);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(SIDEBAR_BG);
        sidebar.setPreferredSize(new Dimension(268, 0));
        sidebar.setMinimumSize(new Dimension(268, 0));
        sidebar.setMaximumSize(new Dimension(268, Integer.MAX_VALUE));
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x1E, 0x2E, 0x44)),
                BorderFactory.createEmptyBorder(14, 12, 14, 12)));

        // 1. Nhóm Điều Hướng Chính (Top)
        JButton navOverview = createSidebarNavButton("Tổng quan", DashboardIcons.home(16, Color.WHITE), true);
        JButton navDeviceList = createSidebarNavButton("Danh sách thiết bị", DashboardIcons.monitor(16, new Color(0x94, 0xA3, 0xB8)), false);
        JButton navAccessMgmt = createSidebarNavButton("Quản lý truy cập", DashboardIcons.shield(16, new Color(0x94, 0xA3, 0xB8)), false);
        JButton navStatistics = createSidebarNavButton("Thống kê & Báo cáo", DashboardIcons.stats(16, new Color(0x94, 0xA3, 0xB8)), false);
        JButton navAbout = createSidebarNavButton("Giới thiệu", DashboardIcons.info(16, new Color(0x94, 0xA3, 0xB8)), false);

        navDeviceList.addActionListener(e -> {
            if (deviceList != null) {
                deviceList.requestFocusInWindow();
            }
        });
        navAccessMgmt.addActionListener(e -> openPolicyDialog());
        navStatistics.addActionListener(e -> openUsageChartDialog());
        navAbout.addActionListener(e -> openAboutDialog());

        JPanel navGroup = new JPanel();
        navGroup.setLayout(new BoxLayout(navGroup, BoxLayout.Y_AXIS));
        navGroup.setOpaque(false);
        navGroup.setAlignmentX(Component.CENTER_ALIGNMENT);
        for (JButton btn : List.of(navOverview, navDeviceList, navAccessMgmt, navStatistics, navAbout)) {
            navGroup.add(btn);
            navGroup.add(Box.createVerticalStrut(4));
        }

        // 2. Nhóm thao tác nhanh
        JPanel quickHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        quickHeader.setOpaque(false);
        quickHeader.setAlignmentX(Component.CENTER_ALIGNMENT);
        quickHeader.setPreferredSize(new Dimension(244, 26));
        quickHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel quickSectionTitle = new JLabel("Thao tác nhanh");
        quickSectionTitle.setIcon(DashboardIcons.lightning(14, new Color(0xF5, 0x9E, 0x0B)));
        quickSectionTitle.setIconTextGap(8);
        quickSectionTitle.setForeground(new Color(0x94, 0xA3, 0xB8));
        quickSectionTitle.setFont(quickSectionTitle.getFont().deriveFont(Font.BOLD, 11f));
        quickHeader.add(quickSectionTitle);

        JButton addDeviceBtn = createSidebarActionButton("Thêm thiết bị", DashboardIcons.plus(16, new Color(0x93, 0xC5, 0xFD)));
        addDeviceBtn.addActionListener(e -> openPairingDialog());

        deleteDeviceButton = createSidebarActionButton("Xóa thiết bị", DashboardIcons.trash(16, new Color(0x93, 0xC5, 0xFD)));
        deleteDeviceButton.setEnabled(false);
        deleteDeviceButton.addActionListener(e -> deleteSelectedDevice());

        remoteScreenButton = createSidebarActionButton("Xem màn hình", DashboardIcons.monitor(16, new Color(0x93, 0xC5, 0xFD)));
        remoteScreenButton.addActionListener(e -> openRemoteScreenDialog());

        JButton allScreensBtn = createSidebarActionButton("Quan sát đa dụng", DashboardIcons.grid(16, new Color(0x93, 0xC5, 0xFD)));
        allScreensBtn.addActionListener(e -> openRemoteScreenWallDialog());

        JButton powerBtn = createSidebarActionButton("Điều khiển nguồn", DashboardIcons.power(16, new Color(0x93, 0xC5, 0xFD)));
        powerBtn.addActionListener(e -> openPowerControlDialog());

        JPanel quickGroup = new JPanel();
        quickGroup.setLayout(new BoxLayout(quickGroup, BoxLayout.Y_AXIS));
        quickGroup.setOpaque(false);
        quickGroup.setAlignmentX(Component.CENTER_ALIGNMENT);
        for (JButton btn : List.of(addDeviceBtn, deleteDeviceButton, remoteScreenButton, allScreensBtn, powerBtn)) {
            quickGroup.add(btn);
            quickGroup.add(Box.createVerticalStrut(4));
        }

        // 3. Khối "Trạng thái hệ thống" ở chân sidebar
        CardPanel statusBox = new CardPanel(12, new Color(0x1E, 0x37, 0x58));
        statusBox.setBackground(new Color(0x12, 0x22, 0x38));
        statusBox.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        statusBox.setPreferredSize(new Dimension(244, 90));
        statusBox.setMinimumSize(new Dimension(244, 90));
        statusBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        statusBox.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel sysTitle = new JLabel("● Trạng thái hệ thống");
        sysTitle.setForeground(new Color(0x94, 0xA3, 0xB8));
        sysTitle.setFont(sysTitle.getFont().deriveFont(Font.BOLD, 11f));

        JLabel sysStatus = new JLabel("Hoạt động bình thường");
        sysStatus.setForeground(new Color(0x22, 0xC5, 0x5E));
        sysStatus.setFont(sysStatus.getFont().deriveFont(Font.BOLD, 12f));

        SparklineWavePanel sysWave = SparklineWavePanel.systemStatusWave();
        sysWave.setPreferredSize(new Dimension(216, 26));

        statusBox.add(sysTitle, BorderLayout.NORTH);
        statusBox.add(sysStatus, BorderLayout.CENTER);
        statusBox.add(sysWave, BorderLayout.SOUTH);

        sidebar.add(navGroup);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(quickHeader);
        sidebar.add(Box.createVerticalStrut(6));
        sidebar.add(quickGroup);
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(statusBox);

        return sidebar;
    }

    private JPanel buildCenterPanel() {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBackground(CANVAS_BG);
        center.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        center.add(buildTopManagedAndInfoSection());
        center.add(Box.createVerticalStrut(10));
        center.add(buildQuotaRuleCard());
        center.add(Box.createVerticalStrut(10));
        center.add(buildUserActivityCard());
        center.add(Box.createVerticalStrut(10));
        center.add(buildEventLogCard());

        JScrollPane scrollPane = new JScrollPane(center);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setBackground(CANVAS_BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel container = new JPanel(new BorderLayout());
        container.setBackground(CANVAS_BG);
        container.add(scrollPane, BorderLayout.CENTER);
        return container;
    }

    private JPanel buildTopManagedAndInfoSection() {
        JPanel section = new JPanel(new GridBagLayout());
        section.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 0, 10);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.35;
        gbc.weighty = 1.0;
        section.add(buildManagedDevicesCard(), gbc);

        gbc.insets = new Insets(0, 0, 0, 0);
        gbc.gridx = 1;
        gbc.weightx = 0.65;
        section.add(buildDeviceInfoCard(), gbc);

        return section;
    }

    private JPanel buildManagedDevicesCard() {
        deviceListModel = new DefaultListModel<>();
        deviceList = new JList<>(deviceListModel);
        deviceList.setCellRenderer(new DeviceCellRenderer());
        deviceList.setBackground(Color.WHITE);
        deviceList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        deviceList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshCenterPanel();
            }
        });

        JScrollPane scroll = new JScrollPane(deviceList);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xE8, 0xEE, 0xF5), 1));
        scroll.getViewport().setBackground(Color.WHITE);

        deviceCountLabel = new JLabel("0 thiết bị kết nối", SwingConstants.CENTER);
        deviceCountLabel.setForeground(TEXT_MUTED);
        deviceCountLabel.setFont(deviceCountLabel.getFont().deriveFont(11f));
        deviceCountLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setOpaque(false);
        content.add(scroll, BorderLayout.CENTER);
        content.add(deviceCountLabel, BorderLayout.SOUTH);

        return createCard("Thiết bị đang quản lý", DashboardIcons.monitor(16, PRIMARY_ACCENT),
                null, content);
    }

    private JPanel buildDeviceInfoCard() {
        renameButton = createWhiteActionButton("Đổi tên", DashboardIcons.edit(14, TEXT_DARK));
        renameButton.addActionListener(e -> renameSelectedDevice());
        renameButton.setEnabled(false);

        deviceNameField = createStyledDisplayField();
        macField = createStyledDisplayField();
        osField = createStyledDisplayField();
        sessionDurationField = createStyledDisplayField();

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        fieldsPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        fieldsPanel.add(createFieldLabel("Tên máy:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(deviceNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        fieldsPanel.add(createFieldLabel("MAC:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(macField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        fieldsPanel.add(createFieldLabel("Hệ điều hành:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(osField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        fieldsPanel.add(createFieldLabel("Thời gian phiên:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        fieldsPanel.add(sessionDurationField, gbc);

        WindowsLogoPanel winLogo = new WindowsLogoPanel();

        JPanel topInfoRow = new JPanel(new BorderLayout(12, 0));
        topInfoRow.setOpaque(false);
        topInfoRow.add(fieldsPanel, BorderLayout.CENTER);
        topInfoRow.add(winLogo, BorderLayout.EAST);

        downloadSpeedField = createMetricValueField();
        uploadSpeedField = createMetricValueField();
        todayTrafficField = createMetricValueField();

        JPanel statCards = new JPanel(new GridLayout(1, 3, 10, 0));
        statCards.setOpaque(false);
        statCards.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        statCards.add(createStatMetricCard("Tốc độ tải về", DashboardIcons.downloadCircle(22),
                downloadSpeedField, SparklineWavePanel.blueDownloadWave()));
        statCards.add(createStatMetricCard("Tốc độ tải lên", DashboardIcons.uploadCircle(22),
                uploadSpeedField, SparklineWavePanel.greenUploadWave()));
        statCards.add(createStatMetricCard("Tổng hôm nay", DashboardIcons.trafficCircle(22),
                todayTrafficField, SparklineWavePanel.purpleTrafficWave()));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(topInfoRow);
        content.add(statCards);

        return createCard("Thông tin thiết bị", DashboardIcons.info(16, new Color(0x02, 0x84, 0xC7)),
                renameButton, content);
    }

    private JPanel buildQuotaRuleCard() {
        quotaBar = new JProgressBar(0, 100);
        usageBar = new JProgressBar(0, 100);
        quotaBar.setStringPainted(false);
        usageBar.setStringPainted(false);
        quotaBar.setPreferredSize(new Dimension(160, 14));
        usageBar.setPreferredSize(new Dimension(160, 14));
        quotaBar.setForeground(PRIMARY_ACCENT);
        usageBar.setForeground(new Color(0x94, 0xA3, 0xB8));

        quotaLabel = new JLabel("Thời gian phiên / giới hạn:");
        usageLabel = new JLabel("Thời gian phiên / giới hạn:");
        quotaLabel.setFont(quotaLabel.getFont().deriveFont(Font.PLAIN, 12f));
        usageLabel.setFont(usageLabel.getFont().deriveFont(Font.PLAIN, 12f));
        quotaLabel.setForeground(TEXT_DARK);
        usageLabel.setForeground(TEXT_DARK);

        quotaTextLabel = new JLabel("Không giới hạn dung lượng");
        usageTextLabel = new JLabel("Không giới hạn thời gian");
        quotaTextLabel.setFont(quotaTextLabel.getFont().deriveFont(Font.PLAIN, 12f));
        usageTextLabel.setFont(usageTextLabel.getFont().deriveFont(Font.PLAIN, 12f));
        quotaTextLabel.setForeground(TEXT_DARK);
        usageTextLabel.setForeground(TEXT_DARK);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(quotaLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(quotaBar, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(quotaTextLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(usageLabel, gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(usageBar, gbc);
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(usageTextLabel, gbc);

        return createCard("Hạn mức (Rule)", DashboardIcons.shield(16, PRIMARY_ACCENT),
                null, panel);
    }

    private JPanel buildUserActivityCard() {
        activeAppField = new JTextField("—");
        activeAppField.setEditable(false);

        activeAppBadge = new JLabel("—", SwingConstants.CENTER);
        activeAppBadge.setForeground(PRIMARY_ACCENT);
        activeAppBadge.setFont(activeAppBadge.getFont().deriveFont(Font.BOLD, 12f));
        activeAppBadge.setOpaque(true);
        activeAppBadge.setBackground(new Color(0xE8, 0xF0, 0xFE));
        activeAppBadge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xBF, 0xDB, 0xFE), 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        JLabel filterLabel = new JLabel("Lọc tiến trình/tiêu đề:");
        filterLabel.setForeground(TEXT_DARK);
        filterLabel.setFont(filterLabel.getFont().deriveFont(12f));

        activityFilterField = new JTextField();
        activityFilterField.setToolTipText("Nhập tên tiến trình hoặc tiêu đề cửa sổ...");
        attachSelectAllOnFocus(activityFilterField);
        activityFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyActivityFilter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyActivityFilter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyActivityFilter(); }
        });

        clearActivityButton = createWhiteActionButton("Xóa lịch sử hoạt động", DashboardIcons.trash(14, TEXT_DARK));
        clearActivityButton.addActionListener(e -> clearActivityHistory());

        JButton expandButton = createWhiteActionButton("Xem toàn bộ / Mở rộng", DashboardIcons.grid(14, TEXT_DARK));
        expandButton.addActionListener(e -> openExpandedActivityDialog());

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel searchBox = new JPanel(new BorderLayout(6, 0));
        searchBox.setOpaque(false);
        searchBox.add(filterLabel, BorderLayout.WEST);
        searchBox.add(activityFilterField, BorderLayout.CENTER);

        JPanel toolBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        toolBtns.setOpaque(false);
        toolBtns.add(clearActivityButton);
        toolBtns.add(expandButton);

        toolbar.add(searchBox, BorderLayout.CENTER);
        toolbar.add(toolBtns, BorderLayout.EAST);

        activityTableModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Thời điểm", "Tiến trình", "Tiêu đề cửa sổ", "URL / Domain"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        activityTable = new JTable(activityTableModel);
        activityTable.setRowHeight(28);
        activityTable.setShowVerticalLines(false);
        activityTable.setShowHorizontalLines(true);
        activityTable.setGridColor(new Color(0xE8, 0xEE, 0xF5));
        activityTable.setSelectionBackground(new Color(0xDF, 0xEC, 0xFF));
        activityTable.setSelectionForeground(TEXT_DARK);
        activityTable.getTableHeader().setPreferredSize(new Dimension(0, 30));
        activityTable.getTableHeader().setFont(
                activityTable.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        activityTable.getTableHeader().setForeground(TEXT_DARK);
        activityTable.setFillsViewportHeight(true);
        activityTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        activityTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        activityTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        activityTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        activityTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        activityTable.getColumnModel().getColumn(2).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable table,
                            Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        setText(value == null ? "" : GuiFormat.truncateForDisplay(value.toString(), 85));
                        setToolTipText(value == null ? null : value.toString());
                        return this;
                    }
                });

        activitySorter = new javax.swing.table.TableRowSorter<>(activityTable.getModel());
        activityTable.setRowSorter(activitySorter);
        attachQuickBlockPopup(activityTable);

        JScrollPane tableScroll = new JScrollPane(activityTable);
        tableScroll.setPreferredSize(new Dimension(0, 130));
        tableScroll.setMinimumSize(new Dimension(0, 100));
        tableScroll.setBorder(BorderFactory.createLineBorder(new Color(0xE2, 0xE8, 0xF0), 1));
        tableScroll.getViewport().setBackground(Color.WHITE);

        JLabel quickActionTitle = new JLabel("Điều khiển nhanh (tùy chọn)");
        quickActionTitle.setFont(quickActionTitle.getFont().deriveFont(Font.BOLD, 12f));
        quickActionTitle.setForeground(TEXT_DARK);
        quickActionTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));

        pauseButton = createPillActionButton("Tạm dừng mạng", DashboardIcons.monitor(15, new Color(0x1A, 0x56, 0xDB)),
                new Color(0xEA, 0xF2, 0xFF), new Color(0x90, 0xBB, 0xF8), new Color(0x1A, 0x56, 0xDB));
        resumeButton = createPillActionButton("Khôi phục mạng", DashboardIcons.home(15, new Color(0x64, 0x74, 0x8B)),
                new Color(0xF1, 0xF5, 0xF9), new Color(0xCB, 0xD5, 0xE1), new Color(0x64, 0x74, 0x8B));
        sendAlertButton = createPillActionButton("Gửi cảnh báo", DashboardIcons.power(15, new Color(0x15, 0x80, 0x3D)),
                new Color(0xE6, 0xF7, 0xEC), new Color(0x86, 0xEF, 0xAC), new Color(0x15, 0x80, 0x3D));

        alertMessageField = new JTextField(16);

        pauseButton.addActionListener(event -> runQuickAction(() ->
                ruleEngine.manualPause(selectedClientId(), "Tạm dừng thủ công từ phụ huynh"),
                "đã gửi lệnh TẠM DỪNG mạng"));
        resumeButton.addActionListener(event -> runQuickAction(() ->
                ruleEngine.manualResume(selectedClientId()),
                "đã gửi lệnh KHÔI PHỤC mạng"));
        sendAlertButton.addActionListener(event -> {
            String message = alertMessageField.getText().trim();
            if (message.isEmpty()) {
                message = (String) JOptionPane.showInputDialog(this,
                        "Nhập nội dung cảnh báo gửi tới thiết bị:",
                        "Gửi cảnh báo", JOptionPane.QUESTION_MESSAGE, null, null,
                        "Cảnh báo: Đã quá giờ sử dụng máy tính, vui lòng nghỉ ngơi!");
                if (message == null || message.trim().isEmpty()) {
                    return;
                }
            }
            final String finalMsg = message.trim();
            runQuickAction(() -> ruleEngine.sendAlert(selectedClientId(), finalMsg),
                    "đã gửi cảnh báo: \"" + finalMsg + "\"");
            alertMessageField.setText("");
        });

        JPanel actionBtnsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actionBtnsRow.setOpaque(false);
        actionBtnsRow.add(pauseButton);
        actionBtnsRow.add(resumeButton);
        actionBtnsRow.add(sendAlertButton);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(toolbar);
        content.add(tableScroll);
        content.add(quickActionTitle);
        content.add(actionBtnsRow);

        return createCard("Hoạt động người dùng", DashboardIcons.users(16, PRIMARY_ACCENT),
                activeAppBadge, content);
    }

    private JPanel buildEventLogCard() {
        eventLogArea = new JTextArea(6, 40);
        eventLogArea.setEditable(false);
        eventLogArea.setLineWrap(true);
        eventLogArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        eventLogArea.setBackground(new Color(0x09, 0x12, 0x22));
        eventLogArea.setForeground(new Color(0xCB, 0xD5, 0xE1));
        eventLogArea.setCaretColor(Color.WHITE);
        eventLogArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JScrollPane scrollPane = new JScrollPane(eventLogArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(eventLogArea.getBackground());

        JButton clearLogBtn = createDarkActionButton("Xóa log", DashboardIcons.trash(13, Color.WHITE));
        clearLogBtn.addActionListener(e -> clearEventLog());

        JButton exportLogBtn = createDarkActionButton("Xuất file", DashboardIcons.document(13, Color.WHITE));
        exportLogBtn.addActionListener(e -> exportEventLogToFile());

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerButtons.setOpaque(false);
        headerButtons.add(clearLogBtn);
        headerButtons.add(exportLogBtn);

        JLabel logTitle = new JLabel("Event Log");
        logTitle.setIcon(DashboardIcons.document(16, new Color(0x38, 0xBD, 0xF8)));
        logTitle.setForeground(Color.WHITE);
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD, 13f));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(logTitle, BorderLayout.WEST);
        header.add(headerButtons, BorderLayout.EAST);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JLabel greenDot = new JLabel("●");
        greenDot.setForeground(new Color(0x22, 0xC5, 0x5E));
        greenDot.setFont(greenDot.getFont().deriveFont(14f));
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        bottomRow.setOpaque(false);
        bottomRow.add(greenDot);

        CardPanel card = new CardPanel(16, new Color(0x1E, 0x2E, 0x47));
        card.setBackground(new Color(0x0C, 0x18, 0x2D));
        card.setPreferredSize(new Dimension(0, 150));
        card.setMinimumSize(new Dimension(0, 120));
        card.add(header, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);
        card.add(bottomRow, BorderLayout.SOUTH);

        return card;
    }

    // ------------------------------------------------------------------
    // Helpers tạo UI component chuẩn mực — Vẽ nền trong paintComponent
    // ------------------------------------------------------------------

    private static class CardPanel extends JPanel {
        private final int arc;
        private final Color borderColor;

        CardPanel(int arc, Color borderColor) {
            super(new BorderLayout(0, 8));
            this.arc = arc;
            this.borderColor = borderColor;
            setOpaque(false);
            setBackground(CARD_BG);
            setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                if (borderColor != null) {
                    g2.setColor(borderColor);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static JPanel createCard(String title, Icon icon, JComponent rightComponent, JComponent content) {
        CardPanel card = new CardPanel(16, CARD_BORDER_COLOR);

        JLabel titleLabel = new JLabel(title);
        if (icon != null) {
            titleLabel.setIcon(icon);
            titleLabel.setIconTextGap(8);
        }
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setForeground(TEXT_DARK);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.WEST);
        if (rightComponent != null) {
            header.add(rightComponent, BorderLayout.EAST);
        }
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private static JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT_MUTED);
        label.setFont(label.getFont().deriveFont(12f));
        return label;
    }

    private static JTextField createStyledDisplayField() {
        JTextField field = new JTextField("—");
        field.setEditable(false);
        field.setOpaque(true);
        field.setBackground(new Color(0xF8, 0xFA, 0xFC));
        field.setForeground(TEXT_DARK);
        field.setFont(field.getFont().deriveFont(12f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE2, 0xE8, 0xF0), 1, true),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        return field;
    }

    private static JTextField createMetricValueField() {
        JTextField field = new JTextField("—");
        field.setEditable(false);
        field.setOpaque(false);
        field.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        field.setFont(field.getFont().deriveFont(Font.BOLD, 17f));
        field.setForeground(TEXT_DARK);
        return field;
    }

    private static JPanel createStatMetricCard(String title, Icon icon,
                                               JTextField valueField, SparklineWavePanel wavePanel) {
        CardPanel card = new CardPanel(14, new Color(0xE2, 0xE8, 0xF0));
        card.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));

        JLabel titleLabel = new JLabel(" " + title);
        if (icon != null) {
            titleLabel.setIcon(icon);
        }
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 11f));
        titleLabel.setForeground(TEXT_MUTED);

        card.add(titleLabel, BorderLayout.NORTH);
        card.add(valueField, BorderLayout.CENTER);
        card.add(wavePanel, BorderLayout.SOUTH);
        return card;
    }

    private static class SidebarButton extends JButton {
        private final boolean isPrimaryNav;
        private final boolean active;
        private final Color activeBg;
        private final Color activeBorder;
        private final Color defaultBg;
        private final Color defaultBorder;
        private final Color hoverBg;
        private final Color hoverBorder;
        private final Color defaultFg;
        private final Color activeFg;
        private final Color hoverFg;
        private final int arc;

        SidebarButton(String text, Icon icon, boolean isPrimaryNav, boolean active) {
            super(text);
            this.isPrimaryNav = isPrimaryNav;
            this.active = active;
            this.arc = 10;

            if (icon != null) {
                setIcon(icon);
            }
            setHorizontalAlignment(SwingConstants.LEFT);
            setIconTextGap(12);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            int height = 36;
            setPreferredSize(new Dimension(244, height));
            setMinimumSize(new Dimension(244, height));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            setAlignmentX(Component.CENTER_ALIGNMENT);

            setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

            if (isPrimaryNav) {
                if (active) {
                    this.activeBg = new Color(0x1D, 0x4E, 0xD8);
                    this.activeBorder = new Color(0x3B, 0x82, 0xF6);
                    this.activeFg = Color.WHITE;
                    this.defaultBg = this.activeBg;
                    this.defaultBorder = this.activeBorder;
                    this.defaultFg = this.activeFg;
                    this.hoverBg = new Color(0x25, 0x63, 0xEB);
                    this.hoverBorder = new Color(0x60, 0xA5, 0xFA);
                    this.hoverFg = Color.WHITE;
                    setFont(getFont().deriveFont(Font.BOLD, 12f));
                } else {
                    this.activeBg = null;
                    this.activeBorder = null;
                    this.activeFg = null;
                    this.defaultBg = new Color(0x00, 0x00, 0x00, 0);
                    this.defaultBorder = null;
                    this.defaultFg = new Color(0x94, 0xA3, 0xB8);
                    this.hoverBg = new Color(0x16, 0x2A, 0x46);
                    this.hoverBorder = new Color(0x23, 0x3E, 0x66);
                    this.hoverFg = new Color(0xF1, 0xF5, 0xF9);
                    setFont(getFont().deriveFont(Font.PLAIN, 12f));
                }
            } else {
                this.activeBg = null;
                this.activeBorder = null;
                this.activeFg = null;
                this.defaultBg = new Color(0x13, 0x24, 0x3D);
                this.defaultBorder = new Color(0x1E, 0x37, 0x58);
                this.defaultFg = new Color(0xCB, 0xD5, 0xE1);
                this.hoverBg = new Color(0x1A, 0x32, 0x54);
                this.hoverBorder = new Color(0x2E, 0x52, 0x80);
                this.hoverFg = Color.WHITE;
                setFont(getFont().deriveFont(Font.PLAIN, 12f));
            }

            setForeground(defaultFg);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean isRollover = getModel().isRollover() && isEnabled();
                boolean isPressed = getModel().isPressed() && isEnabled();

                Color bg;
                Color border;
                Color fg;

                if (!isEnabled()) {
                    bg = new Color(0x0C, 0x17, 0x26);
                    border = new Color(0x15, 0x24, 0x38);
                    fg = new Color(0x47, 0x55, 0x69);
                } else if (active) {
                    bg = isPressed ? activeBg.darker() : (isRollover ? hoverBg : activeBg);
                    border = isRollover ? hoverBorder : activeBorder;
                    fg = activeFg;
                } else if (isRollover || isPressed) {
                    bg = isPressed ? hoverBg.darker() : hoverBg;
                    border = hoverBorder;
                    fg = hoverFg;
                } else {
                    bg = defaultBg;
                    border = defaultBorder;
                    fg = defaultFg;
                }

                if (bg != null && bg.getAlpha() > 0) {
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                if (border != null) {
                    g2.setColor(border);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                setForeground(fg);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static JButton createSidebarNavButton(String text, Icon icon, boolean active) {
        return new SidebarButton(text, icon, true, active);
    }

    private static JButton createSidebarActionButton(String text, Icon icon) {
        return new SidebarButton(text, icon, false, false);
    }

    private static JButton createWhiteActionButton(String text, Icon icon) {
        JButton button = new JButton(text);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setForeground(new Color(0x1E, 0x29, 0x3B));
        button.setBackground(Color.WHITE);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(12f));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xCB, 0xD5, 0xE1), 1, true),
                BorderFactory.createEmptyBorder(5, 11, 5, 11)));
        return button;
    }

    private static JButton createDarkActionButton(String text, Icon icon) {
        JButton button = new JButton(text);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(0x18, 0x26, 0x3E));
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(11f));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x28, 0x3E, 0x60), 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        return button;
    }

    private static JButton createPillActionButton(String text, Icon icon, Color bg, Color border, Color fg) {
        JButton button = new JButton(text);
        if (icon != null) {
            button.setIcon(icon);
        }
        button.setForeground(fg);
        button.setBackground(bg);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1, true),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        return button;
    }

    // ------------------------------------------------------------------
    // Renderer danh sách thiết bị
    // ------------------------------------------------------------------

    private static final class DeviceCellRenderer extends DefaultListCellRenderer {
        private static final Color SELECTED_BG = new Color(0xEE, 0xF4, 0xFF);
        private static final Color SELECTED_BORDER = new Color(0xBF, 0xDB, 0xFE);
        private static final Color SELECTED_FG = new Color(0x1D, 0x4E, 0xD8);

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Component component = super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof ClientRecord record && component instanceof JLabel label) {
                boolean online = "ONLINE".equals(record.status());
                String bullet = online ? "●" : "○";
                boolean hasAlias = record.alias() != null && !record.alias().isBlank();
                String name = hasAlias ? record.alias() : record.deviceName();
                String ip = record.ipAddress() != null ? record.ipAddress() : "—";

                label.setText(bullet + "  " + name + " (" + ip + ") — " + (online ? "Online" : "Offline"));
                label.setToolTipText((hasAlias ? record.alias() + " (" + record.deviceName() + ")"
                        : record.deviceName()) + " · " + ip);

                if (selected) {
                    label.setBackground(SELECTED_BG);
                    label.setForeground(SELECTED_FG);
                    label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
                    label.setOpaque(true);
                    label.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(SELECTED_BORDER, 1, true),
                            BorderFactory.createEmptyBorder(6, 10, 6, 10)));
                } else {
                    label.setBackground(Color.WHITE);
                    label.setForeground(online ? new Color(0x16, 0xA3, 0x4A) : TEXT_MUTED);
                    label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
                    label.setOpaque(true);
                    label.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
                }
            }
            return component;
        }
    }

    // ------------------------------------------------------------------
    // Cập nhật trạng thái và chu kỳ dữ liệu
    // ------------------------------------------------------------------

    private void updateHeaderStatus() {
        if (serverStatusLabel != null) {
            boolean running = serverEngine.isRunning();
            serverStatusLabel.setText(running ? "●  Server Online · " + serverEngine.getPort()
                    : "●  Server Offline");
            serverStatusLabel.setForeground(running
                    ? new Color(0x22, 0xC5, 0x5E) : new Color(0xEF, 0x44, 0x44));
        }
        if (headerClockLabel != null) {
            headerClockLabel.setText(java.time.LocalDateTime.now().format(HEADER_TIME_FORMAT));
        }
    }

    private void registerEngineListeners() {
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
                SwingUtilities.invokeLater(() -> {
                    lastActivityTableRefresh = java.time.Instant.EPOCH;
                });
            }

            @Override
            public void onConfigAck(ClientSession session, long revision,
                    String status, String message) {
                SwingUtilities.invokeLater(() -> {
                    if ("APPLIED".equals(status)) {
                        appendEventLog("[OK] Client '" + session.getClientId()
                                + "' đã áp dụng CONFIG_SYNC rev=" + revision);
                    } else {
                        appendEventLog("[!] Client '" + session.getClientId()
                                + "' áp dụng CONFIG_SYNC rev=" + revision + " CHƯA ĐẦY ĐỦ ("
                                + status + "): " + message);
                    }
                });
            }
        });
    }

    private void startUiRefresher() {
        uiRefresher = new Timer(REFRESH_INTERVAL_MS, event -> {
            updateHeaderStatus();
            refreshCenterPanel();
        });
        uiRefresher.start();
    }

    public void stopRefresher() {
        if (uiRefresher != null) {
            uiRefresher.stop();
        }
    }

    private void reloadDeviceList() {
        if (clientDao == null) {
            return;
        }
        String previousSelection = selectedClientId();
        deviceListModel.clear();
        List<ClientRecord> records = clientDao.getAllClients();
        int onlineCount = 0;
        for (ClientRecord record : records) {
            deviceListModel.addElement(record);
            if ("ONLINE".equals(record.status())) {
                onlineCount++;
            }
        }
        if (deviceCountLabel != null) {
            deviceCountLabel.setText(records.size() == 1
                    ? "1 thiết bị kết nối"
                    : records.size() + " thiết bị kết nối (" + onlineCount + " online)");
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
        if (clientDao == null || metricsDao == null || ruleDao == null || sessionManager == null) {
            return;
        }
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null && !deviceListModel.isEmpty()) {
            if (deviceList.getSelectedIndex() < 0) {
                deviceList.setSelectedIndex(0);
            }
            selected = deviceList.getSelectedValue();
        }
        if (selected == null) {
            clearCenterPanel();
            return;
        }
        String clientId = selected.clientId();

        boolean hasAlias = selected.alias() != null && !selected.alias().isBlank();
        deviceNameField.setText(hasAlias
                ? selected.alias() + " (" + selected.deviceName() + ")"
                : orDash(selected.deviceName()));
        macField.setText(orDash(selected.macAddress()));
        osField.setText(selected.osVersion() != null ? selected.osVersion() : "—");

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

        com.parental.common.protocol.payload.ActivityReportPayload activity =
                session != null ? session.getLastActivity() : null;
        if (activity != null && !activity.activeProcessName().isEmpty()) {
            String title = activity.activeWindowTitle();
            String display = activity.activeProcessName()
                    + (title.isEmpty() ? "" : " — " + title);
            activeAppField.setText(display);
            if (activeAppBadge != null) {
                activeAppBadge.setText(GuiFormat.truncateForDisplay(display, 80));
            }
        } else {
            activeAppField.setText("—");
            if (activeAppBadge != null) {
                activeAppBadge.setText("—");
            }
        }

        java.time.Instant now = java.time.Instant.now();
        if (java.time.Duration.between(lastActivityTableRefresh, now).getSeconds() >= 2) {
            lastActivityTableRefresh = now;
            reloadActivityTable(clientId);
        }

        long todayBytes = metricsDao.getTodayTrafficBytes(clientId);
        todayTrafficField.setText(GuiFormat.formatBytes(todayBytes));

        RuleRecord rule = ruleDao.getRule(clientId);
        if (rule != null && rule.dailyTrafficQuotaBytes() > 0) {
            int pct = GuiFormat.clampPercent(todayBytes, rule.dailyTrafficQuotaBytes());
            quotaBar.setValue(pct);
            quotaTextLabel.setText(GuiFormat.formatBytes(todayBytes) + " / "
                    + GuiFormat.formatBytes(rule.dailyTrafficQuotaBytes()));
        } else {
            quotaBar.setValue(0);
            quotaTextLabel.setText("Không giới hạn dung lượng");
        }

        if (rule != null && rule.dailyUsageLimitSeconds() > 0) {
            long todaySeconds = metricsDao.getTodayUsageSeconds(clientId);
            long usedSeconds = todaySeconds > 0 ? todaySeconds
                    : (metrics != null ? metrics.sessionDurationSeconds() : 0);
            int pct = GuiFormat.clampPercent(usedSeconds, rule.dailyUsageLimitSeconds());
            usageBar.setValue(pct);
            usageTextLabel.setText(GuiFormat.formatDuration(usedSeconds) + " / "
                    + GuiFormat.formatDuration(rule.dailyUsageLimitSeconds()));
        } else {
            usageBar.setValue(0);
            usageTextLabel.setText("Không giới hạn thời gian");
        }

        boolean enforced = ruleEngine != null && ruleEngine.isEnforced(clientId);
        if (enforced) {
            quotaBar.setForeground(new Color(0xDC, 0x26, 0x26));
            usageBar.setForeground(new Color(0xDC, 0x26, 0x26));
        } else {
            quotaBar.setForeground(PRIMARY_ACCENT);
            usageBar.setForeground(new Color(0x94, 0xA3, 0xB8));
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
        if (activeAppBadge != null) {
            activeAppBadge.setText("—");
        }
        clearActivityTable();
        quotaBar.setValue(0);
        quotaTextLabel.setText("Chưa chọn thiết bị");
        usageBar.setValue(0);
        usageTextLabel.setText("Chưa chọn thiết bị");
    }

    private void reloadActivityTable(String clientId) {
        if (serverEngine.getActivityDao() == null) {
            return;
        }
        List<ActivityDao.ActivityRecord> records =
                serverEngine.getActivityDao().getRecentActivities(clientId, ACTIVITY_LOG_LIMIT);
        activityTableModel.setRowCount(0);
        for (ActivityDao.ActivityRecord record : records) {
            activityTableModel.addRow(ServerDashboardFrameTestHook.buildActivityTableRow(record));
        }
        activityTable.repaint();
    }

    private void applyActivityFilter() {
        if (activitySorter == null) {
            return;
        }
        activitySorter.setRowFilter(
                ServerDashboardFrameTestHook.buildActivityRowFilter(activityFilterField.getText())
                        .orElse(null));
    }

    private void clearActivityTable() {
        activityTableModel.setRowCount(0);
        if (activityFilterField != null && !activityFilterField.getText().isEmpty()) {
            activityFilterField.setText("");
        }
    }

    private void clearActivityHistory() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để xóa lịch sử hoạt động");
            return;
        }
        String deviceDisplay = displayName(selected);
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
        clearActivityTable();
        activeAppField.setText("—");
        if (activeAppBadge != null) {
            activeAppBadge.setText("—");
        }
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
                        appendEventLog("[OK] Đã xóa toàn bộ lịch sử hoạt động của '"
                                + deviceDisplay + "'");
                    } else {
                        appendEventLog("[!] Xóa lịch sử hoạt động thất bại (lỗi cơ sở dữ liệu)");
                    }
                } catch (Exception e) {
                    appendEventLog("[!] Lỗi khi xóa lịch sử hoạt động: " + e.getMessage());
                    log.error("Clear activity history failed: {}", e.getMessage(), e);
                }
                lastActivityTableRefresh = java.time.Instant.EPOCH;
                refreshNow();
            }
        }.execute();
    }

    private void clearEventLog() {
        if (eventLogArea != null) {
            eventLogArea.setText("");
            appendEventLog("[OK] Đã xóa nhật ký hiển thị");
        }
    }

    private void exportEventLogToFile() {
        if (eventLogArea == null || eventLogArea.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "Nhật ký hiện đang trống!",
                    "Xuất nhật ký", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Lưu nhật ký Event Log");
        fileChooser.setSelectedFile(new File("event_log_"
                + java.time.LocalDate.now() + ".txt"));
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try (FileWriter writer = new FileWriter(fileToSave, StandardCharsets.UTF_8)) {
                writer.write(eventLogArea.getText());
                appendEventLog("[OK] Đã xuất nhật ký ra tệp: " + fileToSave.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "Xuất file thành công!\n"
                        + fileToSave.getAbsolutePath(), "Xuất tệp thành công", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                appendEventLog("[!] Xuất tệp nhật ký thất bại: " + e.getMessage());
                JOptionPane.showMessageDialog(this, "Lỗi khi lưu tệp: " + e.getMessage(),
                        "Lỗi xuất tệp", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ------------------------------------------------------------------
    // Quick Actions & Admin Handlers
    // ------------------------------------------------------------------

    private void runQuickAction(java.util.function.Supplier<String> action, String successMessage) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào");
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
                        appendEventLog("[!] Thao tác thất bại với client '" + deviceName
                                + "' (offline hoặc gửi lỗi)");
                    }
                } catch (Exception e) {
                    appendEventLog("[!] Lỗi thao tác: " + e.getMessage());
                }
                updateQuickActionButtons();
            }
        }.execute();
    }

    private String selectedClientId() {
        ClientRecord selected = deviceList.getSelectedValue();
        return selected != null ? selected.clientId() : null;
    }

    private void updateQuickActionButtons() {
        ClientRecord selected = deviceList.getSelectedValue();
        boolean online = selected != null && "ONLINE".equals(selected.status())
                && ruleEngine != null;
        boolean enforced = selected != null && ruleEngine != null && ruleEngine.isEnforced(selected.clientId());
        if (pauseButton != null) {
            pauseButton.setEnabled(online && !enforced);
        }
        if (resumeButton != null) {
            resumeButton.setEnabled(online && enforced);
        }
        if (sendAlertButton != null) {
            sendAlertButton.setEnabled(online);
        }
        if (alertMessageField != null) {
            alertMessageField.setEnabled(online);
        }
    }

    private void updateDeviceAdminButtons() {
        boolean hasSelection = deviceList != null && deviceList.getSelectedValue() != null;
        if (deleteDeviceButton != null) {
            deleteDeviceButton.setEnabled(hasSelection);
        }
        if (renameButton != null) {
            renameButton.setEnabled(hasSelection);
        }
        if (remoteScreenButton != null) {
            ClientRecord selected = deviceList != null ? deviceList.getSelectedValue() : null;
            remoteScreenButton.setEnabled(selected != null && "ONLINE".equals(selected.status()));
        }
    }

    private void deleteSelectedDevice() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để xóa");
            return;
        }
        String deviceDisplay = displayName(selected);
        int choice = JOptionPane.showConfirmDialog(this,
                "Xóa thiết bị '" + deviceDisplay + "' khỏi danh sách quản lý?\n\n"
                        + "• Thiết bị đang online sẽ bị ngắt kết nối và quay về màn hình ghép đôi\n"
                        + "• Toàn bộ dữ liệu (hạn mức, lịch sử traffic, lệnh) sẽ bị xóa sạch\n\n"
                        + "Thao tác không thể hoàn tác!",
                "Xóa thiết bị",
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
                if (wasOnline) {
                    serverEngine.unpairClient(clientId, UnpairPayload.DEFAULT_REASON);
                }
                return clientDao.deleteClient(clientId)
                        ? "[OK] Đã xóa thiết bị '" + deviceDisplay + "' khỏi danh sách quản lý"
                        : null;
            }

            @Override
            protected void done() {
                try {
                    String msg = get();
                    if (msg != null) {
                        appendEventLog(msg);
                    } else {
                        appendEventLog("[!] Xóa thiết bị thất bại (lỗi cơ sở dữ liệu)");
                    }
                } catch (Exception e) {
                    appendEventLog("[!] Lỗi khi xóa thiết bị: " + e.getMessage());
                }
                refreshNow();
            }
        }.execute();
    }

    private void renameSelectedDevice() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để đổi tên");
            return;
        }
        String input = (String) JOptionPane.showInputDialog(this,
                "Nhập biệt danh mới cho '" + selected.deviceName() + "':\n"
                        + "(để trống rồi bấm OK để xóa biệt danh, về tên máy gốc)",
                "Đổi tên thiết bị",
                JOptionPane.PLAIN_MESSAGE, null, null,
                selected.alias() != null ? selected.alias() : selected.deviceName());
        if (input == null) {
            return;
        }
        String newAlias = input.trim();
        String clientId = selected.clientId();
        String deviceName = selected.deviceName();

        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return clientDao.updateAlias(clientId, newAlias) ? newAlias : null;
            }

            @Override
            protected void done() {
                try {
                    String savedAlias = get();
                    if (savedAlias == null) {
                        appendEventLog("[!] Không lưu được biệt danh (thiết bị không tồn tại trong DB)");
                        return;
                    }
                    appendEventLog(savedAlias.isEmpty()
                            ? "Đã xóa biệt danh của '" + deviceName + "' — về tên máy gốc"
                            : "Đã đặt biệt danh '" + savedAlias + "' cho thiết bị '" + deviceName + "'");
                } catch (Exception e) {
                    appendEventLog("[!] Lỗi khi đổi tên: " + e.getMessage());
                }
                refreshNow();
            }
        }.execute();
    }

    private void openPairingDialog() {
        if (serverEngine.getPairingDao() == null) {
            appendEventLog("[!] Server đang chạy không database — không thể ghép đôi thiết bị");
            return;
        }
        PairingDialog dialog = new PairingDialog(this, serverEngine.getPairingDao(), () -> {
            appendEventLog("Một thiết bị mới đã ghép đôi thành công — có thể kết nối bằng deviceToken");
            refreshNow();
        });
        dialog.open();
    }

    private void openRemoteScreenDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để xem màn hình");
            return;
        }
        if (!"ONLINE".equals(selected.status())) {
            appendEventLog("[!] Thiết bị đang chọn không trực tuyến — không thể xem màn hình");
            return;
        }
        new RemoteScreenDialog(this, selected.clientId(), serverEngine.getClientSessionManager(),
                this::refreshNow);
    }

    private void openRemoteScreenWallDialog() {
        long onlineCount = sessionManager.getAllSessions().stream()
                .filter(ClientSession::isConnected)
                .count();
        if (onlineCount == 0) {
            appendEventLog("[!] Hiện không có máy online để quan sát");
            return;
        }
        new RemoteScreenWallDialog(this, sessionManager, this::refreshNow);
    }

    private void openPowerControlDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để điều khiển nguồn");
            return;
        }
        if (!"ONLINE".equals(selected.status())) {
            appendEventLog("[!] Thiết bị đang chọn không trực tuyến — không thể gửi lệnh nguồn");
            return;
        }
        if (ruleEngine == null) {
            appendEventLog("[!] Server đang chạy không database/rule engine — không thể gửi lệnh");
            return;
        }
        String[] options = {"Khóa màn hình", "Đăng xuất", "Khởi động lại", "Tắt máy", "Hủy"};
        int choice = JOptionPane.showOptionDialog(this,
                "Chọn thao tác cho thiết bị '" + displayName(selected) + "':",
                "Điều khiển nguồn/phiên từ xa",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[options.length - 1]);
        if (choice < 0 || choice >= 4) {
            return;
        }
        ActionType action = switch (choice) {
            case 0 -> ActionType.LOCK_SESSION;
            case 1 -> ActionType.LOGOFF_SESSION;
            case 2 -> ActionType.RESTART_DEVICE;
            default -> ActionType.SHUTDOWN_DEVICE;
        };
        String actionLabel = options[choice];
        boolean destructive = action == ActionType.RESTART_DEVICE || action == ActionType.SHUTDOWN_DEVICE;
        int confirm = JOptionPane.showConfirmDialog(this,
                "Gửi lệnh '" + actionLabel + "' tới '" + displayName(selected) + "'?\n\n"
                        + "• Thiết bị: " + selected.deviceName() + " (" + selected.ipAddress() + ")\n"
                        + "• Hành động: " + actionLabel + "\n"
                        + (destructive ? "• Khởi động lại/tắt máy có 5 giây để hủy trên máy con.\n" : "")
                        + "• Lệnh được ghi audit trong command_logs.\n\n"
                        + "Chỉ tiếp tục nếu bạn là phụ huynh/người quản lý thiết bị.",
                "Xác nhận " + actionLabel,
                JOptionPane.YES_NO_OPTION,
                destructive ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        String clientId = selected.clientId();
        String deviceName = displayName(selected);
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return ruleEngine.sendPowerCommand(clientId, action, actionLabel
                        + " requested by parent from Dashboard");
            }

            @Override
            protected void done() {
                try {
                    String commandId = get();
                    if (commandId != null) {
                        appendEventLog("Đã gửi lệnh '" + actionLabel + "' tới '" + deviceName
                                + "' (commandId=" + commandId.substring(0, 8) + "..., chờ ACK từ client)");
                    } else {
                        appendEventLog("[!] Gửi lệnh '" + actionLabel + "' thất bại — client offline hoặc từ chối");
                    }
                } catch (Exception e) {
                    appendEventLog("[!] Lỗi gửi lệnh nguồn: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void openUsageChartDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để xem thống kê");
            return;
        }
        String label = displayName(selected);
        new UsageChartDialog(this, selected.clientId(), label, metricsDao,
                serverEngine.getActivityDao(), java.time.ZoneId.systemDefault())
                .setVisible(true);
    }

    private void openAboutDialog() {
        JOptionPane.showMessageDialog(this,
                "Parental Network Control — Hệ thống Quản lý Mạng Gia đình / Văn phòng\n\n"
                        + "• Phiên bản: 1.0.0 (Java Swing Desktop Edition)\n"
                        + "• Cổng TCP lắng nghe: " + serverEngine.getPort() + "\n"
                        + "• Giao thức: LDJSON socket qua TCP với Auto-Reconnect và Quota Engine\n"
                        + "• Bảo vệ & Giám sát: OSHI metrics, Hosts Enforcement, App Blocker, Live Stream\n\n"
                        + "© 2026 Nhóm Đồ án Lập trình mạng — Đại học",
                "Giới thiệu hệ thống", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openPolicyDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để cấu hình");
            return;
        }
        PolicyConfigDialog dialog = new PolicyConfigDialog(this, selected, ruleDao, () -> {
            boolean pushed = ruleEngine != null
                    && ruleEngine.syncConfigToClient(selected.clientId());
            appendEventLog(pushed
                    ? "Đã đẩy CONFIG_SYNC xuống client '" + selected.deviceName() + "'"
                    : "[!] Không đồng bộ được tới client '" + selected.deviceName()
                            + "' (offline) — cấu hình đã lưu trong DB");
            refreshNow();
        });
        dialog.setVisible(true);
    }

    private void openExpandedActivityDialog() {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để xem nhật ký");
            return;
        }
        ActivityDao activityDao = serverEngine.getActivityDao();
        if (activityDao == null) {
            appendEventLog("[!] Server đang chạy không database — không có nhật ký hoạt động");
            return;
        }
        String clientId = selected.clientId();
        String deviceDisplay = displayName(selected);

        JDialog dialog = new JDialog(this, "Nhật ký hoạt động đầy đủ — " + deviceDisplay, true);
        dialog.setSize(1000, 650);
        dialog.setResizable(true);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.getContentPane().setBackground(CANVAS_BG);

        javax.swing.table.DefaultTableModel fullModel = new javax.swing.table.DefaultTableModel(
                new Object[] {"Thời điểm", "Tiến trình", "Tiêu đề cửa sổ", "URL / Domain"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable fullTable = new JTable(fullModel);
        fullTable.setRowHeight(30);
        fullTable.setFillsViewportHeight(true);
        fullTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        fullTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        fullTable.getColumnModel().getColumn(2).setPreferredWidth(500);
        fullTable.getColumnModel().getColumn(3).setPreferredWidth(190);
        fullTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> fullSorter =
                new javax.swing.table.TableRowSorter<>(fullModel);
        fullTable.setRowSorter(fullSorter);
        attachQuickBlockPopup(fullTable);

        JScrollPane fullScroll = new JScrollPane(fullTable);
        fullScroll.setBorder(BorderFactory.createLineBorder(new Color(0xD8, 0xE2, 0xEE), 1));

        Runnable reloadFullTable = () -> {
            List<ActivityDao.ActivityRecord> records = activityDao
                    .getRecentActivitiesForDialog(clientId,
                            ServerDashboardFrameTestHook.DIALOG_ROW_LIMIT);
            fullModel.setRowCount(0);
            for (ActivityDao.ActivityRecord record : records) {
                fullModel.addRow(ServerDashboardFrameTestHook.buildActivityTableRow(record));
            }
        };
        reloadFullTable.run();

        ClientConnectionListener dialogAutoRefreshListener = new ClientConnectionListener() {
            volatile long lastAutoRefresh = 0;

            @Override
            public void onActivityReport(ClientSession session,
                    com.parental.common.protocol.payload.ActivityReportPayload activity) {
                if (!clientId.equals(session.getClientId()) || !dialog.isShowing()) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastAutoRefresh < ACTIVITY_DIALOG_AUTO_REFRESH_MIN_MS) {
                    return;
                }
                lastAutoRefresh = now;
                SwingUtilities.invokeLater(reloadFullTable);
            }
        };
        sessionManager.addConnectionListener(dialogAutoRefreshListener);

        JTextField fullFilterField = new JTextField();
        fullFilterField.setToolTipText("Lọc theo tiến trình hoặc tiêu đề...");
        attachSelectAllOnFocus(fullFilterField);
        fullFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFullDialogFilter(fullFilterField, fullSorter); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFullDialogFilter(fullFilterField, fullSorter); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFullDialogFilter(fullFilterField, fullSorter); }
        });

        JButton refreshButton = createWhiteActionButton("Làm mới", DashboardIcons.grid(14, TEXT_DARK));
        refreshButton.addActionListener(event -> reloadFullTable.run());
        JButton closeButton = createPillActionButton("Đóng", null, PRIMARY_ACCENT, PRIMARY_ACCENT, Color.WHITE);
        closeButton.addActionListener(event -> dialog.dispose());

        JPanel controlRow = new JPanel(new BorderLayout(8, 0));
        controlRow.setOpaque(true);
        controlRow.setBackground(Color.WHITE);
        controlRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD8, 0xE2, 0xEE), 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        controlRow.add(new JLabel("Lọc tiến trình/tiêu đề:"), BorderLayout.WEST);
        controlRow.add(fullFilterField, BorderLayout.CENTER);
        JPanel dialogButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        dialogButtons.setOpaque(false);
        dialogButtons.add(refreshButton);
        dialogButtons.add(closeButton);
        controlRow.add(dialogButtons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBackground(CANVAS_BG);
        content.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        content.add(controlRow, BorderLayout.NORTH);
        content.add(fullScroll, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                sessionManager.removeConnectionListener(dialogAutoRefreshListener);
            }
        });
        dialog.setVisible(true);
    }

    private static void attachSelectAllOnFocus(javax.swing.text.JTextComponent field) {
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                field.selectAll();
            }
        });
    }

    private static void applyFullDialogFilter(JTextField filterField,
            javax.swing.table.TableRowSorter<? extends javax.swing.table.TableModel> sorter) {
        sorter.setRowFilter(ServerDashboardFrameTestHook
                .buildActivityRowFilter(filterField.getText()).orElse(null));
    }

    // ------------------------------------------------------------------
    // Context Menu Chặn Nhanh
    // ------------------------------------------------------------------

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

    private void maybeShowQuickBlockPopup(JTable table, java.awt.event.MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(event.getPoint());
        if (viewRow < 0) {
            return;
        }
        table.setRowSelectionInterval(viewRow, viewRow);
        int modelRow = table.convertRowIndexToModel(viewRow);
        String processName = String.valueOf(table.getModel().getValueAt(modelRow,
                ServerDashboardFrameTestHook.ACTIVITY_COLUMN_PROCESS));
        String title = String.valueOf(table.getModel().getValueAt(modelRow,
                ServerDashboardFrameTestHook.ACTIVITY_COLUMN_TITLE));
        String urlOrDomain = ServerDashboardFrameTestHook.valueOfCell(table.getModel(),
                modelRow, ServerDashboardFrameTestHook.ACTIVITY_COLUMN_URL);

        JPopupMenu popup = new JPopupMenu();
        String suggestedDomain = WindowTitleDomainExtractor.suggestDomain(urlOrDomain, title);

        if (suggestedDomain != null) {
            JMenuItem blockDomainItem = new JMenuItem(
                    ServerDashboardFrameTestHook.buildQuickBlockLabel(suggestedDomain));
            blockDomainItem.addActionListener(actionEvent ->
                    quickBlockDomainForClient(suggestedDomain, true));
            popup.add(blockDomainItem);
            JMenuItem editDomainItem = new JMenuItem("Chặn tên miền khác (sửa gợi ý)...");
            editDomainItem.addActionListener(actionEvent ->
                    quickBlockDomainForClient(suggestedDomain, false));
            popup.add(editDomainItem);
        }

        if (processName != null && !processName.isBlank() && !processName.equals("—")) {
            ClientRecord selectedDevice = deviceList.getSelectedValue();
            boolean alreadyBlocked = selectedDevice != null
                    && serverEngine.getRuleDao() != null
                    && serverEngine.getRuleDao()
                            .getBlockedProcesses(selectedDevice.clientId()).stream()
                            .anyMatch(name -> name.equalsIgnoreCase(processName.trim())
                                    || name.equalsIgnoreCase(processName.trim() + ".exe"));
            if (alreadyBlocked) {
                JMenuItem unblockProcessItem = new JMenuItem(
                        ServerDashboardFrameTestHook.buildQuickUnblockProcessLabel(processName));
                unblockProcessItem.addActionListener(actionEvent ->
                        quickUnblockProcessForClient(processName));
                popup.add(unblockProcessItem);
            } else {
                JMenuItem blockProcessItem = new JMenuItem(
                        ServerDashboardFrameTestHook.buildQuickBlockProcessLabel(processName));
                blockProcessItem.addActionListener(actionEvent ->
                        quickBlockProcessForClient(processName));
                popup.add(blockProcessItem);
            }
        }

        if (popup.getComponentCount() > 0) {
            popup.show(table, event.getX(), event.getY());
        }
    }

    private void quickBlockDomainForClient(String suggestedDomain, boolean directConfirm) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để chặn tên miền");
            return;
        }
        BlockedDomainDao blockedDomainDao = serverEngine.getBlockedDomainDao();
        if (blockedDomainDao == null) {
            appendEventLog("[!] Server đang chạy không database — không chặn tên miền được");
            return;
        }
        String input = suggestedDomain;
        if (!directConfirm || input == null || input.isBlank()) {
            input = (String) JOptionPane.showInputDialog(this,
                    "Tên miền cần chặn cho thiết bị '" + selected.deviceName() + "':",
                    "Chặn tên miền này",
                    JOptionPane.PLAIN_MESSAGE, null, null,
                    suggestedDomain != null ? suggestedDomain : "");
            if (input == null || input.isBlank()) {
                return;
            }
        }
        String domain = BlockedDomainDialogHelper.normalize(input);
        if (domain == null) {
            JOptionPane.showMessageDialog(this,
                    "Tên miền không hợp lệ: " + input + "\n(ví dụ hợp lệ: youtube.com, facebook.com)",
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
        String deviceDisplay = displayName(selected);
        BlockedDomainDao.AddResult result = blockedDomainDao.addDomain(clientId, domain);
        if (result == BlockedDomainDao.AddResult.FAILED) {
            appendEventLog("[!] Không chặn được '" + domain + "' cho '"
                    + deviceDisplay + "' (lỗi cơ sở dữ liệu)");
            return;
        }
        boolean pushed = serverEngine.pushConfigSync(clientId);
        if (result == BlockedDomainDao.AddResult.ALREADY_PRESENT) {
            appendEventLog("Tên miền '" + domain + "' đã có trong danh sách chặn của '"
                    + deviceDisplay + "' — đang đồng bộ lại xuống client "
                    + (pushed ? "thành công" : "THẤT BẠI (client offline — sẽ nhận khi kết nối lại)"));
        } else {
            appendEventLog("Đã chặn '" + domain + "' cho '" + deviceDisplay + "'"
                    + (pushed ? " — CONFIG_SYNC đã đẩy xuống client"
                              : " — client offline, sẽ nhận khi kết nối lại"));
        }
    }

    private void quickBlockProcessForClient(String processName) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để chặn tiến trình");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn chặn tiến trình [" + processName + "] cho thiết bị này?\n\n"
                        + "Tiến trình sẽ bị kết thúc ngay và bị chặn vĩnh viễn.",
                "Xác nhận chặn tiến trình",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        String clientId = selected.clientId();
        String deviceDisplay = displayName(selected);
        boolean saved = serverEngine.getRuleDao() != null
                && serverEngine.getRuleDao().addBlockedProcess(clientId, processName);
        String commandId = ruleEngine != null
                ? ruleEngine.blockProcess(clientId, processName)
                : null;
        if (commandId != null) {
            appendEventLog("Đã gửi lệnh chặn tiến trình '" + processName + "' cho '"
                    + deviceDisplay + "' (client sẽ kill ngay và chặn vĩnh viễn)");
        } else {
            appendEventLog("[!] Không gửi được lệnh chặn tiến trình '" + processName
                    + "' cho '" + deviceDisplay + "' (client offline)"
                    + (saved ? " — đã lưu DB, client nhận khi đồng bộ sau" : ""));
        }
    }

    private void quickUnblockProcessForClient(String processName) {
        ClientRecord selected = deviceList.getSelectedValue();
        if (selected == null) {
            appendEventLog("[!] Chưa chọn thiết bị nào để bỏ chặn tiến trình");
            return;
        }
        if (serverEngine.getRuleDao() == null) {
            appendEventLog("[!] Server đang chạy không database — không bỏ chặn được");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc muốn BỎ chặn tiến trình [" + processName + "] cho thiết bị này?\n\n"
                        + "Ứng dụng sẽ mở lại bình thường sau khi bỏ chặn.",
                "Xác nhận bỏ chặn tiến trình",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        String clientId = selected.clientId();
        String deviceDisplay = displayName(selected);
        boolean removed = serverEngine.getRuleDao().removeBlockedProcess(clientId, processName);
        if (!removed) {
            appendEventLog("[!] Không gỡ được '" + processName + "' (không có trong danh sách chặn)");
            return;
        }
        boolean pushed = ruleEngine != null && ruleEngine.syncConfigToClient(clientId);
        appendEventLog("Đã BỎ chặn tiến trình '" + processName + "' cho '"
                + deviceDisplay + "'"
                + (pushed ? " — client ngừng kill, app mở lại bình thường"
                          : " — client offline, sẽ nhận khi kết nối lại"));
    }

    private static String displayName(ClientRecord record) {
        return record.alias() != null && !record.alias().isBlank()
                ? record.alias() + " (" + record.deviceName() + ")"
                : record.deviceName();
    }

    private static String orDash(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    private void appendEventLog(String message) {
        if (eventLogArea != null) {
            eventLogArea.append("[" + LocalTime.now().format(TIME_FORMAT) + "] " + message + "\n");
            eventLogArea.setCaretPosition(eventLogArea.getDocument().getLength());
        }
    }

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

    public void showFrame() {
        setVisible(true);
    }
}
