package com.parental.client.input;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Hộp thoại xin phép điều khiển từ xa phía Client (Mục 3.11).
 *
 * <p>Luôn hỏi rõ người đang ngồi tại máy; nút mặc định là “Từ chối”, đóng cửa
 * sổ cũng là từ chối, hết thời gian cũng không bắt đầu. Không có đường nào tự
 * chấp nhận hoặc bật điều khiển ngầm.</p>
 */
public final class SwingConsentPrompter implements ConsentPrompter {

    static final class PendingRequest {
        private final Consumer<Decision> callback;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile JDialog dialog;

        PendingRequest(Consumer<Decision> callback) {
            this.callback = callback;
        }
    }

    private final AtomicReference<PendingRequest> pendingRequest = new AtomicReference<>();

    @Override
    public void requestConsent(String sessionId, int timeoutSeconds, Consumer<Decision> callback) {
        if (callback == null) {
            return;
        }
        if (isHeadless()) {
            callback.accept(Decision.UNAVAILABLE);
            return;
        }
        PendingRequest request = new PendingRequest(callback);
        cancelRequest(pendingRequest.getAndSet(request));
        SwingUtilities.invokeLater(() -> showDialog(request, timeoutSeconds));
    }

    private void showDialog(PendingRequest request, int timeoutSeconds) {
        if (pendingRequest.get() != request || request.completed.get()) {
            return;
        }
        JDialog dialog = new JDialog((java.awt.Frame) null, "Yêu cầu điều khiển từ xa", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setLayout(new BorderLayout(12, 12));
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        JLabel title = new JLabel("YÊU CẦU ĐIỀU KHIỂN CHUỘT/BÀN PHÍM");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(new Color(0xB4, 0x1C, 0x1C));

        JLabel body = new JLabel("<html><body style='width: 380px'>"
                + "Người quản trị đang xem màn hình của máy này và yêu cầu điều khiển "
                + "chuột/bàn phím.<br><br>"
                + "Chỉ bấm <b>Chấp nhận</b> nếu bạn đang trực tiếp ngồi tại máy này và "
                + "đồng ý. Khi phiên bắt đầu, một chỉ báo luôn hiển thị kèm nút "
                + "<b>Dừng điều khiển</b>.<br><br>"
                + "Không phản hồi sau <b>" + Math.max(1, timeoutSeconds)
                + " giây</b> → tự động từ chối."
                + "</body></html>");

        JButton declineButton = new JButton("Từ chối");
        declineButton.addActionListener(event -> complete(request, Decision.DECLINED, dialog));
        JButton acceptButton = new JButton("Chấp nhận");
        acceptButton.addActionListener(event -> complete(request, Decision.ACCEPTED, dialog));

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.add(declineButton);
        buttons.add(acceptButton);

        dialog.add(title, BorderLayout.NORTH);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(declineButton); // mặc định an toàn
        dialog.pack();
        dialog.setMinimumSize(new Dimension(460, 220));
        dialog.setLocationRelativeTo(null);

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                complete(request, Decision.DECLINED, dialog);
            }
        });

        Timer timer = new Timer(Math.max(1, timeoutSeconds) * 1000,
                event -> complete(request, Decision.EXPIRED, dialog));
        timer.setRepeats(false);
        timer.start();

        request.dialog = dialog;
        if (pendingRequest.get() != request || request.completed.get()) {
            timer.stop();
            dialog.dispose();
            return;
        }
        try {
            dialog.setVisible(true); // modal — trả về khi dispose
        } finally {
            timer.stop();
            if (request.dialog == dialog) {
                request.dialog = null;
            }
        }
    }

    private void complete(PendingRequest request, Decision decision, JDialog dialog) {
        if (!completeIfCurrent(pendingRequest, request, decision)) {
            dialog.dispose();
            return;
        }
        dialog.dispose();
    }

    @Override
    public void cancel() {
        cancelRequest(pendingRequest.getAndSet(null));
    }

    private static void cancelRequest(PendingRequest request) {
        if (request == null) {
            return;
        }
        request.completed.set(true);
        JDialog dialog = request.dialog;
        if (dialog != null) {
            SwingUtilities.invokeLater(dialog::dispose);
        }
    }

    static boolean completeIfCurrent(AtomicReference<PendingRequest> current,
                                     PendingRequest request, Decision decision) {
        if (current.get() != request || !request.completed.compareAndSet(false, true)) {
            return false;
        }
        current.compareAndSet(request, null);
        request.callback.accept(decision);
        return true;
    }

    private static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
