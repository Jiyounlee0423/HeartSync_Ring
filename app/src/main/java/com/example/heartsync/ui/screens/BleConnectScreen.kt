package com.example.heartsync.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
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
import com.example.heartsync.viewmodel.DualRingViewModel
import com.example.heartsync.ble.ConnState
import com.example.heartsync.service.MeasureService

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
    val snackbar = remember { SnackbarHostState() }

    val leftMac  by prefs.leftMac.collectAsStateWithLifecycle(initialValue = null)
    val rightMac by prefs.rightMac.collectAsStateWithLifecycle(initialValue = null)

    val ringStates by dualVm.connStates.collectAsStateWithLifecycle(initialValue = emptyMap())
    val events by vm.events.collectAsState(initial = null)

    // toast for events
    LaunchedEffect(events) {
        events?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

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
    ) { }

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

            // 지정된 기기 상태 요약
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("지정된 기기", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))

                    val leftState  = connStates["left"]
                    val rightState = connStates["right"]
                    val leftStatus = when (val s = leftState) {
                        is ConnState.Connected    -> "연결됨 (${s.name ?: "Unknown"})"
                        is ConnState.Reconnecting -> {
                            val who = s.name?.takeIf { it.isNotBlank() } ?: ""
                            "재연결 중${if (s.attempt > 1) " #${s.attempt}" else ""}" +
                                    (if (who.isNotBlank()) " ($who)" else "")
                        }
                        is ConnState.Disconnected -> "끊김"
                        else -> if (leftMac != null) "등록됨" else "미지정"
                    }

                    val rightStatus = when (val s = rightState) {
                        is ConnState.Connected    -> "연결됨 (${s.name ?: "Unknown"})"
                        is ConnState.Reconnecting -> {
                            val who = s.name?.takeIf { it.isNotBlank() } ?: ""
                            "재연결 중${if (s.attempt > 1) " #${s.attempt}" else ""}" +
                                    (if (who.isNotBlank()) " ($who)" else "")
                        }
                        is ConnState.Disconnected -> "끊김"
                        else -> if (rightMac != null) "등록됨" else "미지정"
                    }

                    Text("왼손: $leftStatus")
                    Text("오른손: $rightStatus")
                }
            }

            DeviceSelectList(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                items = results,
                leftMac = leftMac,
                rightMac = rightMac,
                onPickLeft  = { dev ->
                    if (vm.onSelectLeft(dev)) scope.launch { prefs.setLeft(dev.address) }
                },
                onPickRight = { dev ->
                    if (vm.onSelectRight(dev)) scope.launch { prefs.setRight(dev.address) }
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // 1) 저장된 좌/우 MAC 제거
                            prefs.clear()

                            // 2) 듀얼 클라이언트: 두 센서 동시 안전 분리
                            dualVm.resetAll()

                            // 3) (단일 모드 측정 서비스가 살아있다면) 강제 리셋
                            val intent = Intent(ctx, MeasureService::class.java)
                                .setAction(MeasureService.ACTION_RESET_ALL)
                            ctx.startService(intent)

                            snackbar.showSnackbar("블루투스 연결을 초기화했어요.")
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("초기화") }

                Button(
                    onClick = {
                        val leftOk  = ringStates["left"]  is ConnState.Connected
                        val rightOk = ringStates["right"] is ConnState.Connected
                        when {
                            leftMac == null || rightMac == null ->
                                scope.launch { snackbar.showSnackbar("왼손/오른손을 모두 지정하세요.") }
                            leftOk && rightOk -> onDone?.invoke()
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
                        Text("왼/오 버튼으로 지정", style = MaterialTheme.typography.labelMedium)
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
                        Text(dev.name ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
                        Text(dev.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = { onPickLeft(dev) },
                        label = { Text(if (isLeftSel) "왼손 ✓" else "왼손") })
                    Spacer(Modifier.width(6.dp))
                    AssistChip(onClick = { onPickRight(dev) },
                        label = { Text(if (isRightSel) "오른손 ✓" else "오른손") })
                }
                HorizontalDivider(thickness = 0.6.dp)
            }
        }
    }
}
