# PROJECT ROADMAP & IMPLEMENTATION PHASES

## Phase 1: Foundation & Common Protocol Engine
- [x] Task 1.1: Setup Maven Multi-module (`parental-network-control`, `parental-common`, `parental-server`, `parental-client`).
- [x] Task 1.2: Định nghĩa Models, Enums, DTOs theo `PROTOCOL.md` trong `parental-common`.
- [x] Task 1.3: Viết `JsonProtocolParser` mã hóa/giải mã LDJSON với Jackson/Gson. Unit test 100% qua (18/18 test).

## Phase 2: Core Server & Client TCP Networking — HOÀN THÀNH 100%
- [x] Task 2.1: Server - `ServerSocket` Engine với `ExecutorService` (Thread pool) và `ClientSessionManager`. (Integration test 9/9 qua)
- [x] Task 2.2: Client - Kết nối TCP, gửi `HANDSHAKE`, chạy vòng lặp gửi `HEARTBEAT` (5s/lần), Auto-reconnect. (24/24 test qua — tổng 51/51; exponential backoff 1s→30s, `ClientConfig`, `DeviceInfoCollector`, `TcpClientEngine`, `ServerCommandHandler`)
- [x] Task 2.3: Graceful Shutdown + phát hiện timeout/ngắt kết nối bất thường. (68/68 test toàn dự án — tổng 20/20 server + 30/30 client; Watchdog Silent Server Detection 15s/3 nhịp tim + JVM shutdown hook + close(); server `DisconnectReason` + `ClientConnectionListener` callbacks + sweeper `HEARTBEAT_TIMEOUT` + `closeAll(SERVER_SHUTDOWN)`)

## Phase 3: Hardware Metrics & Client Enforcement Engine — HOÀN THÀNH 100%
- [x] Task 3.1: Client - Tích hợp OSHI đo lưu lượng upload/download, gửi gói tin `METRICS_REPORT`. (79/79 test toàn dự án — 40/40 client + 21/21 server; `NetworkMetricsCollector` + `OshiNetworkMetricsCollector` delta calculation, scheduler riêng `metricsIntervalMs=5s`, server lưu `session.lastMetrics`)
- [x] Task 3.2: Client - Can thiệp file `hosts` để chặn danh sách domain từ lệnh `CONFIG_SYNC`. (89/89 test toàn dự án — 50/50 client; `HostsFileManager` marker block + backup/restore + idempotent + atomic write + defensive quyền, tích hợp CONFIG_SYNC + shutdown hook clearBlocks)
- [x] Task 3.3: Client - Thực thi `PAUSE_NETWORK`/`RESUME_NETWORK` qua firewall rule `netsh` + `ALERT` notifier + ACK commandId. (105/105 test toàn dự án — 66/66 client; `NetworkEnforcer`/`WindowsNetshEnforcer` (block outbound 80,443/TCP giữ LAN) + `AlertNotifier`/`SystemTrayAlertNotifier` + `ActionDispatcher`, PROTOCOL.md Mục 3.5 bổ sung ACK `commandId`, cleanup on exit)

## Phase 4: Server Database & Business Rules Engine — HOÀN THÀNH 100%
- [x] Task 4.1: Server - Tích hợp SQLite, tạo Schema (clients, metrics_logs, client_rules, blocked_domains, command_logs). (130/130 test toàn dự án — 46/46 server; `DatabaseManager` (WAL file DB + foreign_keys/busy_timeout per-connection + migration idempotent + anchor connection cho :memory: shared-cache), DAO layer `ClientDao`/`MetricsDao` (getTodayTrafficBytes)/`RuleDao` (+blocked domains)/`CommandLogDao` (SENT→ACKED), tích hợp HANDSHAKE→upsert ONLINE / disconnect→OFFLINE / METRICS_REPORT→insert / ACK→markAcked)
- [x] Task 4.2: Server - Background Rule Engine kiểm tra giới hạn giờ dùng và dung lượng, tự phát lệnh `COMMAND`. (149/149 test toàn dự án — 65/65 server; `RuleEvaluationEngine` scheduler `parental-server-rule-engine` chu kỳ cấu hình được (mặc định 30s), 3 điều kiện vi phạm theo thứ tự ưu tiên khung giờ (HH:mm, hỗ trợ qua đêm + fail-open) → thời gian (sessionDurationSeconds) → dung lượng (getTodayTrafficBytes), enforcement state chống spam + RESUME khi nới lỏng/gỡ rule, pre-warning ALERT ≤ 5 phút đúng 1 lần, offline cleanup, `setRuleScanIntervalSeconds`, start/stop cùng `TcpServerEngine`)

## Phase 5: Server Management GUI
- [x] Task 5.1: Xây dựng Dashboard (Swing + FlatLaf) hiển thị danh sách client, tình trạng online/offline, traffic realtime. (155/155 test toàn dự án — 71/71 server; `ServerDashboardFrame` 3 vùng (sidebar thiết bị / center chi tiết + metric cards + progress hạn mức / event log console), `ClientConnectionListener` bridge + `javax.swing.Timer` 1s, mọi cập nhật qua `SwingUtilities.invokeLater`, `GuiFormat` pure-function test headless, `ServerApp` (FlatLightLaf, port 8888, WindowClosing → graceful shutdown), FlatLaf 3.5.1, surefire `java.awt.headless=true`)
- [ ] Task 5.2: Giao diện cấu hình giới hạn, chặn website, nút điều khiển tạm dừng/khôi phục mạng thủ công.