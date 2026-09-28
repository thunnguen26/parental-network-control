package com.parental.client.identity;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.PlainDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.client.config.ClientConfig;
import com.parental.client.device.DeviceInfoCollector;
import com.parental.client.identity.PairingClient.PairingResult;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.common.protocol.payload.PairRequestPayload;

/**
 * Giao diện ghép đôi lần đầu (Swing/FlatLaf) — xuất hiện khi
 * {@code client_identity.json} CHƯA có {@code deviceToken} (hoặc bị server
 * từ chối token cũ).
 *
 * <p>Bố cục: nhập IP máy chủ (mặc định 127.0.0.1) + mã PIN 6 số do phụ huynh
 * sinh từ Server Dashboard, nút {@code [ Ghép đôi & Kết nối ]}. Ghép đôi chạy
 * trên SwingWorker (không block EDT); thành công → lưu {@code deviceToken}
 * vào {@code client_identity.json} qua {@link ClientIdentityManager}, gọi
 * callback {@code onPaired} TRÊN BACKGROUND THREAD (không bao giờ chờ EDT —
 * callback khởi động engine ngầm có thể block), rồi {@code dispose()} frame
 * trên EDT.</p>
 *
 * <p><b>Hotfix deadlock EDT</b>: phiên bản đầu gọi callback trực tiếp trong
 * {@code done()} (EDT) → callback chờ vô hạn ({@code Thread.currentThread().join()})
 * khiến EDT tự block → frame không bao giờ đóng, nút X không phản hồi. Quy ước
 * hiện tại: callback CHỈ chạy trên thread nền; mọi thao tác UI ({@code dispose})
 * nằm trên EDT.</p>
 *
 * <p><b>Nút X đỏ</b>: chưa ghép đôi thành công → hủy worker đang chạy (nếu có)
 * và {@code System.exit(0)} (người dùng chủ động hủy); ĐÃ ghép đôi → chỉ
 * {@code DISPOSE_ON_CLOSE} — engine ngầm tiếp tục chạy, JVM không thoát.</p>
 */
public class ClientPairingFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ClientPairingFrame.class);

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);

    private final ClientIdentityManager identityManager;
    private final DeviceInfoCollector deviceInfoCollector;
    private final Runnable onPairedCallback;

    private final JTextField serverHostField = new JTextField(15);
    private final JTextField pinField = new JTextField(6);
    private final JButton pairButton = new JButton("Ghép đôi & Kết nối");
    private final JLabel statusLabel = new JLabel(" ");

    /** Đã ghép đôi thành công chưa — quyết định hành vi nút X (exit vs dispose). */
    private volatile boolean pairedSuccessfully = false;

    /** Worker ghép đôi đang chạy (để hủy khi người dùng đóng cửa sổ giữa chừng). */
    private volatile javax.swing.SwingWorker<PairingResult, Void> activeWorker;

    /** Client ghép đôi của phiên hiện hành — hủy = đóng socket NGAY (không rò rỉ). */
    private volatile PairingClient activePairingClient;

    /**
     * @param identityManager     quản lý {@code client_identity.json}
     * @param deviceInfoCollector thu thập thông tin máy đóng gói PAIR_REQUEST
     * @param defaultHost         IP máy chủ hiển thị sẵn (mặc định 127.0.0.1)
     * @param onPairedCallback    gọi trên EDT sau khi ghép đôi thành công và
     *                            lưu token (frame tự đóng trước đó)
     */
    public ClientPairingFrame(ClientIdentityManager identityManager,
                              DeviceInfoCollector deviceInfoCollector,
                              String defaultHost,
                              Runnable onPairedCallback) {
        super("Ghép đôi thiết bị — Parental Control");
        this.identityManager = identityManager;
        this.deviceInfoCollector = deviceInfoCollector;
        this.onPairedCallback = onPairedCallback;

        initUi(defaultHost);
    }

    private void initUi(String defaultHost) {
        // Sau khi ghép đôi, đóng cửa sổ CHỈ dispose frame — engine ngầm vẫn chạy,
        // JVM không thoát. Trường hợp CHƯA ghép đôi do WindowListener xử lý exit.
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (!pairedSuccessfully) {
                    log.info("User closed pairing window before pairing — stopping client agent");
                    javax.swing.SwingWorker<?, ?> worker = activeWorker;
                    if (worker != null && !worker.isDone()) {
                        worker.cancel(true); // ngắt worker
                    }
                    PairingClient pairingClient = activePairingClient;
                    if (pairingClient != null) {
                        pairingClient.close(); // đóng socket NGAY — readLine() thoát tức thì, không rò rỉ
                    }
                    System.exit(0); // người dùng chủ động hủy ghép đôi → dừng tiến trình
                }
                // pairedSuccessfully → default DISPOSE_ON_CLOSE, không đụng engine ngầm
            }
        });
        setLayout(new BorderLayout(0, 8));

        JLabel titleLabel = new JLabel("Kích hoạt thiết bị lần đầu", SwingConstants.CENTER);
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("IP Máy chủ:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        serverHostField.setText(defaultHost);
        formPanel.add(serverHostField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        formPanel.add(new JLabel("Mã PIN 6 số:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        pinField.setHorizontalAlignment(JTextField.CENTER);
        pinField.setFont(pinField.getFont().deriveFont(Font.BOLD, 18f));
        applyDigitsOnlyFilter(pinField);
        formPanel.add(pinField, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        pairButton.addActionListener(event -> runPairingWorker());
        buttonPanel.add(pairButton);

        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        add(titleLabel, BorderLayout.NORTH);
        add(formPanel, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(buttonPanel, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        setSize(430, 250);
        setLocationRelativeTo(null);
    }

    /** Chỉ cho nhập chữ số vào ô PIN (DocumentFilter — defensive UI). */
    private static void applyDigitsOnlyFilter(JTextField field) {
        PlainDocument document = (PlainDocument) field.getDocument();
        document.setDocumentFilter(new DocumentFilter() {
            @Override
            public void replace(FilterBypass bypass, int offset, int length,
                                String text, AttributeSet attrs) throws BadLocationException {
                super.replace(bypass, offset, length,
                        text == null ? null : text.replaceAll("\\D", ""), attrs);
            }

            @Override
            public void insertString(FilterBypass bypass, int offset, String text,
                                     AttributeSet attrs) throws BadLocationException {
                super.insertString(bypass, offset,
                        text == null ? null : text.replaceAll("\\D", ""), attrs);
            }
        });
    }

    /** Ghép đôi trên SwingWorker; kết quả xử lý trên EDT. */
    private void runPairingWorker() {
        String host = serverHostField.getText().trim();
        String pin = pinField.getText().replaceAll("\\D", "");
        if (host.isEmpty()) {
            statusLabel.setText("Vui lòng nhập IP máy chủ");
            return;
        }
        if (pin.length() != 6) {
            statusLabel.setText("Mã PIN phải đủ 6 chữ số");
            return;
        }
        int port = ClientConfig.DEFAULT_SERVER_PORT; // 8888
        pairButton.setEnabled(false);
        statusLabel.setText("Đang ghép đôi tới " + host + ":" + port + " ...");

        String clientId = identityManager.loadOrCreate().clientId();
        PairRequestPayload request = toPairRequest(deviceInfoCollector.collect(), pin);

        PairingClient pairingClient = new PairingClient();
        activePairingClient = pairingClient;

        javax.swing.SwingWorker<PairingResult, Void> worker = new javax.swing.SwingWorker<>() {
            @Override
            protected PairingResult doInBackground() {
                return pairingClient.pair(host, port, clientId, pin, request);
            }

            @Override
            protected void done() {
                pairButton.setEnabled(true);
                if (isCancelled()) {
                    return; // người dùng đóng cửa sổ giữa chừng — không đụng UI nữa
                }
                try {
                    handlePairingResult(host, port, get());
                } catch (Exception e) {
                    statusLabel.setText("Lỗi ghép đôi: " + e.getMessage());
                    log.error("Pairing worker failed: {}", e.getMessage(), e);
                }
            }
        };
        activeWorker = worker;
        worker.execute();
    }

    /** Map thông tin máy (HandshakePayload) + PIN → PairRequestPayload (Mục 3.6). */
    private PairRequestPayload toPairRequest(HandshakePayload device, String pin) {
        return new PairRequestPayload(pin, device.deviceName(), device.deviceType(),
                device.osVersion(), device.ipAddress(), device.macAddress());
    }

    /**
     * Xử lý kết quả ghép đôi (LUÔN trên EDT qua {@code done()}):
     * lưu token → đóng frame trên EDT → khởi động engine ngầm trên
     * BACKGROUND THREAD (không bao giờ block EDT — hotfix deadlock).
     */
    private void handlePairingResult(String host, int port, PairingResult result) {
        if (!result.success()) {
            statusLabel.setText("Ghép đôi thất bại: " + result.message());
            return;
        }
        // Đặt cờ TRƯỚC TIÊN: từ đây nút X chỉ dispose, không exit app.
        pairedSuccessfully = true;

        // Lưu token + nhớ serverHost/serverPort cho lần bật sau (zero-config).
        var current = identityManager.loadOrCreate();
        identityManager.save(new ClientIdentityManager.ClientIdentity(
                current.clientId(), result.deviceToken(), host, port));
        log.info("Paired! deviceToken saved to {}", identityManager.getIdentityFilePath());
        statusLabel.setText("✅ Ghép đôi thành công! Đang chuyển sang chạy ngầm...");

        // KHỞI ĐỘNG ENGINE NGẦM TRÊN THREAD RIÊNG — callback có thể block
        // (park main, chờ scheduler...) và TUYỆT ĐỐI không được chạy trên EDT
        // (hotfix: EDT block trong callback → frame không bao giờ đóng được).
        Thread bootstrap = new Thread(() -> {
            try {
                if (onPairedCallback != null) {
                    onPairedCallback.run();
                }
            } catch (RuntimeException e) {
                log.error("Paired callback (engine bootstrap) failed: {}", e.getMessage(), e);
            }
        }, "parental-client-engine-bootstrap");
        bootstrap.setDaemon(true); // JVM sống nhờ main thread đang park
        bootstrap.start();

        // Đóng cửa sổ TRÊN EDT (thread hiện tại chính là EDT qua done()) —
        // diễn ra NGAY LẬP TỨC, không phụ thuộc callback nền.
        SwingUtilities.invokeLater(this::dispose);
    }
}
