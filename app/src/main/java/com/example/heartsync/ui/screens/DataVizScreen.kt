// app/src/main/java/com/example/heartsync/ui/screens/DataVizScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// Compose 그래픽 타입을 alias로 명시
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.geometry.Offset as ComposeOffset
import androidx.compose.ui.graphics.drawscope.Stroke

import com.example.heartsync.ui.DataBizViewModel
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DataVizScreen(
    deviceId: String,                         // 예: "fpb8XE0z2ifrQJGV4liKw31grQR2"
    vm: DataBizViewModel = viewModel(),       // 이전에 만든 ViewModel 재사용
) {
    if (deviceId.isNullOrBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("장치가 선택되지 않았습니다.")
            Text("MainActivity에서 deviceId를 넘겨주세요.")
        }
        return
    }
    val points by vm.points.collectAsState()

    // 같은 날짜(Asia/Seoul)의 모든 세션 records 합쳐서 실시간 구독
    LaunchedEffect(deviceId) {
        vm.startListenSameDateSessions(
            deviceId = deviceId,
            date      = LocalDate.now(ZoneId.of("Asia/Seoul")),
            tsField   = "timestamp",
            leftField = "smooted_left",
            rightField= "smooted_right"
        )
    }
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }

    // === Column 안에 그래프/텍스트 배치 ===
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("센서 데이터 시각화", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        // 그래프 영역 (Column 안)
        LineChart(
            dataLeft  = points.map { it.t to it.left.toFloat() },
            dataRight = points.map { it.t to it.right.toFloat() },
            modifier  = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        Spacer(Modifier.height(8.dp))
        Text("표본 개수: ${points.size}")

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { /* TODO: 다른 액션 */ }) {
            Text("새로고침 / 임시 버튼")
        }
    }
}

/** 아주 가벼운 2채널 라인 차트 (추가 라이브러리 없이 Canvas 사용) */
@Composable
private fun LineChart(
    dataLeft: List<Pair<Long, Float>>,
    dataRight: List<Pair<Long, Float>>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (dataLeft.isEmpty() && dataRight.isEmpty()) return@Canvas

        val allX = (dataLeft + dataRight).map { it.first }
        val tMin = allX.minOrNull() ?: 0L
        val tMax = allX.maxOrNull() ?: (tMin + 1L)
        val tSpan = (tMax - tMin).coerceAtLeast(1L).toFloat()

        val yAll = (dataLeft.map { it.second } + dataRight.map { it.second })
        val yMin = yAll.minOrNull() ?: 0f
        val yMax = yAll.maxOrNull() ?: 1f
        val ySpan = (yMax - yMin).takeIf { it > 1e-6f } ?: 1f

        fun Pair<Long, Float>.toOffset(): ComposeOffset {
            val x = ((first - tMin) / tSpan) * size.width
            val y = size.height - ((second - yMin) / ySpan) * size.height
            return ComposeOffset(x, y)
        }

        fun buildPath(data: List<Pair<Long, Float>>): ComposePath? {
            if (data.isEmpty()) return null
            val p = ComposePath()
            val first = data.first().toOffset()
            p.moveTo(first.x, first.y)
            for (pt in data.drop(1)) {
                val o = pt.toOffset()
                p.lineTo(o.x, o.y)
            }
            return p
        }

        val pathL = buildPath(dataLeft)
        val pathR = buildPath(dataRight)
        val stroke = Stroke(width = 3f)

        pathL?.let {
            drawPath(path = it, color = Color(0xFF4CAF50), style = stroke)
        }
        pathR?.let {
            drawPath(path = it, color = Color(0xFF2196F3), alpha = 0.7f, style = stroke)
        }
    }
}
