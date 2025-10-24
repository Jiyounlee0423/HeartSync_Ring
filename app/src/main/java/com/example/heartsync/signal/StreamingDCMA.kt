package com.example.heartsync.signal

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * DC 제거(최근 dc_win_s 평균 제거) → 이동평균(ma_win_s) → ppgf 반환
 * - Python StreamingDCMA와 동등 동작
 */
class StreamingDCMA(
    fsHz: Double,
    dcWinSec: Double = 0.20,  // @100Hz 기준 20샘플
    maWinSec: Double = 0.05,  // @100Hz 기준 5샘플
    private val eps: Double = 1e-8
) {
    private val dcN  = max(1, (fsHz * dcWinSec).toInt())
    private val maN  = max(1, (fsHz * maWinSec).toInt())
    private val dcBuf = ArrayDeque<Double>(dcN)
    private val maBuf = ArrayDeque<Double>(maN)

    fun reset() { dcBuf.clear(); maBuf.clear() }

    /** raw PPG → ppgf (Double). 유효치 없으면 null */
    fun update(raw: Double?): Double? {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return null

        if (dcBuf.size == dcN) dcBuf.removeFirst()
        dcBuf.addLast(raw)

        // DC 제거: 최근 평균
        val dcMean = dcBuf.average()
        val ac = raw - dcMean

        // 이동평균
        if (maBuf.size == maN) maBuf.removeFirst()
        maBuf.addLast(ac)

        val ppgf = if (maBuf.isEmpty()) null else maBuf.average()

        // 산술 안정성
        return when {
            ppgf == null -> null
            abs(ppgf) < eps -> 0.0
            else -> ppgf
        }
    }

    private fun ArrayDeque<Double>.average(): Double {
        if (this.isEmpty()) return 0.0
        var s = 0.0
        for (v in this) s += v
        return s / this.size
    }
}
