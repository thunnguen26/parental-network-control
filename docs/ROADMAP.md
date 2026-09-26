# PROJECT ROADMAP & IMPLEMENTATION PHASES

## Phase 1: Foundation & Common Protocol Engine
- [x] Task 1.1: Setup Maven Multi-module (`parental-network-control`, `parental-common`, `parental-server`, `parental-client`).
- [x] Task 1.2: Định nghĩa Models, Enums, DTOs theo `PROTOCOL.md` trong `parental-common`.
- [x] Task 1.3: Viết `JsonProtocolParser` mã hóa/giải mã LDJSON với Jackson/Gson. Unit test 100% qua (18/18 test).

## Phase 2: Core Server & Client TCP Networking — HOÀN THÀNH 100%
- [x] Task 2.1: Server - `ServerSocket` Engine với `ExecutorService` (Thread pool) và `ClientSessionManager`. (Integration test 9/9 qua)
- [x] Task 2.2: Client - Kết nối TCP, gửi `HANDSHAKE`, chạy vòng lặp gửi `HEARTBEAT` (5s/lần), Auto-reconnect. (24/24 test qua — tổng 51/51; exponential backoff 1s→30s, `ClientConfig`, `DeviceInfoCollector`, `TcpClientEngine`, `ServerCommandHandler`)
- [x] Task 2.3: Graceful Shutdown + phát hiện timeout/ngắt kết nối bất thường. (68/68 test toàn dự án — tổng 20/20 server + 30/30 client; Watchdog Silent Server Detection 15s/3 nhịp tim + JVM shutdown hook + close(); server `DisconnectReason` + `ClientConnectionListener` callbacks + sweeper `HEARTBEAT_TIMEOUT` + `closeAll(SERVER_SHUTDOWN)`)

## Phase 3: Hardware Metrics & Client Enforcement Engine
- [x] Task 3.1: Client - Tích hợp OSHI đo lưu lượng upload/download, gửi gói tin `METRICS_REPORT`. (79/79 test toàn dự án — 40/40 client + 21/21 server; `NetworkMetricsCollector` + `OshiNetworkMetricsCollector` delta calculation, scheduler riêng `metricsIntervalMs=5s`, server lưu `session.lastMetrics`)
- [x] Task 3.2: Client - Can thiệp file `hosts` để chặn danh sách domain từ lệnh `CONFIG_SYNC`. (89/89 test toàn dự án — 50/50 client; `HostsFileManager` marker block + backup/restore + idempotent + atomic write + defensive quyền, tích hợp CONFIG_SYNC + shutdown hook clearBlocks)
- [ ] Task 3.3: Client - Thực thi lệnh hệ thống `PAUSE_NETWORK` / `RESUME_NETWORK` qua `netsh`.

## Phase 4: Server Database & Business Rules Engine
- [ ] Task 4.1: Server - Tích hợp SQLite, tạo Schema (clients, metrics_log, rules, schedules).
- [ ] Task 4.2: Server - Background Rule Engine kiểm tra giới hạn giờ dùng và dung lượng, tự phát lệnh `COMMAND`.

## Phase 5: Server Management GUI
- [ ] Task 5.1: Xây dựng Dashboard (Swing/JavaFX) hiển thị danh sách client, tình trạng online/offline, traffic realtime.
- [ ] Task 5.2: Giao diện cấu hình giới hạn, chặn website, nút điều khiển tạm dừng/khôi phục mạng thủ công.