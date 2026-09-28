package com.parental.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Màn hình chính (Jetpack Compose — yêu cầu nhiệm vụ Mục 3):
 * ô nhập IP server (lưu DataStore), nút Kết nối/Ngắt kết nối, đèn trạng
 * thái 3 mức, thông tin thiết bị (tên/OS/ID) và khối ghép đôi PIN.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Kiểm soát gia đình",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        // --- Đèn trạng thái 3 mức ---
        StatusIndicator(state.connectionState)

        // --- Nhập địa chỉ server ---
        Card(colors = CardDefaults.cardColors()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = viewModel::onHostChanged,
                    label = { Text("IP máy chủ (máy tính phụ huynh)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChanged,
                    label = { Text("Cổng (mặc định 8888)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::connect,
                        modifier = Modifier.weight(1f),
                        enabled = state.connectionState != ConnectionStateUi.CONNECTED,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1B4D8F)
                        )
                    ) {
                        Text("Kết nối")
                    }
                    Button(
                        onClick = viewModel::disconnect,
                        modifier = Modifier.weight(1f),
                        enabled = state.connectionState != ConnectionStateUi.DISCONNECTED,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8F1B1B)
                        )
                    ) {
                        Text("Ngắt kết nối")
                    }
                }
            }
        }

        // --- Ghép đôi PIN (chỉ hiện khi CHƯA ghép đôi) ---
        if (!state.paired) {
            Card(colors = CardDefaults.cardColors()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Ghép đôi lần đầu", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Nhập mã PIN 6 số do phụ huynh sinh từ máy chủ (nút Thêm thiết bị mới)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = viewModel::onPinChanged,
                        label = { Text("Mã PIN 6 số") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Button(
                        onClick = viewModel::pairWithPin,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ghép đôi & Kết nối")
                    }
                }
            }
        }

        // --- Thông tin thiết bị (hiển thị cho HANDSHAKE) ---
        Card(colors = CardDefaults.cardColors()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Thông tin thiết bị", fontWeight = FontWeight.SemiBold)
                InfoRow("Tên thiết bị", state.deviceName.ifBlank { "—" })
                InfoRow("Hệ điều hành", state.osVersion.ifBlank { "—" })
                InfoRow("Định danh (clientId)", state.clientId.ifBlank { "—" })
                InfoRow("Trạng thái ghép đôi", if (state.paired) "Đã ghép đôi" else "Chưa ghép đôi")
            }
        }

        state.message?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3CD)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
    }
}

/** Đèn trạng thái: Đã kết nối (xanh) / Đang kết nối (vàng) / Ngắt (đỏ). */
@Composable
fun StatusIndicator(state: ConnectionStateUi) {
    val (color, label) = when (state) {
        ConnectionStateUi.CONNECTED -> Color(0xFF2E7D32) to "Đã kết nối"
        ConnectionStateUi.CONNECTING -> Color(0xFFF9A825) to "Đang kết nối"
        ConnectionStateUi.DISCONNECTED -> Color(0xFFC62828) to "Đang ngắt kết nối"
    }
    Card(colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(150.dp)
        )
        Text(text = value, fontSize = 13.sp)
    }
}
