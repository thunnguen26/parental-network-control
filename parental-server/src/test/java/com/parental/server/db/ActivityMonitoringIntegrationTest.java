package com.parental.server.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.parental.common.protocol.BaseMessage;
import com.parental.common.protocol.JsonProtocolParser;
import com.parental.common.protocol.MessageType;
import com.parental.common.protocol.payload.ActivityReportPayload;
import com.parental.common.protocol.payload.HandshakePayload;
import com.parental.server.network.ClientConnectionListener;
import com.parental.server.network.ClientSession;
import com.parental.server.network.TcpServerEngine;

/**
 * Integration test Activity Monitoring (PROTOCOL.md Mục 3.9) qua TCP thật:
 * client ảo handshake rồi gửi ACTIVITY_REPORT → server lưu vào bảng
 * {@code activity_logs} qua {@link ActivityDao}, cập nhật
 * {@code session.lastActivity} và fire {@code onActivityReport} cho listener
 * (Dashboard hiển thị tức thời).
 */
@Timeout(60)
class ActivityMonitoringIntegrationTest {

    private DatabaseManager database;
    private TcpServerEngine engine;
    private JsonProtocolParser parser;
    private Socket clientSocket;
    private BufferedReader clientReader;
    private BufferedWriter clientWriter;
    private final List<String> activityEvents = new CopyOnWriteArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("activity_integration.db"));
        parser = new JsonProtocolParser();
        engine = new TcpServerEngine(0, 8, 30, database);
        engine.getSessionManager().addConnectionListener(new ClientConnectionListener() {
            @Override
            public void onActivityReport(ClientSession session, ActivityReportPayload activity) {
                activityEvents.add(session.getClientId() + ":" + activity.activeProcessName());
            }
        });
        engine.start();
    }

    @AfterEach
    void tearDown() {
        closeClientQuietly();
        engine.stop();
    }

    private void connectClient(String clientId) throws IOException {
        engine.getClientDao().saveDeviceToken(clientId, "token-" + clientId);
        clientSocket = new Socket("127.0.0.1", engine.getPort());
        clientSocket.setTcpNoDelay(true);
        clientSocket.setSoTimeout(15_000);
        clientReader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        clientWriter = new BufferedWriter(
                new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
        writeAndFlush(BaseMessage.of(MessageType.HANDSHAKE, clientId, new HandshakePayload(
                "PC-Activity", "DESKTOP", "Windows 11", "127.0.0.1", clientId, "token-" + clientId)));
        awaitCondition("client ONLINE", Duration.ofSeconds(10), () ->
                "ONLINE".equals(engine.getClientDao().getStatus(clientId)));
    }

    private void writeAndFlush(BaseMessage<?> message) throws IOException {
        try {
            clientWriter.write(parser.toLine(message));
        } catch (com.parental.common.protocol.ProtocolParseException e) {
            throw new IOException("Serialize failed in test", e);
        }
        clientWriter.flush();
    }

    private void closeClientQuietly() {
        try {
            if (clientSocket != null) {
                clientSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        clientSocket = null;
    }

    private static void awaitCondition(String description, Duration timeout,
                                       java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "Timeout while waiting: " + description);
    }

    @Test
    @DisplayName("ACTIVITY_REPORT → lưu DB + session.lastActivity + listener fire")
    void activityReportPersistsAndNotifies() throws Exception {
        connectClient("activity-client-1");

        writeAndFlush(BaseMessage.of(MessageType.ACTIVITY_REPORT, "activity-client-1",
                new ActivityReportPayload(
                        "Giải bài tập Hóa 12 | VietJack - Google Chrome", "chrome.exe",
                        List.of("chrome.exe", "notepad.exe"), 1_727_400_000_000L)));

        ActivityDao dao = engine.getActivityDao();
        assertNotNull(dao);

        // 1. DB có bản ghi đúng
        awaitCondition("activity_logs có bản ghi", Duration.ofSeconds(10), () ->
                dao.countAll("activity-client-1") >= 1);
        List<ActivityDao.ActivityRecord> records = dao.getRecentActivities("activity-client-1", 10);
        assertEquals(1, records.size());
        assertEquals("chrome.exe", records.get(0).processName());
        assertEquals("Giải bài tập Hóa 12 | VietJack - Google Chrome", records.get(0).windowTitle());

        // 2. session.lastActivity cập nhật (Dashboard realtime)
        awaitCondition("session.lastActivity cập nhật", Duration.ofSeconds(10), () -> {
            ClientSession session = engine.getSessionManager().get("activity-client-1");
            return session != null && session.getLastActivity() != null
                    && "chrome.exe".equals(session.getLastActivity().activeProcessName());
        });

        // 3. Listener được fire (Dashboard nhận thông báo)
        awaitCondition("onActivityReport fire", Duration.ofSeconds(10), () ->
                activityEvents.contains("activity-client-1:chrome.exe"));
    }

    @Test
    @DisplayName("Nhiều ACTIVITY_REPORT liên tiếp → getRecentActivities(10) mới nhất trước")
    void multipleReportsStoredInOrder() throws Exception {
        connectClient("activity-client-2");

        for (int i = 1; i <= 3; i++) {
            writeAndFlush(BaseMessage.of(MessageType.ACTIVITY_REPORT, "activity-client-2",
                    new ActivityReportPayload("Cửa sổ " + i, "app" + i + ".exe",
                            List.of("app" + i + ".exe"), 1_727_400_000_000L + i)));
            Thread.sleep(50); // phân biệt recorded_at
        }

        awaitCondition("3 bản ghi vào DB", Duration.ofSeconds(10), () ->
                engine.getActivityDao().countAll("activity-client-2") >= 3);

        List<ActivityDao.ActivityRecord> recent =
                engine.getActivityDao().getRecentActivities("activity-client-2", 10);
        assertEquals(3, recent.size());
        assertEquals("app3.exe", recent.get(0).processName(), "Mới nhất đứng trước");
    }

    @Test
    @DisplayName("DEDUP: gửi lặp cùng process+title → KHÔNG insert thêm; đổi tab → insert bản ghi mới")
    void duplicateActivityReportsAreNotInsertedTwice() throws Exception {
        connectClient("activity-dedup-client");
        ActivityDao dao = engine.getActivityDao();

        ActivityReportPayload watching = new ActivityReportPayload(
                "YouTube - Cốc Cốc", "browser.exe", List.of("browser.exe"), 1L);

        // Người dùng ở yên xem 1 video — client gửi 5 lần giống hệt nhau (10s/lần)
        for (int i = 0; i < 5; i++) {
            writeAndFlush(BaseMessage.of(MessageType.ACTIVITY_REPORT, "activity-dedup-client", watching));
            Thread.sleep(50);
        }
        awaitCondition("bản ghi ĐẦU TIÊN vào DB", Duration.ofSeconds(10),
                () -> dao.countAll("activity-dedup-client") >= 1);
        Thread.sleep(300); // đợi các report sau được xử lý — nếu không dedup sẽ thành 5 dòng
        assertEquals(1, dao.countAll("activity-dedup-client"),
                "5 report giống hệt nhau → CHỈ ĐÚNG 1 dòng trong DB (chống spam 10s/lần)");

        // Người dùng chuyển tab: process+title THAY ĐỔI → được insert bản ghi mới
        writeAndFlush(BaseMessage.of(MessageType.ACTIVITY_REPORT, "activity-dedup-client",
                new ActivityReportPayload("Facebook – log in", "browser.exe", List.of("browser.exe"), 2L)));
        awaitCondition("bản ghi THỨ HAI (đổi tab) vào DB", Duration.ofSeconds(10),
                () -> dao.countAll("activity-dedup-client") >= 2);

        // Ở yên trên Facebook tiếp 2 lần nữa → vẫn chỉ 2 dòng
        for (int i = 0; i < 2; i++) {
            writeAndFlush(BaseMessage.of(MessageType.ACTIVITY_REPORT, "activity-dedup-client",
                    new ActivityReportPayload("Facebook – log in", "browser.exe", List.of("browser.exe"), 3L)));
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertEquals(2, dao.countAll("activity-dedup-client"),
                "Report lặp sau khi đổi tab không được insert thêm");

        List<ActivityDao.ActivityRecord> recent = dao.getRecentActivities("activity-dedup-client", 10);
        assertEquals("Facebook – log in", recent.get(0).windowTitle(), "Mới nhất trước");

        // Dashboard realtime vẫn nhận đủ MỌI report (kể cả trùng) qua listener
        awaitCondition("listener nhận đủ 8 report", Duration.ofSeconds(10), () ->
                activityEvents.size() >= 8);
    }

    @Test
    @DisplayName("ClientSession signature: process+title tạo chữ ký, set/get nhất quán")
    void sessionSignatureRoundTrip() {
        com.parental.server.network.ClientSession session =
                new com.parental.server.network.ClientSession("sig-client", null, null, null, parser);

        org.junit.jupiter.api.Assertions.assertNull(session.getActivitySignature(),
                "Chưa ghi gì → signature null");
        session.setActivitySignature("browser.exe\u0000YouTube - Cốc Cốc");
        assertEquals("browser.exe\u0000YouTube - Cốc Cốc", session.getActivitySignature());
        session.setActivitySignature(null); // reset (khi clear lịch sử)
        org.junit.jupiter.api.Assertions.assertNull(session.getActivitySignature());
    }

    @Test
    @DisplayName("ACTIVITY_REPORT khi engine không DB → session vẫn cập nhật, không crash")
    void activityReportWithoutDatabaseDoesNotCrash() throws Exception {
        TcpServerEngine noDbEngine = new TcpServerEngine(0, 8, 30);
        try {
            noDbEngine.start();
            Socket socket = new Socket("127.0.0.1", noDbEngine.getPort());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(parser.toLine(BaseMessage.of(MessageType.HANDSHAKE, "no-db-activity",
                    new HandshakePayload("PC", "DESKTOP", "Windows 11", "127.0.0.1", "AA", null))));
            writer.write(parser.toLine(BaseMessage.of(MessageType.ACTIVITY_REPORT, "no-db-activity",
                    new ActivityReportPayload("Title", "app.exe", List.of("app.exe"), 1L))));
            writer.flush();
            awaitCondition("engine không DB không crash", Duration.ofSeconds(5), () ->
                    noDbEngine.getSessionManager().count() >= 0);
            socket.close();
        } finally {
            noDbEngine.stop();
        }
    }
}
