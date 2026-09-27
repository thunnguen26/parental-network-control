package com.parental.client.identity;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

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
 * vào {@code client_identity.json} qua {@link ClientIdentityManager}, đóng
 * frame và callback {@code onPaired} để app chuyển sang chạy ngầm.</p>
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
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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
            statusLabel.setText("⚠ Vui lòng nhập IP máy chủ");
            return;
        }
        if (pin.length() != 6) {
            statusLabel.setText("⚠ Mã PIN phải đủ 6 chữ số");
            return;
        }
        int port = ClientConfig.DEFAULT_SERVER_PORT; // 8888
        pairButton.setEnabled(false);
        statusLabel.setText("Đang ghép đôi tới " + host + ":" + port + " ...");

        String clientId = identityManager.loadOrCreate().clientId();
        PairRequestPayload request = toPairRequest(deviceInfoCollector.collect(), pin);

        new javax.swing.SwingWorker<PairingResult, Void>() {
            @Override
            protected PairingResult doInBackground() {
                return new PairingClient().pair(host, port, clientId, pin, request);
            }

            @Override
            protected void done() {
                pairButton.setEnabled(true);
                try {
                    handlePairingResult(host, port, get());
                } catch (Exception e) {
                    statusLabel.setText("⚠ Lỗi ghép đôi: " + e.getMessage());
                    log.error("Pairing worker failed: {}", e.getMessage(), e);
                }
            }
        }.execute();
    }

    /** Map thông tin máy (HandshakePayload) + PIN → PairRequestPayload (Mục 3.6). */
    private PairRequestPayload toPairRequest(HandshakePayload device, String pin) {
        return new PairRequestPayload(pin, device.deviceName(), device.deviceType(),
                device.osVersion(), device.ipAddress(), device.macAddress());
    }

    /** Xử lý kết quả ghép đôi trên EDT: lưu token → callback → đóng frame. */
    private void handlePairingResult(String host, int port, PairingResult result) {
        if (!result.success()) {
            statusLabel.setText("⚠ Ghép đôi thất bại: " + result.message());
            return;
        }
        // Lưu token + nhớ serverHost/serverPort cho lần bật sau (zero-config).
        var current = identityManager.loadOrCreate();
        identityManager.save(new ClientIdentityManager.ClientIdentity(
                current.clientId(), result.deviceToken(), host, port));
        log.info("Paired! deviceToken saved to {}", identityManager.getIdentityFilePath());

        statusLabel.setText("✅ Ghép đôi thành công! Đang chuyển sang chạy ngầm...");
        if (onPairedCallback != null) {
            onPairedCallback.run();
        }
        dispose();
    }
}
