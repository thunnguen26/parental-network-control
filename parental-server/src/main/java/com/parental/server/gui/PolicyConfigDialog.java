package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

import com.parental.common.protocol.payload.ConfigSyncPayload;
import com.parental.server.db.ClientRecord;
import com.parental.server.db.RuleDao;
import com.parental.server.db.RuleDao.RuleRecord;

/**
 * Dialog cấu hình chính sách cho MỘT client (Task 5.2):
 * <ul>
 *   <li>Quản lý Blocked Domains — danh sách + Thêm/Xóa;</li>
 *   <li>Giới hạn thời gian dùng trong ngày — spinner GIỜ (0–24);</li>
 *   <li>Giới hạn dung lượng trong ngày — spinner GB (0–1024);</li>
 *   <li>Khung giờ cho phép — spinner Giờ:Phút bắt đầu/kết thúc (HH:mm);</li>
 *   <li>[Lưu &amp; Đồng bộ ngay]: {@code RuleDao.upsertRule} + {@code setBlockedDomains}
 *       → callback đẩy {@code CONFIG_SYNC} xuống client → đóng dialog.</li>
 * </ul>
 *
 * <p>Giá trị 0 = không giới hạn; thời gian rỗng (bỏ tick) = không giới hạn giờ.
 * Chỉ đụng {@link RuleDao} — việc gửi xuống client do caller quyết định qua
 * {@code afterSaveCallback} (giữ separation of concerns: dialog không biết
 * gì về network engine).</p>
 */
final class PolicyConfigDialog extends JDialog {

    /** Số phút trong 1 giờ (chuyển đổi spinner giờ:phút ↔ giây). */
    static final int SECONDS_PER_HOUR = 3600;

    /** Số byte trong 1 GB (chuyển đổi spinner GB ↔ bytes, nhị phân). */
    static final long BYTES_PER_GB = 1024L * 1024 * 1024;

    /** Danh mục mẫu: Web người lớn / Độc hại (Domain Blacklist Management). */
    static final java.util.List<String> PRESET_ADULT_HARMFUL = java.util.List.of(
            "xvideos.com", "pornhub.com", "xhamster.com", "xnxx.com",
            "bet88.com", "f8bet.com", "sunwin.com", "789bet.com",
            "shbet.com", "hello88.com");

    /** Danh mục mẫu: Mạng xã hội & Video. */
    static final java.util.List<String> PRESET_SOCIAL_VIDEO = java.util.List.of(
            "tiktok.com", "facebook.com", "youtube.com", "instagram.com",
            "snapchat.com", "x.com", "reddit.com");

    private final RuleDao ruleDao;
    private final ClientRecord client;
    private final Runnable afterSaveCallback;

    private DefaultListModel<String> domainListModel;
    private JTextField domainField;
    private JSpinner usageHoursSpinner;
    private JSpinner quotaGbSpinner;
    private JSpinner startHourSpinner;
    private JSpinner startMinuteSpinner;
    private JSpinner endHourSpinner;
    private JSpinner endMinuteSpinner;

    /** Dùng chung các spinner giờ:phút cho khung bắt đầu/kết thúc. */
    private record TimeWindow(JSpinner hour, JSpinner minute) {
        String toHHmm() {
            return String.format("%02d:%02d", (Integer) hour.getValue(), (Integer) minute.getValue());
        }
    }

    PolicyConfigDialog(ServerDashboardFrame owner, ClientRecord client, RuleDao ruleDao,
                       Runnable afterSaveCallback) {
        super(owner != null ? owner : null, "Cấu hình chính sách — " + client.deviceName(), true);
        this.ruleDao = ruleDao;
        this.client = client;
        this.afterSaveCallback = afterSaveCallback;
        loadCurrentRule();
        initUi();
        pack();
        setLocationRelativeTo(owner);
    }

    private void loadCurrentRule() {
        RuleRecord rule = ruleDao.getRule(client.clientId());
        usageHoursSpinner = new JSpinner(new SpinnerNumberModel(
                rule != null ? (int) Math.min(24, rule.dailyUsageLimitSeconds() / SECONDS_PER_HOUR) : 0,
                0, 24, 1));
        quotaGbSpinner = new JSpinner(new SpinnerNumberModel(
                rule != null ? (int) Math.min(1024, rule.dailyTrafficQuotaBytes() / BYTES_PER_GB) : 0,
                0, 1024, 1));
        int[] start = parseHHmm(rule != null ? rule.allowedStartTime() : null);
        int[] end = parseHHmm(rule != null ? rule.allowedEndTime() : null);
        startHourSpinner = new JSpinner(new SpinnerNumberModel(start[0], 0, 23, 1));
        startMinuteSpinner = new JSpinner(new SpinnerNumberModel(start[1], 0, 59, 1));
        endHourSpinner = new JSpinner(new SpinnerNumberModel(end[0], 0, 23, 1));
        endMinuteSpinner = new JSpinner(new SpinnerNumberModel(end[1], 0, 59, 1));
    }

    /** Parse "HH:mm" → [hour, minute]; null/hỏng → [0, 0]. */
    static int[] parseHHmm(String text) {
        if (text != null) {
            try {
                String[] parts = text.trim().split(":");
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            } catch (RuntimeException e) {
                // fall through
            }
        }
        return new int[]{0, 0};
    }

    private void initUi() {
        setLayout(new BorderLayout(0, 8));

        // --- Blocked domains (Domain Blacklist Management) ---
        domainListModel = new DefaultListModel<>();
        for (String domain : ruleDao.getBlockedDomains(client.clientId())) {
            domainListModel.addElement(domain);
        }
        JList<String> domainList = new JList<>(domainListModel);
        domainList.setVisibleRowCount(6);
        domainField = new JTextField(18);
        JButton addButton = new JButton("\u2795 Th\u00EAm");
        JButton removeButton = new JButton("\uD83D\uDDD1 X\u00F3a");
        addButton.addActionListener(event -> addDomainFromField());
        removeButton.addActionListener(event -> {
            int index = domainList.getSelectedIndex();
            if (index >= 0) {
                domainListModel.remove(index);
            }
        });

        // Nút tiện ích: nạp danh mục mẫu (preset blacklist)
        JButton presetAdultButton = new JButton("N\u1EA1p: Web ng\u01B0\u1EDDi l\u1EDBn / \u0110\u1ED9c h\u1EA1i");
        presetAdultButton.addActionListener(event ->
                mergePresetIntoList(PRESET_ADULT_HARMFUL));
        JButton presetSocialButton = new JButton("N\u1EA1p: M\u1EA1ng x\u00E3 h\u1ED9i & Video");
        presetSocialButton.addActionListener(event ->
                mergePresetIntoList(PRESET_SOCIAL_VIDEO));

        JPanel domainsPanel = new JPanel(new BorderLayout(0, 4));
        domainsPanel.setBorder(BorderFactory.createTitledBorder(
                "Qu\u1EA3n l\u00FD Ch\u1EB7n T\u00EAn Mi\u1EC1n (Blacklist)"));
        domainsPanel.add(new JScrollPane(domainList), BorderLayout.CENTER);
        JPanel domainEditPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainEditPanel.add(domainField);
        domainEditPanel.add(addButton);
        domainEditPanel.add(removeButton);
        JPanel presetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        presetPanel.add(new JLabel("Danh m\u1EE5c m\u1EABu:"));
        presetPanel.add(presetAdultButton);
        presetPanel.add(presetSocialButton);
        JPanel domainsSouth = new JPanel(new BorderLayout(0, 2));
        domainsSouth.add(domainEditPanel, BorderLayout.NORTH);
        domainsSouth.add(presetPanel, BorderLayout.SOUTH);
        domainsPanel.add(domainsSouth, BorderLayout.SOUTH);

        // --- Hạn mức + khung giờ ---
        JPanel limitsPanel = new JPanel(new GridBagLayout());
        limitsPanel.setBorder(BorderFactory.createTitledBorder("Hạn mức sử dụng"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        limitsPanel.add(new JLabel("Giới hạn thời gian (giờ/ngày, 0 = không giới hạn):"), gbc);
        gbc.gridx = 1;
        limitsPanel.add(usageHoursSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        limitsPanel.add(new JLabel("Giới hạn dung lượng (GB/ngày, 0 = không giới hạn):"), gbc);
        gbc.gridx = 1;
        limitsPanel.add(quotaGbSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        limitsPanel.add(new JLabel("Khung giờ cho phép (bắt đầu — kết thúc, HH:mm):"), gbc);
        JPanel startPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        startPanel.add(startHourSpinner);
        startPanel.add(new JLabel(":"));
        startPanel.add(startMinuteSpinner);
        JPanel endPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        endPanel.add(endHourSpinner);
        endPanel.add(new JLabel(":"));
        endPanel.add(endMinuteSpinner);
        JPanel schedulePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        schedulePanel.add(startPanel);
        schedulePanel.add(new JLabel("—"));
        schedulePanel.add(endPanel);
        gbc.gridx = 1;
        limitsPanel.add(schedulePanel, gbc);

        // --- Nút lưu ---
        JButton saveButton = new JButton("💾 Lưu & Đồng bộ ngay");
        saveButton.addActionListener(event -> saveAndSync());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(saveButton);

        add(domainsPanel, BorderLayout.NORTH);
        add(limitsPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Thêm domain từ ô nhập vào danh sách: chuẩn hóa (trim + lowercase + bỏ
     * {@code http://}/{@code https://}/{@code /}/{port}/{www.}) qua quy tắc
     * của {@code BlockedDomainDao.normalizeDomain}; không hợp lệ → dialog nhẹ.
     */
    private void addDomainFromField() {
        String normalized = BlockedDomainDialogHelper.normalize(domainField.getText());
        if (normalized != null && !domainListModel.contains(normalized)) {
            domainListModel.addElement(normalized);
        } else if (normalized == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Tên miền không hợp lệ. Ví dụ: facebook.com hoặc https://www.facebook.com/",
                    "Domain không hợp lệ", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        domainField.setText("");
    }

    /** Gộp danh mục mẫu vào danh sách hiện hành (khử trùng lặp, giữ domain đã có). */
    private void mergePresetIntoList(java.util.List<String> preset) {
        for (String domain : preset) {
            if (!domainListModel.contains(domain)) {
                domainListModel.addElement(domain);
            }
        }
    }

    /** Lưu rule + domains vào DB, đẩy CONFIG_SYNC qua callback, đóng dialog. */
    private void saveAndSync() {
        long usageSeconds = (Integer) usageHoursSpinner.getValue() * (long) SECONDS_PER_HOUR;
        long quotaBytes = (Integer) quotaGbSpinner.getValue() * BYTES_PER_GB;
        String startTime = new TimeWindow(startHourSpinner, startMinuteSpinner).toHHmm();
        String endTime = new TimeWindow(endHourSpinner, endMinuteSpinner).toHHmm();

        List<String> domains = new ArrayList<>();
        for (int i = 0; i < domainListModel.size(); i++) {
            domains.add(domainListModel.get(i));
        }

        // Cả hai spinner đều được cấu hình → luôn có khung giờ; chỉ bỏ khi
        // người dùng đặt 00:00 cho CẢ bắt đầu và kết thúc (00:00–00:00 = cả ngày)
        String allowedStart = startTime;
        String allowedEnd = endTime;
        if ("00:00".equals(startTime) && "00:00".equals(endTime)) {
            allowedStart = null; // quy ước "không giới hạn giờ" trong DB
            allowedEnd = null;
        }

        ConfigSyncPayload config = new ConfigSyncPayload(
                domains, usageSeconds, quotaBytes, allowedStart, allowedEnd);
        boolean saved = ruleDao.upsertRule(client.clientId(), config);
        if (!saved) {
            // Client chưa tồn tại trong bảng clients (FK) — báo lỗi, không đóng
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Không lưu được cấu hình (client chưa có trong DB?)",
                    "Lỗi", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (afterSaveCallback != null) {
            afterSaveCallback.run();
        }
        dispose();
    }
}
