# CURRENT PROJECT STATE & TASK LOG

## 1. Project Snapshot
- **Current Phase:** Phase 2 (Core Server & Client TCP Networking) — Task 2.1 HOÀN THÀNH.
- **Current Status:** `mvn clean test` BUILD SUCCESS — 27/27 test pass (18 common + 9 server), không còn process treo.
- **Active Task:** Task 2.2 — Client TCP: kết nối, gửi `HANDSHAKE`, vòng lặp `HEARTBEAT` 5s, auto-reconnect.

## 2. Completed Tasks
- [x] Tạo file AGENTS.md, PROTOCOL.md, ROADMAP.md.
- [x] **Task 1.1** — Maven Multi-Module: root `pom.xml` (groupId `com.parental`, dependencyManagement cho Jackson 2.17.2, SLF4J 2.0.13, sqlite-jdbc 3.46.1.0, oshi-core 6.6.5, JUnit 5.10.2) + 3 module `parental-common` (Jackson, SLF4J), `parental-server` (common, sqlite-jdbc, SLF4J), `parental-client` (common, oshi-core, SLF4J). Xác minh: `mvn clean compile` → BUILD SUCCESS cả 4 reactor module.
- [x] **Task 1.2** — Models/Enums/DTOs trong `parental-common` (package `com.parental.common.protocol`): `MessageType` (6 giá trị), `ActionType` (3 giá trị), `BaseMessage<T>` (type, clientId, timestamp epoch-seconds, payload) và 4 DTO record package `...protocol.payload`: `HandshakePayload`, `MetricsPayload`, `CommandPayload`, `ConfigSyncPayload`.
- [x] Cập nhật `PROTOCOL.md` bổ sung **Mục 3 Payload Schemas** (bảng field + ví dụ JSON cho 4 payload; HEARTBEAT/ACK dùng payload rỗng `{}`).
- [x] **Task 2.1** — Server TCP Networking Engine trong `parental-server` (package `com.parental.server.network`):
    - `ClientSession`: đại diện 1 kết nối — clientId, deviceInfo (HandshakePayload), Socket, BufferedReader, PrintWriter, lastHeartbeatTimestamp; `send()` thread-safe (synchronized writeLock + `checkError()` do PrintWriter nuốt IOException); `close()` ĐÓNG SOCKET TRƯỚC để đánh thức `readLine()` đang block rồi mới đóng stream (fix deadlock monitor, socket read không phản ứng với interrupt trên Windows).
    - `ClientSessionManager`: `ConcurrentHashMap<String, ClientSession>`; register (đóng phiên cũ khi reconnect đè), remove, removeSession (CAS remove(k,v) tránh xóa nhầm phiên mới), get, getAllSessions (snapshot), broadcast (gỡ dead session khi gửi lỗi), sweepDeadSessions(timeoutSeconds) theo heartbeat.
    - `ClientHandler`: Runnable trong pool — bắt buộc gói đầu tiên là HANDSHAKE (timeout 30s, sai → từ chối); HEARTBEAT → cập nhật heartbeat + phản hồi ACK; METRICS_REPORT chỉ log; bắt SocketException/IOException khi client ngắt đột ngột → cleanup session trong finally + log SLF4J rõ ràng.
    - `TcpServerEngine`: mặc định cổng 8888 (constructor overload cho port động); `ServerSocket` bind trên background daemon thread accept loop; `ExecutorService` fixed pool (kích thước `availableProcessors()*2`, tối thiểu 4); scheduled sweeper quét dead session mỗi timeout/2; `stop()` graceful shutdown — đóng listener, remove toàn bộ session, chờ pool tối đa 5s; `getPort()` trả về cổng thực (hỗ trợ port động = 0).
    - Integration test `TcpServerEngineIntegrationTest` (9 test): dynamic port; handshake đăng ký đúng clientId/deviceInfo; gói đầu không phải HANDSHAKE bị từ chối; HEARTBEAT→ACK + cập nhật timestamp; 2 client đồng thời; disconnect → cleanup tự động; server chủ động đẩy COMMAND qua `session.send()`; sweep session quá hạn heartbeat; `stop()` shutdown sạch sẽ. Kết quả: **9/9 PASS** — tổng toàn dự án **27/27 test pass** (`mvn clean test`).
- [x] **Task 1.3** — Protocol Engine trong `parental-common` (package `com.parental.common.protocol`):
    - `JsonProtocolParser`: ObjectMapper an toàn (`FAIL_ON_UNKNOWN_PROPERTIES = false`); `toLine(BaseMessage<?>)` serialize + đảm bảo kết thúc `\n` (Framing Rule LDJSON); `fromLine(String)` tách frame `\n`/`\r\n`, kiểm tra 4 field bắt buộc `type`/`clientId`/`timestamp`/`payload` (Mục 2), parse payload polymorphic: HANDSHAKE→`HandshakePayload`, METRICS_REPORT→`MetricsPayload`, COMMAND→`CommandPayload`, CONFIG_SYNC→`ConfigSyncPayload`, HEARTBEAT/ACK→`Map` rỗng; field DTO thiếu nhận default lenient.
    - `ProtocolParseException`: bọc `JsonProcessingException`, không để exception thô của Jackson văng ra ngoài.
    - Unit test JUnit 5 (`JsonProtocolParserTest`): 18 test / 3 nhóm — Round-trip 4 payload chính + HEARTBEAT/ACK; Framing Rule (kết thúc đúng 1 `\n`, dòng có/không `\n`, ranh giới gói tin khi stream nhiều dòng); Error handling (JSON hỏng, thiếu type/clientId/timestamp/payload, type lạ, payload sai schema, dòng rỗng, unknown field bị bỏ qua). Kết quả: **18/18 PASS** (`mvn clean test`).

## 3. Work In Progress (WIP)
- Không có.

## 4. Next Action Items (Dành cho phiên tiếp theo)
- **Task 2.2** (ROADMAP Phase 2): Client — kết nối TCP, gửi `HANDSHAKE` ngay khi kết nối, vòng lặp gửi `HEARTBEAT` mỗi 5s, auto-reconnect exponential backoff. Client dùng `JsonProtocolParser` + DTO từ `parental-common`; KHÔNG sửa code server.
- Lệnh build môi trường này: `JAVA_HOME="/c/Program Files/Java/jdk-17" /c/maven/apache-maven-3.9.6/bin/mvn clean test`.

## 5. Known Issues / Blockers
- `mvn` chưa có trong PATH (chỉ có JDK 23 không khớp target): dùng đường dẫn đầy đủ `/c/maven/apache-maven-3.9.6/bin/mvn` và `JAVA_HOME=C:\Program Files\Java\jdk-17`, hoặc cân nhắc thêm vào PATH hệ thống.
- `PROTOCOL.md` ban đầu chưa có schema payload → đã bổ sung Mục 3 (do người dùng cung cấp schema chuẩn). Không còn blocker.
- Đã cấu hình `forkedProcessTimeoutInSeconds=300` cho surefire (root `pom.xml`) — tự hủy forked JVM treo, phòng ngừa zombie process giữ khóa `target/` trên Windows (đã gặp và xử lý: deadlock ClientSession.close() khi thread handler block trong socket read không interruptible).