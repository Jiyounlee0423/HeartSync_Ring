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
import androidx.compose.ui.text.buildAnnotatedString
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --------------------------- Screen ---------------------------

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

    val conn by bleVm.connectionState.collectAsStateWithLifecycle()
    val isConnected = conn is PpgBleClient.ConnectionState.Connected
    val deviceName = (conn as? PpgBleClient.ConnectionState.Connected)?.device?.name ?: "Unknown"

    // ALERT 팝업 상태
    var currentAlert by remember { mutableStateOf<PpgRepository.UiAlert?>(null) }
    LaunchedEffect(Unit) {
        bleVm.alerts.collect { a ->
            currentAlert = a
            launch {
                kotlinx.coroutines.delay(4000)
                if (currentAlert === a) currentAlert = null
            }
        }
    }

    LaunchedEffect(isConnected) {
        vm.onBleConnectionChanged(isConnected)
    }

    val isLoggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val pointsDisplay by vm.display.collectAsStateWithLifecycle()

    var window by rememberSaveable { mutableStateOf(150) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DateChip()

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
            val last = pointsDisplay.lastOrNull()
            val sourceLabel = if (live.isNotEmpty() && isConnected) "실시간(BLE)" else "기록(Firebase)"

            val twoLineMinHeight = with(LocalDensity.current) {
                // bodyMedium의 lineHeight × 2줄 × 여유계수(1.1~1.2)
                (MaterialTheme.typography.bodyMedium.lineHeight * 2f * 1.15f).toDp()
            }

            HeaderRow(
                pointsDisplaySize = pointsDisplay.size,
                sourceLabel = sourceLabel,
                lastLeft = last?.left,
                lastRight = last?.right
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 150, 200).forEach { w ->
                    val selected = window == w
                    Button(
                        onClick = { window = w },
                        enabled = !selected,
                        modifier = Modifier.weight(1f)
                    ) { Text("표시 $w") }
                }
            }

            HomeGraphSection(points = pointsDisplay, window = window)

            Text(
                "기록은 같은 날짜의 모든 세션(S_YYYYMMDD_*) records를 합쳐 시간순으로 표시합니다.",
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

    // ALERT 팝업
    currentAlert?.let { a ->
        val sideKo = if (a.side.equals("left", true)) "왼쪽" else "오른쪽"
        val title = when (a.alertType?.uppercase()) {
            "FLOW_IMBALANCE" -> "혈류 불균형 감지"
            "HR_ABNORMAL"    -> "심박 이상 감지"
            else             -> "이상 감지"
        }
        val body = buildString {
            append("${sideKo}에서 이상이 감지되었어요.")
            if (!a.alertType.isNullOrBlank()) append("\n유형: ${a.alertType}")
            if (a.reasons.isNotEmpty()) append("\n사유: ${a.reasons.joinToString(", ")}")
        }

        AlertDialog(
            onDismissRequest = { currentAlert = null },
            title = { Text(title) },
            text  = { Text(body) },
            confirmButton = {
                TextButton(onClick = { currentAlert = null }) { Text("확인") }
            }
        )
    }
}

@Composable
fun HeaderRow(
    pointsDisplaySize: Int,
    sourceLabel: String,
    lastLeft: Double?,
    lastRight: Double?
) {
    val density = LocalDensity.current

    // 2줄 기준 최소 높이(Dp) 계산: bodyMedium lineHeight × 2줄 × 여유계수
    val twoLineMinHeight = with(density) {
        (MaterialTheme.typography.bodyMedium.lineHeight.value * 2f * 1.15f).sp.toDp()
    }
    // 마지막 Text의 라인 간격(원하는 값으로 살짝 여유를 줌)
    val compactLineHeight = (MaterialTheme.typography.bodyMedium.lineHeight.value * 1.2f).sp

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = twoLineMinHeight),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 좌측: 샘플 수 (1줄 고정)
        Text(
            text = "샘플 수: $pointsDisplaySize",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )

        // 중앙: 소스 라벨 (1줄 고정)
        Text(
            text = sourceLabel,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge
        )

        // 우측: 항상 2줄(제목/값) 고정
        Text(
            text = buildAnnotatedString {
                append("최근 L/R:")
                append('\n')
                append(
                    "${lastLeft?.let { "%.2f".format(it) } ?: "-"} / " +
                            "${lastRight?.let { "%.2f".format(it) } ?: "-"}"
                )
            },
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            minLines = 2,
            maxLines = 2,           // 정확히 2줄
            softWrap = true,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(lineHeight = compactLineHeight)
            )
        )
    }
}

// --------------------------- DateChip ---------------------------

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

// --------------------------- HomeViewModel ---------------------------

class HomeViewModel(
    private val repo: PpgRepository
) : ViewModel() {
    private val _today = MutableStateFlow<List<PpgPoint>>(emptyList())
    val today: StateFlow<List<PpgPoint>> = _today.asStateFlow()

    private val _live = MutableStateFlow<List<PpgPoint>>(emptyList())
    val live: StateFlow<List<PpgPoint>> = _live.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val display: StateFlow<List<PpgPoint>> =
        combine(today, live) { day, livePts ->
            if (livePts.isNotEmpty()) livePts else day
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        _isLoggedIn.value = uid != null
        if (uid != null) {
            viewModelScope.launch {
                repo.observeDayPpg(uid, LocalDate.now())
                    .collectLatest { _today.value = it }
            }
            viewModelScope.launch {
                PpgRepository.smoothedFlow.collect { (t, l, r) ->
                    addLivePoint(t, l, r)
                }
            }
        }
    }

    fun onBleConnectionChanged(connected: Boolean) {
        if (!connected) _live.value = emptyList()
    }

    private fun addLivePoint(timeMillis: Long, l: Float, r: Float) {
        val newPt = PpgPoint(timeMillis, l.toDouble(), r.toDouble(), timeMillis)
        _live.update { cur -> (if (cur.size >= 1000) cur.drop(cur.size - 999) else cur) + newPt }
    }
}

// --------------------------- Factory ---------------------------

class HomeVmFactory(
    private val repo: PpgRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repo) as T
    }
}

// --------------------------- Graph ---------------------------

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

    val slice = if (points.size > window) points.takeLast(window) else points
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

    val labelCount = when {
        slice.size <= 300  -> 4
        slice.size <= 600  -> 6
        slice.size <= 1000 -> 8
        else               -> 10
    }
    val labelIndices =
        if (labelCount <= 1) listOf(0)
        else (0 until labelCount).map { i -> ((slice.size - 1).toFloat() * i / (labelCount - 1)).toInt() }

    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun labelAt(idx: Int): String {
        val ms = slice[idx].serverTime ?: slice[idx].time
        return sdf.format(Date(ms))
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

                var prevL: Offset? = null
                slice.forEachIndexed { i, p ->
                    val v = p.left ?: return@forEachIndexed
                    val cur = Offset(i * stepX, yMap(v))
                    prevL?.let { drawLine(color = leftColor, start = it, end = cur, strokeWidth = 2f) }
                    prevL = cur
                }

                var prevR: Offset? = null
                slice.forEachIndexed { i, p ->
                    val v = p.right ?: return@forEachIndexed
                    val cur = Offset(i * stepX, yMap(v))
                    prevR?.let { drawLine(color = rightColor, start = it, end = cur, strokeWidth = 3.5f) }
                    prevR = cur
                }

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