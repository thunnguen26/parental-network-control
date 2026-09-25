# PARENTAL CONTROL SYSTEM - AGENT INSTRUCTION & GUIDELINES

## 1. Project Context
- **Name:** Parental Network Control System (Client/Server)
- **Tech Stack:** Java 17+, Socket TCP/IP, JSON (Jackson or Gson), SQLite, OSHI (Hardware/Network monitor).
- **Architecture:** 1 Master Server, Multiple Desktop/Mobile Clients.

## 2. Engineering Principles
- **No Hallucinations:** Always refer to `PROTOCOL.md` for JSON schemas. Never invent new message types or fields.
- **Fail-Safe Networking:** Socket connections must support Auto-Reconnect (Exponential Backoff / Heartbeat every 5s).
- **Concurrency & Thread-Safety:**
    - Every client connection is isolated in an `ExecutorService` thread pool.
    - Shared state must use `ConcurrentHashMap` or explicit synchronization.
- **Defensive OS Interfacing:** All system commands (netsh, hosts manipulation) must handle permissions/exceptions gracefully without crashing the JVM.
- **Code Style:** Clean Code, separation of concerns (Core, Network, OS, UI, Database). No spaghetti code in UI handlers.

## 3. Session Continuity & Workflow Rules
- **Rule 1 - On Session Start:** Luôn đọc `docs/PROJECT_STATE.md` và `docs/ROADMAP.md` trước tiên để biết task hiện tại. Tham chiếu `docs/PROTOCOL.md` khi viết code mạng/JSON. KHÔNG tự ý làm task thuộc Phase khác.
- **Rule 2 - Scope Boundary:** Chỉ làm đúng Task được giao trong prompt. Không viết trước code cho các Phase chưa tới.
- **Rule 3 - On Session Finish:** Bắt buộc cập nhật lại `docs/PROJECT_STATE.md` với trạng thái cụ thể: việc đã làm, file đã sửa, việc kế tiếp cần làm.

## 4. Output Reporting Format
Mỗi khi hoàn thành, Agent phải báo cáo chuẩn theo cấu trúc:
1. **Tóm tắt hành động**: Danh sách file đã tạo/chỉnh sửa.
2. **Xác nhận tiêu chuẩn**: Khẳng định đã tuân thủ đúng `PROTOCOL.md` và `ROADMAP.md`.
3. **Cập nhật tiến độ**: Trích dẫn nội dung đã ghi vào `PROJECT_STATE.md`.
4. **Hành động kế tiếp**: Chỉ rõ task tiếp theo cần chạy theo `ROADMAP.md`.