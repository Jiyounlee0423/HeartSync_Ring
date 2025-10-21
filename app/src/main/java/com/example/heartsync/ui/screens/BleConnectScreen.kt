// app/src/main/java/com/example/heartsync/ui/screens/BleConnectScreen.kt
package com.example.heartsync.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.data.model.BleDevice
import com.example.heartsync.ui.components.StatusCard
import com.example.heartsync.viewmodel.BleViewModel
import com.example.heartsync.data.DevicePrefs

// DualRing 상태 확인
import com.example.heartsync.viewmodel.DualRingViewModel
import com.example.heartsync.ble.ConnState

@Composable
fun BleConnectScreen(
    vm: BleViewModel,
    onDone: (() -> Unit)? = null,
    dualVm: DualRingViewModel = viewModel()
) {
    val connStates by dualVm.connStates.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val results  by vm.scanResults.collectAsStateWithLifecycle()
    val conn     by vm.connectionState.collectAsStateWithLifecycle()

    val ctx   = LocalContext.current
    val prefs = remember { DevicePrefs(ctx) }
    val scope = rememberCoroutineScope()

    val leftMac  by prefs.leftMac.collectAsStateWithLifecycle(initialValue = null)
    val rightMac by prefs.rightMac.collectAsStateWithLifecycle(initialValue = null)

    // DualRing 연결상태
    val ringStates by dualVm.connStates.collectAsStateWithLifecycle(initialValue = emptyMap())

    val snackbar = remember { SnackbarHostState() }

    // 권한 런처
    val requiredPerms = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        list.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    // 화면 진입 시 DualRing 연결 시도
    LaunchedEffect(Unit) { dualVm.start() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── 스캔/상태 카드: 스캔 플래그에 따라 제목/버튼을 즉시 반영 ──
            val title = when {
                scanning -> "스캔 중…"
                conn is PpgBleClient.ConnectionState.Failed ->
                    "연결 실패: ${(conn as PpgBleClient.ConnectionState.Failed).reason}"
                else -> "스캔 대기"
            }
            val btnText = if (scanning) "스캔 중지" else "스캔 시작"
            StatusCard(
                icon = if (scanning) "success" else "error",
                title = title,
                buttonText = btnText,
                onClick = {
                    if (scanning) vm.stopScan()
                    else {
                        permLauncher.launch(requiredPerms)
                        vm.startScan()
                    }
                }
            )

            // ── 지정된 기기 요약 (이름만 표시 / 주소 숨김) ──
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("지정된 기기", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))

                    val leftState  = connStates["left"]
                    val rightState = connStates["right"]
                    val leftStatus  = when (leftState) {
                        is ConnState.Connected -> "연결됨 (${leftState.name ?: "Unknown"})"
                        is ConnState.Reconnecting -> "재연결 중"
                        is ConnState.Disconnected -> "끊김"
                        else -> if (leftMac != null) "등록됨" else "미지정"
                    }
                    val rightStatus = when (rightState) {
                        is ConnState.Connected -> "연결됨 (${rightState.name ?: "Unknown"})"
                        is ConnState.Reconnecting -> "재연결 중"
                        is ConnState.Disconnected -> "끊김"
                        else -> if (rightMac != null) "등록됨" else "미지정"
                    }

                    Text("왼손: $leftStatus")
                    Text("오른손: $rightStatus")
                }
            }

            // ── 스캔 리스트 (남은 공간 스크롤) ──
            DeviceSelectList(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                items = results,
                leftMac = leftMac,
                rightMac = rightMac,
                onPickLeft  = { dev -> scope.launch { prefs.setLeft(dev.address) } },
                onPickRight = { dev -> scope.launch { prefs.setRight(dev.address) } }
            )

            // ── 하단 액션 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { scope.launch { prefs.clear() } },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("초기화") }

                Button(
                    onClick = {
                        val leftOk  = ringStates["left"]  is ConnState.Connected
                        val rightOk = ringStates["right"] is ConnState.Connected
                        when {
                            leftMac == null || rightMac == null ->
                                scope.launch { snackbar.showSnackbar("왼손/오른손을 모두 지정하세요.") }
                            leftOk && rightOk ->
                                onDone?.invoke()
                            else -> {
                                val failSides = buildList {
                                    if (!leftOk) add("왼손")
                                    if (!rightOk) add("오른손")
                                }.joinToString(", ")
                                scope.launch { snackbar.showSnackbar("$failSides 연결이 아직 완료되지 않았습니다.") }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = leftMac != null && rightMac != null
                ) { Text("완료") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceSelectList(
    modifier: Modifier = Modifier,
    items: List<BleDevice>,
    leftMac: String?,
    rightMac: String?,
    onPickLeft: (BleDevice) -> Unit,
    onPickRight: (BleDevice) -> Unit
) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 6.dp)
        ) {
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("발견된 장치 (${items.size})", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("항목에서 왼/오 버튼으로 지정", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            items(items, key = { it.address }) { dev ->
                val isLeftSel  = dev.address == leftMac
                val isRightSel = dev.address == rightMac

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(dev.name ?: "Unknown", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(dev.address, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = { onPickLeft(dev) },  label = { Text(if (isLeftSel) "왼손 ✓" else "왼손") })
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { onPickRight(dev) }, label = { Text(if (isRightSel) "오른손 ✓" else "오른손") })
                }
                HorizontalDivider(thickness = 0.6.dp)
            }
        }
    }
}
