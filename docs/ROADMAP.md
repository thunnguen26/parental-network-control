# PROJECT ROADMAP & IMPLEMENTATION PHASES

## Phase 1: Foundation & Common Protocol Engine
- [x] Task 1.1: Setup Maven Multi-module (`parental-network-control`, `parental-common`, `parental-server`, `parental-client`).
- [x] Task 1.2: Định nghĩa Models, Enums, DTOs theo `PROTOCOL.md` trong `parental-common`.
- [x] Task 1.3: Viết `JsonProtocolParser` mã hóa/giải mã LDJSON với Jackson/Gson. Unit test 100% qua (18/18 test).

## Phase 2: Core Server & Client TCP Networking
- [x] Task 2.1: Server - `ServerSocket` Engine với `ExecutorService` (Thread pool) và `ClientSessionManager`. (Integration test 9/9 qua)
- [ ] Task 2.2: Client - Kết nối TCP, gửi `HANDSHAKE`, chạy vòng lặp gửi `HEARTBEAT` (5s/lần), Auto-reconnect.
- [ ] Task 2.3: Xử lý đóng kết nối an toàn (Graceful Shutdown) và phát hiện timeout/ngắt kết nối bất thường.

## Phase 3: Hardware Metrics & Client Enforcement Engine
- [ ] Task 3.1: Client - Tích hợp OSHI đo lưu lượng upload/download, gửi gói tin `METRICS_REPORT`.
- [ ] Task 3.2: Client - Can thiệp file `hosts` để chặn danh sách domain từ lệnh `CONFIG_SYNC`.
- [ ] Task 3.3: Client - Thực thi lệnh hệ thống `PAUSE_NETWORK` / `RESUME_NETWORK` qua `netsh`.

## Phase 4: Server Database & Business Rules Engine
- [ ] Task 4.1: Server - Tích hợp SQLite, tạo Schema (clients, metrics_log, rules, schedules).
- [ ] Task 4.2: Server - Background Rule Engine kiểm tra giới hạn giờ dùng và dung lượng, tự phát lệnh `COMMAND`.

## Phase 5: Server Management GUI
- [ ] Task 5.1: Xây dựng Dashboard (Swing/JavaFX) hiển thị danh sách client, tình trạng online/offline, traffic realtime.
- [ ] Task 5.2: Giao diện cấu hình giới hạn, chặn website, nút điều khiển tạm dừng/khôi phục mạng thủ công.