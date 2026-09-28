# parental-android — Client Android (Mở rộng 13)

Module client Android (Kotlin + Jetpack Compose) của Parental Network Control
System — kết nối TCP tới Server Java hiện tại, **tuân thủ 100% schema
`docs/PROTOCOL.md`** (LDJSON, envelope 4 field, payload Mục 3.1–3.9).

## Kiến trúc

```
app/src/main/java/com/parental/android/
├── protocol/                 # Pure Kotlin — mirror 1:1 parental-common
│   ├── MessageType.kt        #   Enum MessageType + ActionType (khớp Java)
│   ├── ProtocolModels.kt     #   Payload data classes Mục 3.1–3.9
│   └── LdjsonProtocol.kt     #   Serialize/parse LDJSON (lenient như JsonProtocolParser)
├── network/                  # Pure Kotlin — unit test JVM được
│   ├── AndroidTcpClientEngine.kt  # HANDSHAKE + HEARTBEAT 10s + reconnect backoff 1s→30s
│   └── PairingClient.kt      #   PAIR_REQUEST/PAIR_RESPONSE one-shot (PIN 6 số)
├── device/
│   └── AndroidDeviceInfoProvider.kt # Build.MANUFACTURER+MODEL, "Android "+RELEASE, Android ID
├── data/
│   └── ConnectionSettingsStore.kt   # DataStore: host/port, clientId UUID bền vững, deviceToken
├── service/
│   └── ConnectionForegroundService.kt # Foreground Service + notification trạng thái
└── ui/
    ├── MainActivity.kt       # Compose: nhập IP, Kết nối/Ngắt, đèn trạng thái, ghép đôi PIN
    └── MainViewModel.kt
```

**Lưu ý kiến trúc**: tầng `protocol/` và `network/` KHÔNG import Android
framework — chạy unit test JVM thuần (`./gradlew :app:testDebugUnitTest`) và
dễ tái sử dụng.

## Build

Yêu cầu: Android Studio (AGP 8.7.2, Kotlin 2.0.21) hoặc Android SDK với
`platforms;android-35`. Mở thư mục `parental-android/` bằng Android Studio,
hoặc:

```bash
cd parental-android
gradle :app:assembleDebug        # APK: app/build/outputs/apk/debug/
gradle :app:testDebugUnitTest    # unit test JVM (protocol + engine)
```

Module này build bằng **Gradle, độc lập với Maven reactor** — `mvn test`
ở repo root KHÔNG build/ảnh hưởng module Android (2 hệ build song song,
không phá CI Java hiện có).

## Luồng sử dụng

1. Phụ huynh mở Server Dashboard → `[ + Thêm thiết bị mới ]` → PIN 6 số.
2. Trên Android: nhập IP máy chủ + cổng (8888) → nhập PIN → **Ghép đôi & Kết nối**.
3. `PairingClient` gửi `PAIR_REQUEST` → nhận `PAIR_RESPONSE` (token) → lưu DataStore.
4. `ConnectionForegroundService` start → `AndroidTcpClientEngine`:
   `HANDSHAKE` (kèm token, `deviceType="MOBILE"`) → hiện trên Dashboard →
   `HEARTBEAT` mỗi 10s giữ phiên → mất kết nối tự reconnect backoff 1s→30s.
5. Server gửi `COMMAND` (pause/alert) / `CONFIG_SYNC` → engine callback +
   ACK lệnh. `UNPAIR` → xóa token, quay về màn hình ghép đôi.

## Phạm vi Mở rộng 13 (đã làm / chưa làm)

- ✅ Kết nối TCP + HANDSHAKE + HEARTBEAT + auto-reconnect + Foreground Service.
- ✅ Ghép đôi PIN (PAIR_REQUEST/PAIR_RESPONSE), lưu token bền vững.
- ✅ Nhận COMMAND/CONFIG_SYNC/UNPAIR + ACK lệnh.
- ⏳ Enforcement Android (VPN Service chặn domain, Screen Time...) — phần
  tiếp theo của ROADMAP; hiện COMMAND chỉ được ghi nhận (log) và ACK.
- ⏳ METRICS_REPORT / ACTIVITY_REPORT chưa gửi (không có OSHI trên Android —
  cần TrafficStats; để lộ trình sau).
