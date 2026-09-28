package com.parental.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Lưu cài đặt kết nối bằng Jetpack DataStore (Preferences) — KHÔNG phải nhập
 * lại IP mỗi lần mở app (yêu cầu nhiệm vụ):
 *
 * - `serverHost` / `serverPort` (mặc định 8888 theo spec).
 * - `clientId`: UUID bền vững — sinh ĐÚNG MỘT LẦN, các lần mở sau đọc lại
 *   (khớp quy ước client_identity.json của desktop).
 * - `deviceToken`: token ghép đôi server cấp; xóa khi bị UNPAIR.
 */
class ConnectionSettingsStore(private val context: Context) {

    companion object {
        private const val STORE_NAME = "connection_settings"

        // Định nghĩa DataStore (extension property — 1 instance per process)
        private val Context.dataStore by preferencesDataStore(name = STORE_NAME)

        private val KEY_HOST = stringPreferencesKey("server_host")
        private val KEY_PORT = intPreferencesKey("server_port")
        private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        private val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")

        const val DEFAULT_HOST = "192.168.1.100"
        const val DEFAULT_PORT = 8888
    }

    /** Flow cài đặt hiện hành — UI collect để prefill ô nhập. */
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            host = prefs[KEY_HOST] ?: DEFAULT_HOST,
            port = prefs[KEY_PORT] ?: DEFAULT_PORT,
            clientId = prefs[KEY_CLIENT_ID] ?: "",
            deviceToken = prefs[KEY_DEVICE_TOKEN]
        )
    }

    /** Cài đặt hiện hành (suspend — gọi trước khi dựng engine). */
    suspend fun current(): Settings = settings.first()

    /** Lưu host/port khi người dùng bấm Kết nối. */
    suspend fun saveServerAddress(host: String, port: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HOST] = host
            prefs[KEY_PORT] = port
        }
    }

    /**
     * Lấy clientId bền vững: có sẵn → trả lại; chưa có → sinh UUID ngẫu nhiên
     * và ghi ngay (chỉ sinh 1 lần — các lần gọi sau trả cùng giá trị).
     */
    suspend fun getOrCreateClientId(): String {
        val existing = current().clientId
        if (existing.isNotBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { prefs ->
            if (prefs[KEY_CLIENT_ID] == null) {
                prefs[KEY_CLIENT_ID] = fresh
            }
        }
        return current().clientId.ifBlank { fresh }
    }

    /** Lưu deviceToken sau ghép đôi thành công. */
    suspend fun saveDeviceToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEVICE_TOKEN] = token }
    }

    /**
     * Quên token (bị UNPAIR hoặc ghép đôi lại) — thiết bị quay về trạng thái
     * CHƯA ghép đôi, handshake sau sẽ bị từ chối cho tới khi ghép đôi mới.
     */
    suspend fun clearDeviceToken() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_DEVICE_TOKEN) }
    }

    /** Snapshot cài đặt. */
    data class Settings(
        val host: String,
        val port: Int,
        val clientId: String,
        val deviceToken: String?
    )
}
