// app/src/main/java/com/example/heartsync/ui/screens/HomeScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.viewmodel.BleViewModel
import com.example.heartsync.ui.components.StatusCard
import com.example.heartsync.ui.components.HomeGraphSection   // ★ 추가: 그래프 컴포넌트 임포트
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    onClickBle: () -> Unit,
    bleVm: BleViewModel,   // MainActivity에서 주입 (전역 공유)
    onStartMeasure: () -> Unit
) {
    val conn by bleVm.connectionState.collectAsState()


    val isConnected = conn is PpgBleClient.ConnectionState.Connected
    val deviceName = (conn as? PpgBleClient.ConnectionState.Connected)?.device?.name ?: "Unknown"
    val graphState by bleVm.graphState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 날짜 칩
        DateChip()

        // 상태 배너
        StatusCard(
            icon = if (isConnected) "success" else "error",
            title = if (isConnected) "연결됨: $deviceName" else "기기 연결이 필요합니다.",
            buttonText = if (isConnected) "연결 해제" else "기기 연결",
            onClick = {
                if (isConnected) {
                    // 측정 서비스까지 돌리고 있다면 먼저 멈춤 (있을 때만)
                    // bleVm.stopMeasure()
                    bleVm.disconnect()
                } else {
                    onClickBle()    // BLE 연결 화면으로 이동
                }
            }
        )


        // ★ 여기서부터 실제 그래프
        // 데이터가 없으면 HomeGraphSection이 "데이터가 없습니다"를 표시하고,
        // 있으면 smoothed_L/R 라인만 그립니다.
        // 그래프 섹션
        HomeGraphSection(
            left = graphState.smoothedL,
            right = graphState.smoothedR,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        )
        Text("L=${graphState.smoothedL.size}  R=${graphState.smoothedR.size}")


        Button(
            onClick = onStartMeasure,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = isConnected
        ) {
            Text("측정 시작(반응성 충혈 test)")
        }
    }
}

@Composable
private fun DateChip() {
    val today = remember {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        sdf.format(Date())
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = today,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
