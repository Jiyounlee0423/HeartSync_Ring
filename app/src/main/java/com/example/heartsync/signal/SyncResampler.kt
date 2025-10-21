package com.example.heartsync.signal

import com.example.heartsync.ble.RawSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlinx.coroutines.channels.awaitClose

data class SyncedPoint(
    val tMonoS: Double,   // 공통 시간축(모노토닉)
    val left: Float,
    val right: Float
)

class SyncResampler(
    private val fsMs: Int = 20,
    private val windowS: Double = 10.0
) {
    private val dtS = fsMs / 1000.0

    /**
     * 두 손 raw 플로우를 받아 20ms 격자에 보간 후 (left,right) 동기 포인트를 방출.
     * - 간단한 1차 선형 보간(interp)
     * - 앞/뒤 경계는 최근값 유지(ffill/bfill 느낌)
     */
    fun fuse(leftFlow: Flow<RawSample>, rightFlow: Flow<RawSample>): Flow<SyncedPoint> = channelFlow {
        val leftBuf  = ArrayDeque<RawSample>()
        val rightBuf = ArrayDeque<RawSample>()
        var lastEmitT = 0.0

        fun prune(now: Double) {
            val lim = now - windowS * 1.5
            while (leftBuf.isNotEmpty() && leftBuf.first().tMonoS < lim)  leftBuf.removeFirst()
            while (rightBuf.isNotEmpty() && rightBuf.first().tMonoS < lim) rightBuf.removeFirst()
        }
        fun interp(buf: ArrayDeque<RawSample>, t: Double): Float? {
            if (buf.isEmpty()) return null
            // 경계: t보다 작은 마지막값/큰 첫값 찾기
            var lo = buf.first()
            var hi = buf.last()
            if (t <= lo.tMonoS) return lo.ppg
            if (t >= hi.tMonoS) return hi.ppg
            // 이분 탐색 대체: 선형 스캔(샘플링 속도 낮아도 충분)
            var prev = lo
            for (s in buf) {
                if (s.tMonoS >= t) {
                    hi = s; lo = prev
                    val w = ((t - lo.tMonoS) / (hi.tMonoS - lo.tMonoS)).coerceIn(0.0, 1.0)
                    return ( (1.0 - w) * lo.ppg + w * hi.ppg ).toFloat()
                }
                prev = s
            }
            return buf.last().ppg
        }

        val s = this
        val jobL = launch {
            leftFlow.collect { l ->
                leftBuf.addLast(l)
                prune(max(l.tMonoS, if (rightBuf.isEmpty()) l.tMonoS else rightBuf.last().tMonoS))
                // 타임라인 갱신
                val tNow = minOf(leftBuf.last().tMonoS, rightBuf.lastOrNull()?.tMonoS ?: leftBuf.last().tMonoS)
                if (lastEmitT == 0.0) lastEmitT = tNow
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
                prune(max(r.tMonoS, if (leftBuf.isEmpty()) r.tMonoS else leftBuf.last().tMonoS))
                val tNow = minOf(rightBuf.last().tMonoS, leftBuf.lastOrNull()?.tMonoS ?: rightBuf.last().tMonoS)
                if (lastEmitT == 0.0) lastEmitT = tNow
                while (tNow - lastEmitT >= dtS) {
                    val t = lastEmitT + dtS
                    val vl = interp(leftBuf, t)
                    val vr = interp(rightBuf, t)
                    if (vl != null && vr != null) s.trySend(SyncedPoint(t, vl, vr))
                    lastEmitT = t
                }
            }
        }
        awaitClose { jobL.cancel(); jobR.cancel() }
    }
}
