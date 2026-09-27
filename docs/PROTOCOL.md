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
- `type`: `MessageType` — một trong: `HANDSHAKE`, `HEARTBEAT`, `METRICS_REPORT`, `COMMAND`, `CONFIG_SYNC`, `ACK`, `PAIR_REQUEST`, `PAIR_RESPONSE`.
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
| `deviceToken` | String (nullable) | Token bảo mật cấp khi ghép đôi (Mục 3.7); `null`/bỏ trống khi thiết bị CHƯA ghép đôi. Server so khớp với DB — sai/thiếu token → ngắt kết nối `UNAUTHORIZED_DEVICE` | `"550e8400-e29b-41d4-a716-446655440000"` |

```json
{
  "deviceName": "Laptop-ConTrai",
  "deviceType": "DESKTOP",
  "osVersion": "Windows 11",
  "ipAddress": "192.168.1.50",
  "macAddress": "AA-BB-CC-DD-EE-FF",
  "deviceToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

> **Xác thực (Client Identity Persistence)**: `clientId` lưu bền vững trong file `client_identity.json` trên máy con (không sinh lại UUID mới mỗi lần bật). Chỉ thiết bị ĐÃ ghép đôi (token hợp lệ khớp DB) mới được chấp nhận phiên HANDSHAKE.

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
- HEARTBEAT: payload là object rỗng: `{}`.
- ACK: payload có thể rỗng `{}` (phản hồi HEARTBEAT) hoặc chứa trường tùy chọn `commandId` khi ACK là **xác nhận tiếp nhận lệnh COMMAND** (bổ sung Task 3.3):

| Field | Type | Mô tả |
|---|---|---|
| `commandId` | String (tùy chọn) | UUID của lệnh COMMAND đã được client tiếp nhận; không có khi ACK heartbeat thường |

```json
{
  "commandId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```
- Parser xử lý lenient: ACK payload khớp cả `{}` lẫn `{"commandId": "..."}`.

### 3.6 PAIR_REQUEST → `PairRequestPayload` (Client gửi khi muốn ghép đôi lần đầu)
| Field | Type | Mô tả |
|---|---|---|
| `pin` | String | Mã PIN 6 số do phụ huynh sinh từ Server GUI (`[ ➕ Thêm thiết bị mới ]`), còn hạn 5 phút |
| `deviceName` | String | Tên thiết bị muốn ghép đôi |
| `deviceType` | String | `"DESKTOP"` hoặc `"MOBILE"` |
| `osVersion` | String | Hệ điều hành |
| `ipAddress` | String | Địa chỉ IP |
| `macAddress` | String | Địa chỉ MAC |

```json
{
  "pin": "482913",
  "deviceName": "Laptop-ConTrai",
  "deviceType": "DESKTOP",
  "osVersion": "Windows 11",
  "ipAddress": "192.168.1.50",
  "macAddress": "AA-BB-CC-DD-EE-FF"
}
```

> `clientId` trong envelope của PAIR_REQUEST phải là UUID định danh bền vững đã lưu trong `client_identity.json` — Server cấp `deviceToken` gắn với chính `clientId` này (UPSERT 1 dòng duy nhất).

### 3.7 PAIR_RESPONSE → `PairResponsePayload` (Server trả lời PAIR_REQUEST)
| Field | Type | Mô tả |
|---|---|---|
| `success` | boolean | `true` = ghép đôi thành công, `false` = bị từ chối |
| `deviceToken` | String (nullable) | UUID token cấp quyền kết nối — chỉ có khi `success = true` |
| `message` | String | Thông điệp kết quả: `"PAIRED"` khi thành công, hoặc lý do từ chối (`"INVALID_PIN"` / `"PIN_EXPIRED"` / `"PIN_ALREADY_USED"`) |

```json
{
  "success": true,
  "deviceToken": "550e8400-e29b-41d4-a716-446655440000",
  "message": "PAIRED"
}
```

> Sau khi nhận `success = true`, Client lưu `deviceToken` vào `client_identity.json`, đóng phiên ghép đôi và chuyển sang chạy ngầm với HANDSHAKE kèm `deviceToken`. Client gửi PAIR_REQUEST phải đợi PAIR_RESPONSE rồi ngắt kết nối (không giữ phiên).

- Mọi bổ sung schema khác bắt buộc cập nhật tài liệu này trước khi code.