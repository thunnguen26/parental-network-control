package com.parental.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parental.android.data.ConnectionSettingsStore
import com.parental.android.device.AndroidDeviceInfoProvider
import com.parental.android.network.PairingClient
import com.parental.android.network.PairingResult
import com.parental.android.protocol.PairRequestPayload
import com.parental.android.service.ConnectionForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trạng thái UI màn hình chính. */
data class MainUiState(
    val host: String = ConnectionSettingsStore.DEFAULT_HOST,
    val port: String = ConnectionSettingsStore.DEFAULT_PORT.toString(),
    val connectionState: ConnectionStateUi = ConnectionStateUi.DISCONNECTED,
    val deviceName: String = "",
    val osVersion: String = "",
    val clientId: String = "",
    val paired: Boolean = false,
    val pin: String = "",
    val message: String? = null
)

/** Trạng thái hiển thị (map từ ConnectionState của engine). */
enum class ConnectionStateUi { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * ViewModel màn hình chính:
 * - prefill host/port từ DataStore (không nhập lại — yêu cầu nhiệm vụ);
 * - Kết nối/Ngắt qua [ConnectionForegroundService] (socket sống khi app ẩn);
 * - ghép đôi PIN one-shot qua [PairingClient] (lưu token vào DataStore);
 * - theo dõi trạng thái service qua polling lastState (đơn giản, đủ dùng).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = ConnectionSettingsStore(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsStore.current()
            val clientId = settingsStore.getOrCreateClientId()
            val info = AndroidDeviceInfoProvider.collect(application)
            _uiState.update {
                it.copy(
                    host = settings.host,
                    port = settings.port.toString(),
                    deviceName = info.deviceName,
                    osVersion = info.osVersion,
                    clientId = clientId,
                    paired = settings.deviceToken != null
                )
            }
            // Poll trạng thái service 500ms — không cần thêm thư viện flow bridge
            viewModelScope.launch {
                while (true) {
                    val serviceState = ConnectionForegroundService.lastState
                    _uiState.update { prev ->
                        prev.copy(connectionState = when (serviceState) {
                            com.parental.android.network.ConnectionState.CONNECTED -> ConnectionStateUi.CONNECTED
                            com.parental.android.network.ConnectionState.CONNECTING -> ConnectionStateUi.CONNECTING
                            com.parental.android.network.ConnectionState.DISCONNECTED -> ConnectionStateUi.DISCONNECTED
                        })
                    }
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }

    /** Người dùng sửa ô nhập — chỉ cập nhật state (lưu khi bấm Kết nối). */
    fun onHostChanged(value: String) = _uiState.update { it.copy(host = value) }

    fun onPortChanged(value: String) = _uiState.update { it.copy(port = value) }

    fun onPinChanged(value: String) = _uiState.update { it.copy(pin = value.take(6)) }

    /** [Kết nối] — lưu địa chỉ + khởi động Foreground Service. */
    fun connect() {
        val state = _uiState.value
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull() ?: ConnectionSettingsStore.DEFAULT_PORT
        if (host.isBlank()) {
            _uiState.update { it.copy(message = "Vui lòng nhập địa chỉ IP của máy chủ") }
            return
        }
        viewModelScope.launch {
            settingsStore.saveServerAddress(host, port)
            ConnectionForegroundService.start(getApplication(), host, port)
            _uiState.update { it.copy(connectionState = ConnectionStateUi.CONNECTING, message = null) }
        }
    }

    /** [Ngắt kết nối] — dừng service + engine. */
    fun disconnect() {
        ConnectionForegroundService.stop(getApplication())
        _uiState.update { it.copy(connectionState = ConnectionStateUi.DISCONNECTED) }
    }

    /**
     * [Ghép đôi bằng PIN] — one-shot PAIR_REQUEST qua server; thành công lưu
     * token vào DataStore rồi kết nối luôn.
     */
    fun pairWithPin() {
        val state = _uiState.value
        val pin = state.pin.trim()
        if (pin.length != 6 || pin.any { !it.isDigit() }) {
            _uiState.update { it.copy(message = "Mã PIN phải gồm 6 chữ số") }
            return
        }
        val host = state.host.trim()
        val port = state.port.trim().toIntOrNull() ?: ConnectionSettingsStore.DEFAULT_PORT
        viewModelScope.launch {
            try {
                val clientId = settingsStore.getOrCreateClientId()
                val info = AndroidDeviceInfoProvider.collect(getApplication())
                val result: PairingResult = withContext(Dispatchers.IO) {
                    PairingClient(host, port).pair(
                        PairRequestPayload(
                            pin = pin,
                            deviceName = info.deviceName,
                            deviceType = info.deviceType,
                            osVersion = info.osVersion,
                            ipAddress = info.ipAddress,
                            macAddress = info.macAddress
                        ),
                        clientId
                    )
                }
                if (result.success && result.deviceToken != null) {
                    settingsStore.saveDeviceToken(result.deviceToken)
                    _uiState.update {
                        it.copy(paired = true, pin = "", message = "Ghép đôi thành công!")
                    }
                    connect() // có token → kết nối HANDSHAKE luôn
                } else {
                    _uiState.update { it.copy(message = "Ghép đôi bị từ chối: ${result.message}") }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(message = "Không kết nối được server: ${e.message}")
                }
            }
        }
    }

    /** Xóa thông báo. */
    fun clearMessage() = _uiState.update { it.copy(message = null) }
}
