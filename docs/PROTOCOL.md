# TCP COMMUNICATION PROTOCOL SPECIFICATION

## 1. Framing Rule
All TCP packets are framed using **Line-Delimited JSON (LDJSON)**:
- Every JSON string MUST end with a newline character: `\n`.
- Input reader: `BufferedReader.readLine()`. Output writer: `PrintWriter.println()`.

## 2. Base Message Structure
Every packet must contain:
```json
{
  "type": "MESSAGE_TYPE",
  "clientId": "STRING_UUID_OR_MAC",
  "timestamp": 1727280000,
  "payload": {}
}
```
- `type`: `MessageType` — một trong: `HANDSHAKE`, `HEARTBEAT`, `METRICS_REPORT`, `COMMAND`, `CONFIG_SYNC`, `ACK`.
- `clientId`: định danh client (UUID hoặc MAC), dạng chuỗi.
- `timestamp`: long, **epoch seconds**.
- `payload`: object tuân theo schema tương ứng ở Mục 3.

## 3. Payload Schemas

### 3.1 HANDSHAKE → `HandshakePayload` (Client gửi khi mới kết nối)
| Field | Type | Mô tả | Ví dụ |
|---|---|---|---|
| `deviceName` | String | Tên thiết bị | `"Laptop-ConTrai"` |
| `deviceType` | String | Loại thiết bị: `"DESKTOP"` hoặc `"MOBILE"` | `"DESKTOP"` |
| `osVersion` | String | Hệ điều hành | `"Windows 11"` |
| `ipAddress` | String | Địa chỉ IP | `"192.168.1.50"` |
| `macAddress` | String | Địa chỉ MAC | `"AA-BB-CC-DD-EE-FF"` |

```json
{
  "deviceName": "Laptop-ConTrai",
  "deviceType": "DESKTOP",
  "osVersion": "Windows 11",
  "ipAddress": "192.168.1.50",
  "macAddress": "AA-BB-CC-DD-EE-FF"
}
```

### 3.2 METRICS_REPORT → `MetricsPayload` (Client gửi định kỳ báo cáo lưu lượng & thời gian)
| Field | Type | Mô tả |
|---|---|---|
| `downloadBytes` | long | Tổng byte đã tải về |
| `uploadBytes` | long | Tổng byte đã tải lên |
| `downloadSpeedBps` | long | Tốc độ download hiện tại |
| `uploadSpeedBps` | long | Tốc độ upload hiện tại |
| `sessionDurationSeconds` | long | Thời gian online trong phiên |
| `networkStatus` | String | `"ACTIVE"` hoặc `"PAUSED"` |

```json
{
  "downloadBytes": 104857600,
  "uploadBytes": 52428800,
  "downloadSpeedBps": 2097152,
  "uploadSpeedBps": 524288,
  "sessionDurationSeconds": 3600,
  "networkStatus": "ACTIVE"
}
```

### 3.3 COMMAND → `CommandPayload` (Server gửi lệnh điều khiển xuống Client)
| Field | Type | Mô tả |
|---|---|---|
| `commandId` | String | UUID định danh lệnh |
| `action` | ActionType | `PAUSE_NETWORK`, `RESUME_NETWORK` hoặc `ALERT` |
| `reason` | String | Lý do khóa hoặc thông điệp cảnh báo |

```json
{
  "commandId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "action": "PAUSE_NETWORK",
  "reason": "Da vuot gioi han thoi gian su dung trong ngay"
}
```

### 3.4 CONFIG_SYNC → `ConfigSyncPayload` (Server gửi cấu hình chính sách cho Client)
| Field | Type | Mô tả |
|---|---|---|
| `blockedDomains` | List&lt;String&gt; | Danh sách tên miền chặn: `tiktok.com`, ... |
| `dailyUsageLimitSeconds` | long | Giới hạn thời gian dùng trong ngày (0 = không giới hạn) |
| `dailyTrafficQuotaBytes` | long | Giới hạn dung lượng trong ngày (0 = không giới hạn) |
| `allowedStartTime` | String | Giờ bắt đầu được phép dùng, định dạng `HH:mm` (ví dụ `"06:00"`) |
| `allowedEndTime` | String | Giờ kết thúc được phép dùng, định dạng `HH:mm` (ví dụ `"22:00"`) |

```json
{
  "blockedDomains": ["tiktok.com", "facebook.com"],
  "dailyUsageLimitSeconds": 14400,
  "dailyTrafficQuotaBytes": 5368709120,
  "allowedStartTime": "06:00",
  "allowedEndTime": "22:00"
}
```

### 3.5 HEARTBEAT / ACK
- Payload hiện tại là object rỗng: `{}`.
- Schema chi tiết bổ sung (nếu cần) sẽ được định nghĩa ở các Phase sau và bắt buộc cập nhật vào tài liệu này trước khi code.