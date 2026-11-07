package com.parental.client.screen;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.parental.common.protocol.payload.ScreenFramePayload;
import com.parental.common.protocol.payload.StreamControlPayload;

/**
 * Service quản lý chụp và nén màn hình remote (Live Screen Streaming —
 * Mục 3.10 của {@code docs/PROTOCOL.md}).
 *
 * <p>Triển khai:
 * <ul>
 *   <li>Chụp màn hình bằng {@link Robot} (chỉ khi cần stream
 *       hoạt động — không giữ {@code Robot} khi stream tắt).</li>
 *   <li>Nén {@link BufferedImage} thành JPEG bằng {@link ImageIO} với chất
 *       lượng có thể cấu hình (mặc định {@code 0.7}). Kích thước ảnh được chỉ
 *       định từ server qua {@link ScreenControlRequest}; client upscale/downscale
 *       toàn bộ vùng desktop (kể cả nhiều màn hình), sau đó thu nhỏ vừa khung
 *       kích thước yêu cầu và giữ nguyên tỉ lệ khung hình.</li>
 *   <li>Ảnh JPEG được mã hóa Base64 thành 1 dòng ({@code jpegBase64}) rồi
 *       trả về {@link ScreenFramePayload} để client engine serialize thành
 *       gói LDJSON {@link com.parental.common.protocol.MessageType#STREAM_FRAME}.</li>
 * </ul>
 *
 * <p>Đa luồng: {@link #start(ScreenControlRequest)} và {@link #stop()} là
 * thread-safe (atomic Boolean state), thread khung hình chạy trên Scheduler
 * riêng từ {@code TcpClientEngine} — không block các luồng heartbeat/COMMAND/
 * reader.</p>
 */
public final class ScreenCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ScreenCaptureService.class);

    private static final double DEFAULT_QUALITY = 0.7;
    private static final long DEFAULT_FPS = 5L;
    private static final int DEFAULT_WIDTH = 960;
    private static final double DEFAULT_ASPECT_RATIO = 16.0 / 9.0;
    private static final int MAX_DIMENSION = 1600;
    private static final double ASPECT_HEIGHT_FACTOR = 9.0 / 16.0;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<ScreenControlRequest> currentRequest =
            new AtomicReference<>(ScreenControlRequest.DEFAULT);
    private final AtomicInteger frameSeqNo = new AtomicInteger(0);

    private volatile Robot robot;
    private volatile Thread captureThread;

    public ScreenCaptureService() {
        // no-op
    }

    /**
     * Bắt đầu phiên stream: giữ {@link ScreenControlRequest} hiện tại, khởi
     * tạo {@link Robot} nếu cần và lưu reference.
     *
     * <p>Gọi {@link #stop()} để dừng và giải phóng {@code Robot}.</p>
     */
    public void start(ScreenControlRequest request) {
        if (request == null) {
            request = ScreenControlRequest.DEFAULT;
        }
        if (!started.compareAndSet(false, true)) {
            currentRequest.set(request);
            return;
        }
        currentRequest.set(request);
        log.info("Screen capture started: {}x{} q={} fps={}",
                request.width(), request.height(), request.quality(), request.fps());
        try {
            robot = new Robot();
            robot.setAutoDelay(0);
        } catch (AWTException e) {
            log.error("Cannot create Robot for screen capture: {}", e.getMessage());
            started.set(false);
            return;
        }
        frameSeqNo.set(0);
    }

    /**
     * Dừng phiên stream: đánh dấu stop + dọn Robot.
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Screen capture stopped");
        Robot r = this.robot;
        this.robot = null;
        Thread t = this.captureThread;
        this.captureThread = null;
        if (r != null) {
            // Robot instance được release khi không còn reference
        }
        if (t != null) {
            // capture thread sẽ dừng tự nhiên khi check started
        }
    }

    public boolean isStarted() {
        return started.get();
    }

    /**
     * Chụp 1 frame hiện tại → {@link ScreenFramePayload}. Null khi chưa
     * start hoặc gặp lỗi capture.
     */
    public ScreenFramePayload captureFrame() {
        if (!started.get() || robot == null) {
            return null;
        }
        ScreenControlRequest req = currentRequest.get();
        if (req == null) {
            req = ScreenControlRequest.DEFAULT;
        }
        try {
            Rectangle screenBounds = virtualScreenBounds();
            BufferedImage screenImage = robot.createScreenCapture(screenBounds);
            Dimension targetSize = fitWithin(screenImage.getWidth(), screenImage.getHeight(),
                    Math.min(req.width(), MAX_DIMENSION), Math.min(req.height(), MAX_DIMENSION));
            BufferedImage resized = resize(screenImage, targetSize.width, targetSize.height);

            double quality = Math.max(0.1, Math.min(1.0, req.quality()));
            byte[] jpegBytes = toJpeg(resized, quality);

            String base64 = Base64.getEncoder().encodeToString(jpegBytes);
            long seqNo = frameSeqNo.getAndIncrement();
            long tsMs = System.currentTimeMillis();

            return new ScreenFramePayload(targetSize.width, targetSize.height, base64,
                    quality, seqNo, tsMs);
        } catch (Exception e) {
            log.error("Screen capture failed: {}", e.getMessage());
            return null;
        }
    }

    static Rectangle unionScreenBounds(List<Rectangle> screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) {
            throw new IllegalStateException("No display bounds are available");
        }
        Rectangle union = null;
        for (Rectangle bounds : screenBounds) {
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                union = union == null ? new Rectangle(bounds) : union.union(bounds);
            }
        }
        if (union == null) {
            throw new IllegalStateException("Display bounds are invalid");
        }
        return union;
    }

    private static Rectangle virtualScreenBounds() {
        List<Rectangle> bounds = new ArrayList<>();
        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds.add(device.getDefaultConfiguration().getBounds());
        }
        return unionScreenBounds(bounds);
    }

    static Dimension fitWithin(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("Image and output dimensions must be positive");
        }
        double scale = Math.min(1.0, Math.min(
                (double) maxWidth / sourceWidth, (double) maxHeight / sourceHeight));
        return new Dimension(Math.max(1, (int) Math.round(sourceWidth * scale)),
                Math.max(1, (int) Math.round(sourceHeight * scale)));
    }

    static byte[] toJpeg(BufferedImage image, double quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter is available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                double boundedQuality = Double.isFinite(quality)
                        ? Math.max(0.1, Math.min(1.0, quality)) : DEFAULT_QUALITY;
                parameters.setCompressionQuality((float) boundedQuality);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
        if (src.getWidth() == targetW && src.getHeight() == targetH) {
            return src;
        }
        BufferedImage resized =
                new java.awt.image.BufferedImage(targetW, targetH,
                    BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    public ScreenControlRequest currentRequest() {
        return currentRequest.get();
    }

    /**
     * Yêu cầu điều khiển stream (tham số kế từ server STREAM_CONTROL).
     */
    public record ScreenControlRequest(int width, int height, double quality, long fps) {

        public static final ScreenControlRequest DEFAULT =
                new ScreenControlRequest(DEFAULT_WIDTH,
                        (int) Math.round(DEFAULT_WIDTH * DEFAULT_ASPECT_RATIO),
                        DEFAULT_QUALITY, DEFAULT_FPS);

        public ScreenControlRequest {
            quality = Double.isFinite(quality)
                    ? Math.max(0.1, Math.min(1.0, quality)) : DEFAULT_QUALITY;
            if (width <= 0) width = DEFAULT_WIDTH;
            if (height <= 0) height = (int) Math.round(width * DEFAULT_ASPECT_RATIO);
            if (fps <= 0) fps = DEFAULT_FPS;
        }

        /**
         * Request từ payload server (StreamControlPayload).
         */
        public static ScreenControlRequest fromPayload(StreamControlPayload payload) {
            return new ScreenControlRequest(payload.width(), payload.height(),
                    payload.quality(), payload.fps());
        }
    }
}
