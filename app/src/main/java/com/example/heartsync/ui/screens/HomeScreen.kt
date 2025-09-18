// app/src/main/java/com/example/heartsync/ui/screens/HomeScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.viewmodel.BleViewModel
import com.example.heartsync.ui.components.StatusCard
import com.example.heartsync.data.remote.PpgPoint
import com.example.heartsync.data.remote.PpgRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.*

/* --------------------------- Screen --------------------------- */

@Composable
fun HomeScreen(
    onClickBle: () -> Unit,
    bleVm: BleViewModel,
    onStartMeasure: () -> Unit,
    vm: HomeViewModel = viewModel(
        factory = HomeVmFactory(PpgRepository(FirebaseFirestore.getInstance()))
    )
) {
    val scroll = rememberScrollState()

    val conn by bleVm.connectionState.collectAsState()
    val isConnected = conn is PpgBleClient.ConnectionState.Connected
    val deviceName = (conn as? PpgBleClient.ConnectionState.Connected)?.device?.name ?: "Unknown"

    // ★ 연결 상태가 바뀔 때마다 VM에 알려서 live 버퍼를 정리
    LaunchedEffect(isConnected) {
        vm.onBleConnectionChanged(isConnected)
    }

    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val live by vm.live.collectAsState()                          // 실시간 버퍼
    val pointsDisplay by vm.display.collectAsState()              // 화면에 실제 뿌릴 데이터

    var window by rememberSaveable { mutableStateOf(150) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 날짜 칩
        DateChip()

        // 상태 배너(컴팩트)
        StatusCard(
            icon = if (isConnected) "success" else "error",
            title = if (isConnected) "연결됨: $deviceName" else "기기 연결이 필요합니다.",
            buttonText = if (isConnected) "연결 해제" else "기기 연결",
            onClick = { if (isConnected) bleVm.disconnect() else onClickBle() },
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            contentPadding = PaddingValues(3.dp),
            compact = true
        )

        // 제목
        Text(
            "오늘의 실시간 PPG (Left / Right)",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (!isLoggedIn) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) { Text("로그인이 필요합니다.") }
        } else {
            // 통계 + 소스 라벨
            val last = pointsDisplay.lastOrNull()
            val sourceLabel = if (live.isNotEmpty() && isConnected) "실시간(BLE)" else "기록(Firebase)" // ★ 연결 off면 기록으로

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("샘플 수: ${pointsDisplay.size}", modifier = Modifier.weight(1f))
                Text(
                    sourceLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "최근 L/R: " +
                            "${last?.left?.let { "%.2f".format(it) } ?: "-"} / " +
                            "${last?.right?.let { "%.2f".format(it) } ?: "-"}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }

            // 윈도우 선택
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 150, 200).forEach { w ->
                    val selected = window == w
                    Button(
                        onClick = { window = w },
                        enabled = !selected,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (selected) "표시 $w (선택됨)" else "표시 $w") }
                }
            }

            // 그래프 (display 기준)
            HomeGraphSection(points = pointsDisplay, window = window)

            Text(
                "같은 날짜의 모든 세션(S_YYYYMMDD_*) records를 합쳐 시간순으로 표시합니다.",
                style = MaterialTheme.typography.bodySmall
            )
        }

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

/* --------------------------- Widgets --------------------------- */

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

/* --------------------------- ViewModel --------------------------- */

class HomeViewModel(
    private val repo: PpgRepository
) : ViewModel() {

    // Firebase(오늘) 시리즈
    private val _today = MutableStateFlow<List<PpgPoint>>(emptyList())
    val today: StateFlow<List<PpgPoint>> = _today.asStateFlow()

    // 실시간(BLE) 시리즈
    private val _live = MutableStateFlow<List<PpgPoint>>(emptyList())
    val live: StateFlow<List<PpgPoint>> = _live.asStateFlow()

    // 로그인 상태
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    // 화면용 최종 시리즈 (live가 있으면 live 우선)
    val display: StateFlow<List<PpgPoint>> =
        combine(today, live) { day, livePts ->
            if (livePts.isNotEmpty()) livePts else day
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        _isLoggedIn.value = uid != null

        if (uid != null) {
            // 1) 오늘 날짜의 Firebase 수신
            viewModelScope.launch {
                repo.observeDayPpg(uid, LocalDate.now())
                    .collectLatest { _today.value = it }
            }

            // 2) BLE 실시간 수신
            viewModelScope.launch {
                PpgRepository.smoothedFlow.collect { (l, r) ->
                    addLivePoint(l, r)
                }
            }
        }
    }

    /** BLE 연결 상태 반영: 끊기면 live 버퍼 비워서 자동으로 Firebase로 fallback */
    fun onBleConnectionChanged(connected: Boolean) {
        if (!connected) {
            _live.value = emptyList() // ★ 즉시 비워서 display가 today로 전환됨
        }
    }

    /** 실시간 버퍼에 포인트 추가(최대 1000개 유지) */
    private fun addLivePoint(l: Float, r: Float) {
        val now = System.currentTimeMillis()
        val newPt = PpgPoint(ts = now, left = l.toDouble(), right = r.toDouble(), serverTs = now)
        _live.update { cur ->
            val capped = if (cur.size >= 1000) cur.drop(cur.size - 999) else cur
            capped + newPt
        }
    }
}

/* --------------------------- VM Factory --------------------------- */

class HomeVmFactory(
    private val repo: PpgRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repo) as T
    }
}

/* --------------------------- Graph --------------------------- */

@Composable
fun HomeGraphSection(
    points: List<PpgPoint>,
    window: Int = 600
) {
    if (points.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) { Text("데이터가 없습니다") }
        return
    }

    // 최근 window개만 사용
    val slice = if (points.size > window) points.takeLast(window) else points

    // y축 범위 (유효 값만)
    val ys = slice.flatMap { listOfNotNull(it.left, it.right) }
    val (yLo, yHi) = if (ys.isNotEmpty()) {
        val minY = ys.minOrNull()!!
        val maxY = ys.maxOrNull()!!
        if (minY == maxY) (minY - 1.0) to (maxY + 1.0) else {
            val pad = (maxY - minY) * 0.05
            (minY - pad) to (maxY + pad)
        }
    } else 0.0 to 1.0

    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    // x축 라벨: server_ts → HH:mm:ss (없으면 ts)
    val labelCount = when {
        slice.size <= 300  -> 4
        slice.size <= 600  -> 6
        slice.size <= 1000 -> 8
        else               -> 10
    }
    val labelIndices =
        if (labelCount <= 1) listOf(0)
        else (0 until labelCount).map { i -> ((slice.size - 1).toFloat() * i / (labelCount - 1)).toInt() }

    val sdf = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    fun labelAt(idx: Int): String {
        val ms = slice[idx].serverTs ?: slice[idx].ts
        return sdf.format(java.util.Date(ms))
    }
    val xLabels = labelIndices.map(::labelAt)

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val n = slice.size
                if (n < 2) return@Canvas

                val w = size.width
                val h = size.height
                val stepX = w / (n - 1)

                fun yMap(v: Double): Float {
                    val t = ((v - yLo) / (yHi - yLo)).coerceIn(0.0, 1.0)
                    return (h - (t * h)).toFloat()
                }

                // Left
                var prevL: Offset? = null
                slice.forEachIndexed { i, p ->
                    val v = p.left ?: return@forEachIndexed
                    val cur = Offset(i * stepX, yMap(v))
                    prevL?.let { drawLine(color = leftColor, start = it, end = cur, strokeWidth = 2f) }
                    prevL = cur
                }

                // Right
                var prevR: Offset? = null
                slice.forEachIndexed { i, p ->
                    val v = p.right ?: return@forEachIndexed
                    val cur = Offset(i * stepX, yMap(v))
                    prevR?.let { drawLine(color = rightColor, start = it, end = cur, strokeWidth = 3.5f) }
                    prevR = cur
                }

                // 수평 그리드
                val gridLines = 4
                val stepVal = (yHi - yLo) / gridLines
                repeat(gridLines + 1) { idx ->
                    val y = yMap(yLo + stepVal * idx)
                    drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            xLabels.forEach { label ->
                Text(text = label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
