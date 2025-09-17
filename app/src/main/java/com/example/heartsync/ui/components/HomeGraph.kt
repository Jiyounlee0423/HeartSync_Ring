// app/src/main/java/com/example/heartsync/ui/components/HomeGraph.kt
package com.example.heartsync.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.heartsync.data.model.GraphState
import kotlin.math.max

@Composable
fun HomeGraphSection(
    left: List<Float>,
    right: List<Float>,
    modifier: Modifier = Modifier
) {
    val hasData = left.isNotEmpty() || right.isNotEmpty()

    val containerMod = modifier
        .fillMaxWidth()
        .height(220.dp)
        .padding(horizontal = 16.dp)

    if (!hasData) {
        Box(containerMod, contentAlignment = Alignment.Center) {
            Text("실시간 데이터를 기다리는 중…")
        }
        return
    }

    val all = left + right
    val minY = all.minOrNull() ?: 0f
    val maxY = all.maxOrNull() ?: 0f
    val range = (maxY - minY).takeIf { it > 1e-6f } ?: 1f

    Canvas(containerMod) {
        val maxCount = max(left.size, right.size).coerceAtLeast(1)
        val stepX = size.width / maxCount
        fun mapY(v: Float) = size.height - ((v - minY) / range) * size.height

        val pathL = Path()
        left.forEachIndexed { i, v ->
            val x = i * stepX
            val y = mapY(v)
            if (i == 0) pathL.moveTo(x, y) else pathL.lineTo(x, y)
        }
        drawPath(pathL, Color(0xFF3B82F6))

        val pathR = Path()
        right.forEachIndexed { i, v ->
            val x = i * stepX
            val y = mapY(v)
            if (i == 0) pathR.moveTo(x, y) else pathR.lineTo(x, y)
        }
        drawPath(pathR, Color(0xFF10B981))
    }
}


@Composable
private fun DualLineGraph(
    left: List<Float>,
    right: List<Float>,
    modifier: Modifier = Modifier
) {
    // ✅ 색/스타일은 Composable에서 미리 읽어 변수로 넘김 (Canvas 안에서 호출 금지)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val leftColor = MaterialTheme.colorScheme.primary
    val rightColor = MaterialTheme.colorScheme.tertiary

    val (minY, maxY) = remember(left, right) {
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (v in left) { if (v < minV) minV = v; if (v > maxV) maxV = v }
        for (v in right){ if (v < minV) minV = v; if (v > maxV) maxV = v }
        if (minV == Float.POSITIVE_INFINITY) 0f to 1f else minV to maxV
    }

    val yRange = if (maxY == minY) 1f else (maxY - minY)
    val n = maxOf(left.size, right.size).coerceAtLeast(2)

    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val w = size.width
        val h = size.height
        val paddingX = 24f
        val paddingY = 18f
        val plotW = w - paddingX * 2
        val plotH = h - paddingY * 2

        // 가로 그리드
        val grid = 4
        repeat(grid + 1) { i ->
            val y = paddingY + plotH * i / grid
            drawLine(
                color = gridColor,       // ✅ 미리 뽑아둔 색 사용
                start = Offset(paddingX, y),
                end = Offset(paddingX + plotW, y),
                strokeWidth = 1f
            )
        }

        fun yMap(v: Float): Float {
            val norm = (v - minY) / yRange
            return paddingY + plotH * (1f - norm)
        }
        fun xAt(i: Int): Float {
            val denom = (n - 1).coerceAtLeast(1)
            return paddingX + plotW * i / denom
        }

        // Left 라인 (있을 때만)
        if (left.isNotEmpty()) {
            val pathL = Path()
            left.forEachIndexed { i, v ->
                val p = Offset(xAt(i.coerceAtMost(n - 1)), yMap(v))
                if (i == 0) pathL.moveTo(p.x, p.y) else pathL.lineTo(p.x, p.y)
            }
            drawPath(
                path = pathL,
                color = leftColor,       // ✅ 미리 뽑아둔 색 사용
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Right 라인 (있을 때만)
        if (right.isNotEmpty()) {
            val pathR = Path()
            right.forEachIndexed { i, v ->
                val p = Offset(xAt(i.coerceAtMost(n - 1)), yMap(v))
                if (i == 0) pathR.moveTo(p.x, p.y) else pathR.lineTo(p.x, p.y)
            }
            drawPath(
                path = pathR,
                color = rightColor,      // ✅ 미리 뽑아둔 색 사용
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
