package com.parental.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.server.db.ActivityDao;
import com.parental.server.db.MetricsDao;
import static com.parental.ui.DesktopTheme.CANVAS;
import static com.parental.ui.DesktopTheme.CARD_BORDER;
import static com.parental.ui.DesktopTheme.TEXT_PRIMARY;
import static com.parental.ui.DesktopTheme.roundedBorder;
import static com.parental.ui.DesktopTheme.roundedTitledBorder;

/**
 * Dialog thống kê thời gian sử dụng (Tính năng 3.1) — biểu đồ ngày + tổng
 * tuần + bảng "số lần ghi nhận theo ứng dụng".
 *
 * <p>Dữ liệu lấy từ {@code metrics_daily_usage} (đã là delta/ngày) và
 * {@code activity_logs} (chỉ đếm sự kiện, KHÔNG suy ra thời lượng). Múi giờ,
 * khoảng thời gian, nguồn và giới hạn dữ liệu được ghi rõ ngay trên dialog;
 * ngày trống hiển thị 0 thay vì nội suy.</p>
 */
public final class UsageChartDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(UsageChartDialog.class);

    private static final Integer[] PERIOD_DAYS = {7, 14, 30};

    private final String clientId;
    private final String deviceLabel;
    private final MetricsDao metricsDao;
    private final ActivityDao activityDao;
    private final ZoneId zone;

    private final JComboBox<Integer> periodCombo =
            new JComboBox<>(PERIOD_DAYS);
    private final JButton refreshButton = new JButton("Làm mới");
    private final JLabel summaryLabel = new JLabel("Đang tải dữ liệu...");
    private final JLabel weeklyLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final UsageBarChartPanel chartPanel = new UsageBarChartPanel();
    private final DefaultTableModel appsModel = new DefaultTableModel(
            new Object[]{"Ứng dụng", "Số lần ghi nhận"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable appsTable = new JTable(appsModel);

    public UsageChartDialog(JFrame owner, String clientId, String deviceLabel,
                            MetricsDao metricsDao, ActivityDao activityDao, ZoneId zone) {
        super(owner, "Thống kê sử dụng - " + deviceLabel, false);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.deviceLabel = deviceLabel;
        this.metricsDao = Objects.requireNonNull(metricsDao, "metricsDao");
        this.activityDao = activityDao; // nullable → bảng ứng dụng hiện "không khả dụng"
        this.zone = zone != null ? zone : ZoneId.systemDefault();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(880, 660);
        setMinimumSize(new Dimension(700, 520));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(CANVAS);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.setOpaque(false);
        top.setBackground(Color.WHITE);
        top.setBorder(roundedBorder(Color.WHITE, CARD_BORDER, 16,
                new java.awt.Insets(8, 12, 8, 12)));
        top.add(new JLabel("Khoảng thời gian:"));
        top.add(periodCombo);
        top.add(refreshButton);
        top.add(new JLabel("Múi giờ: " + this.zone));
        add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBackground(CANVAS);
        center.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        JPanel summaryPanel = new JPanel(new BorderLayout(0, 2));
        summaryPanel.setOpaque(false);
        summaryPanel.setBackground(Color.WHITE);
        summaryPanel.setBorder(roundedBorder(Color.WHITE, CARD_BORDER, 16,
                new java.awt.Insets(10, 12, 10, 12)));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        summaryLabel.setForeground(TEXT_PRIMARY);
        weeklyLabel.setFont(weeklyLabel.getFont().deriveFont(Font.PLAIN, 12f));
        statusLabel.setForeground(new java.awt.Color(0x9A, 0x34, 0x12));
        summaryPanel.add(summaryLabel, BorderLayout.NORTH);
        summaryPanel.add(weeklyLabel, BorderLayout.CENTER);
        summaryPanel.add(statusLabel, BorderLayout.SOUTH);
        center.add(summaryPanel, BorderLayout.NORTH);
        center.add(chartPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 6));
        bottom.setOpaque(false);
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(roundedTitledBorder("ỨNG DỤNG XUẤT HIỆN NHIỀU NHẤT",
                Color.WHITE, CARD_BORDER, 16, new java.awt.Insets(26, 10, 10, 10)));
        appsTable.setFillsViewportHeight(true);
        appsTable.setRowHeight(28);
        bottom.add(new JScrollPane(appsTable), BorderLayout.CENTER);
        JLabel note = new JLabel("<html>Nguồn: <b>metrics_daily_usage</b> (tổng delta theo ngày, không cộng trùng snapshot) "
                + "và <b>activity_logs</b> (chỉ ghi khi cửa sổ đổi). "
                + "Ngày không có dữ liệu = 0, không nội suy. "
                + "<i>Nhật ký ứng dụng không đủ mẫu để tính thời lượng nên chỉ hiển thị số lần.</i></html>");
        note.setFont(note.getFont().deriveFont(Font.PLAIN, 11f));
        bottom.add(note, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        refreshButton.addActionListener(event -> loadData());
        periodCombo.addActionListener(event -> loadData());
        loadData();
    }

    /** Dữ liệu tải xong trong 1 lần (SwingWorker tránh block EDT khi DAO chậm). */
    private record LoadedData(UsageStatistics.Snapshot snapshot,
                              List<ActivityDao.ProcessCount> processes,
                              LocalDate today) {
    }

    private void loadData() {
        int days = (Integer) periodCombo.getSelectedItem();
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(days - 1L);
        refreshButton.setEnabled(false);
        periodCombo.setEnabled(false);
        statusLabel.setText(" ");

        new SwingWorker<LoadedData, Void>() {
            @Override
            protected LoadedData doInBackground() {
                List<MetricsDao.DailyUsage> usage = metricsDao.getDailyUsage(clientId, from, today);
                UsageStatistics.Snapshot snapshot =
                        UsageStatistics.build(usage, from, today, today, zone);
                List<ActivityDao.ProcessCount> processes = activityDao != null
                        ? activityDao.countByProcess(clientId, from, today, 10)
                        : List.of();
                return new LoadedData(snapshot, processes, today);
            }

            @Override
            protected void done() {
                try {
                    LoadedData loaded = get();
                    apply(loaded.snapshot(), loaded.processes(), loaded.today());
                } catch (Exception e) {
                    log.error("Failed to load usage statistics for [{}]: {}",
                            clientId, e.getMessage(), e);
                    chartPanel.setData(List.of());
                    summaryLabel.setText("Không tải được dữ liệu thống kê");
                    weeklyLabel.setText(" ");
                    statusLabel.setText("Lỗi: " + e.getMessage());
                    appsModel.setRowCount(0);
                } finally {
                    refreshButton.setEnabled(true);
                    periodCombo.setEnabled(true);
                }
            }
        }.execute();
    }

    private void apply(UsageStatistics.Snapshot snapshot,
                       List<ActivityDao.ProcessCount> processes, LocalDate today) {
        chartPanel.setData(snapshot.days());
        summaryLabel.setText("Tổng: " + GuiFormat.formatDuration(snapshot.totalSeconds())
                + "  •  TB/ngày: " + GuiFormat.formatDuration(snapshot.averageSecondsPerDay())
                + "  •  Tải về: " + GuiFormat.formatBytes(snapshot.totalDownloadBytes())
                + "  •  Gửi lên: " + GuiFormat.formatBytes(snapshot.totalUploadBytes()));
        weeklyLabel.setText("7 ngày gần nhất: " + GuiFormat.formatDuration(snapshot.last7DaysSeconds())
                + "  •  7 ngày trước đó: " + GuiFormat.formatDuration(snapshot.previous7DaysSeconds())
                + "  •  Khoảng: " + snapshot.from() + " → " + snapshot.to()
                + " (" + snapshot.days().size() + " ngày)");
        if (!snapshot.hasData()) {
            statusLabel.setText("Chưa có dữ liệu trong khoảng này (các ngày hiển thị 0).");
        }

        appsModel.setRowCount(0);
        if (activityDao == null) {
            appsModel.addRow(new Object[]{"(Không có DAO nhật ký hoạt động)", "-"});
        } else if (processes.isEmpty()) {
            appsModel.addRow(new Object[]{"(Chưa có bản ghi hoạt động)", "0"});
        } else {
            for (ActivityDao.ProcessCount process : processes) {
                appsModel.addRow(new Object[]{process.processName(), process.events()});
            }
        }
        log.debug("Usage statistics loaded for [{}]: {}..{} ({}s)",
                clientId, snapshot.from(), snapshot.to(), snapshot.totalSeconds());
    }
}
