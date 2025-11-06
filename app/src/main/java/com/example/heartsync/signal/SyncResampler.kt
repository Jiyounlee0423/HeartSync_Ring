package com.example.heartsync.signal

import com.example.heartsync.ble.PpgSample
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min

data class SyncedPoint(
    val tMonoS: Double,   // 공통 시간축(모노토닉)
    val left: Float,
    val right: Float
)

class SyncResampler(
    private val fsMs: Int = 20,       // 20ms → 50 Hz
    private val windowS: Double = 10.0
) {
    private val dtS = fsMs / 1000.0   // 리샘플 간격(초)

    /**
     * 두 손 PPG 플로우를 받아 20ms 격자에 선형보간 후 (left,right) 동기 포인트 방출.
     * - 경계는 최근값 유지(ffill)
     */
    fun fuse(leftFlow: Flow<PpgSample>, rightFlow: Flow<PpgSample>): Flow<SyncedPoint> = channelFlow {
        val leftBuf  = ArrayDeque<PpgSample>()
        val rightBuf = ArrayDeque<PpgSample>()
        var lastEmitT = Double.NaN

        fun prune(now: Double) {
            val lim = now - windowS * 1.5
            while (leftBuf.isNotEmpty()  && leftBuf.first().tMonoS  < lim) leftBuf.removeFirst()
            while (rightBuf.isNotEmpty() && rightBuf.first().tMonoS < lim) rightBuf.removeFirst()
        }

        // filt(필터 후) 값을 보간 — 필요시 raw로 바꿔도 됨
        fun interp(buf: ArrayDeque<PpgSample>, t: Double): Float? {
            if (buf.isEmpty()) return null
            val lo0 = buf.first()
            val hi0 = buf.last()
            if (t <= lo0.tMonoS) return lo0.filt
            if (t >= hi0.tMonoS) return hi0.filt

            var prev = lo0
            for (s in buf) {
                if (s.tMonoS >= t) {
                    val lo = prev
                    val hi = s
                    val w = ((t - lo.tMonoS) / (hi.tMonoS - lo.tMonoS)).coerceIn(0.0, 1.0)
                    val v = ((1.0 - w) * lo.filt + w * hi.filt).toFloat()
                    return v
                }
                prev = s
            }
            return buf.last().filt
        }

        val s = this

        val jobL = launch {
            leftFlow.collect { l ->
                leftBuf.addLast(l)
                // 반대 버퍼 최신 t와 비교해서 오래된 데이터 정리
                val latestOther = rightBuf.lastOrNull()?.tMonoS ?: l.tMonoS
                prune(max(l.tMonoS, latestOther))

                val tNow = min(leftBuf.last().tMonoS, rightBuf.lastOrNull()?.tMonoS ?: leftBuf.last().tMonoS)
                if (lastEmitT.isNaN()) lastEmitT = tNow
                while (tNow - lastEmitT >= dtS) {
                    val t = lastEmitT + dtS
                    val vl = interp(leftBuf, t)
                    val vr = interp(rightBuf, t)
                    if (vl != null && vr != null) s.trySend(SyncedPoint(t, vl, vr))
                    lastEmitT = t
                }
            }
        }

        val jobR = launch {
            rightFlow.collect { r ->
                rightBuf.addLast(r)
                val latestOther = leftBuf.lastOrNull()?.tMonoS ?: r.tMonoS
                prune(max(r.tMonoS, latestOther))

                val tNow = min(rightBuf.last().tMonoS, leftBuf.lastOrNull()?.tMonoS ?: rightBuf.last().tMonoS)
                if (lastEmitT.isNaN()) lastEmitT = tNow
                while (tNow - lastEmitT >= dtS) {
                    val t = lastEmitT + dtS
                    val vl = interp(leftBuf, t)
                    val vr = interp(rightBuf, t)
                    if (vl != null && vr != null) s.trySend(SyncedPoint(t, vl, vr))
                    lastEmitT = t
                }
            }
        }

        awaitClose {
            jobL.cancel()
            jobR.cancel()
        }
    }
}
