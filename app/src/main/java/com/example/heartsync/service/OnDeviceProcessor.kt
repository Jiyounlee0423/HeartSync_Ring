// app/src/main/java/com/example/heartsync/service/OnDeviceProcessor.kt
package com.example.heartsync.service

import kotlin.math.*
import java.util.ArrayDeque

/**
 * Arduino에서 넘어온 CSV("t,left,right")를
 * - DC 제거 + 이동평균 스무딩
 * - 좌/우 비트(foot, peak) 검출 및 쌍 매칭
 * - AUSP, AUSPR, ΔTD, RT, HSI 산출
 * - PpgRepository.trySaveFromLine(...)이 이해하는 "STAT key=value ..." 라인으로 변환
 *
 * side 판정:
 *  - AUSPR 편향 + TD(peak 시차) 두 기준의 투표를 합산해 left/right/balance 결정
 *  - 비대칭일 때 alert_type=asymmetry, risk=OK/WARN/HIGH, reasons=[...] 포함
 */
class OnDeviceProcessor(
    private val fsHz: Int = 50,
    private val dcWinSec: Double = 1.5,
    private val smoothN: Int = 5,
    private val foiAlpha: Double = 0.1,
    private val ausprSmoothK: Int = 5,
    private val ausprClampLow: Double = 0.5,
    private val ausprClampHigh: Double = 2.0,
    private val minPeakProm: Double = 30.0,
    private val refractSec: Double = 0.35,
    private val pairTolSec: Double = 0.10,
    private val minRtMs: Double = 100.0,
    private val hsiTdBase: Double = 40.0,
    private val hsiTdScale: Double = 25.0,
    private val hsiWarn: Double = 1.0,
    private val hsiHigh: Double = 2.0,
    // AUSPR 정상 밴드(±30%) – HSI term1 분모 계산에도 사용
    private val ausprBand: Double = 0.30,
) {
    private val dt = 1.0 / fsHz.toDouble()
    private val dcN = max(3, (dcWinSec * fsHz).toInt())

    /* -------------------- 이동 평균 -------------------- */
    private class MovingAvg(private val n: Int) {
        private val q = ArrayDeque<Double>()
        private var sum = 0.0
        fun push(x: Double): Double {
            if (q.size == n) sum -= q.removeFirst()
            q.addLast(x); sum += x
            return sum / q.size
        }
    }
    private val dcL = MovingAvg(dcN)
    private val dcR = MovingAvg(dcN)
    private val smL = MovingAvg(smoothN)
    private val smR = MovingAvg(smoothN)

    /* -------------------- Beat 검출 -------------------- */
    private data class Beat(
        val footIdx: Int,
        val footVal: Double,
        val peakIdx: Int,
        val peakVal: Double,
        var AUSP: Double = 0.0,
        var RTms: Double = Double.NaN
    )

    private class BeatDetector(fs: Int, minProm: Double, refractSec: Double) {
        private val maxLen = 15 // ≈300ms@50Hz
        private val win = ArrayDeque<Pair<Int, Double>>()
        private val refract = (refractSec * fs).toInt()
        private val minPromVal = minProm
        private var lastPeakIdx = -1_000_000
        private val found = ArrayList<Beat>()

        fun update(idx: Int, y: Double) {
            if (win.size == maxLen) win.removeFirst()
            win.addLast(idx to y)
            if (win.size < maxLen) return

            val mid = maxLen / 2
            val (iMid, yMid) = win.elementAt(mid)
            val yPrev = win.elementAt(mid - 1).second
            val yNext = win.elementAt(mid + 1).second

            if (yMid > yPrev && yMid > yNext) {
                if ((iMid - lastPeakIdx) >= refract && yMid >= minPromVal) {
                    // foot: mid 이전 첫 8개 중 최소
                    var footIdx = -1; var footVal = Double.POSITIVE_INFINITY
                    val end = min(8, mid)
                    for (k in 0 until end) {
                        val (ii, vv) = win.elementAt(k)
                        if (vv < footVal) { footVal = vv; footIdx = ii }
                    }
                    if (footIdx >= 0 && iMid > footIdx) {
                        found.add(Beat(footIdx, footVal, iMid, yMid))
                        lastPeakIdx = iMid
                    }
                }
            }
        }

        fun consume(): List<Beat> = found.toList().also { found.clear() }
    }

    private val detL = BeatDetector(fsHz, minPeakProm, refractSec)
    private val detR = BeatDetector(fsHz, minPeakProm, refractSec)

    private val filtHistL = ArrayList<Double>()
    private val filtHistR = ArrayList<Double>()
    private val baseBufL = ArrayList<Double>()
    private val baseBufR = ArrayList<Double>()
    private val ausprHist = ArrayDeque<Double>() // 중앙값 근사

    private var idx = 0

    /* -------------------- 보조 계산 -------------------- */
    private fun baseAmp(buf: List<Double>, recent: List<Double>): Double {
        if (buf.isNotEmpty()) {
            val v = buf.filter { it > 0 }.ifEmpty { listOf(0.0) }.average()
            return max(v.takeIf { it.isFinite() } ?: 1.0, 1e-6)
        }
        if (recent.isNotEmpty()) {
            val n = min(fsHz * 2, recent.size)
            val seg = recent.takeLast(n).map { max(0.0, it) }
            val v = seg.ifEmpty { listOf(0.0) }.average()
            return max(v.takeIf { it.isFinite() } ?: 1.0, 1e-6)
        }
        return 1.0
    }

    private fun foiArea(seg: List<Double>, dt: Double, alpha: Double): Double {
        if (seg.size < 2) return 0.0
        if (alpha <= 0.0) {
            var s = 0.0
            for (i in 1 until seg.size) s += 0.5 * (seg[i - 1] + seg[i]) * dt
            return s
        }
        val n = seg.size
        var denom = 0.0
        val k = DoubleArray(n) { i ->
            val v = (i + 1).toDouble().pow(alpha - 1.0)
            denom += v; v
        }
        var dot = 0.0
        for (i in 0 until n) dot += seg[i] * (k[i] / denom)
        return dot * n * dt
    }

    /* ---------------------------------------------------
     *  주 API: CSV 한 줄 → STAT 라인들
     * --------------------------------------------------- */
    fun onCsvLine(csv: String): List<String> {
        val t = csv.trim()
        val parts = t.split(',')
        if (parts.size < 3) return emptyList()

        val tRaw = parts[0].trim()
        val rawL = parts[1].trim().toDoubleOrNull() ?: return emptyList()
        val rawR = parts[2].trim().toDoubleOrNull() ?: return emptyList()
        // us로 오면 ms로, 이미 ms면 그대로
        val tsMs = tRaw.toDoubleOrNull()?.let { if (it > 10_000.0) it / 1000.0 else it } ?: 0.0

        // 필터링
        val xL = rawL - dcL.push(rawL)
        val xR = rawR - dcR.push(rawR)
        val yL = smL.push(xL)
        val yR = smR.push(xR)

        filtHistL.add(yL); filtHistR.add(yR)
        if (baseBufL.size < fsHz * 8) { // 자동 baseline: 약 8초
            baseBufL.add(yL); baseBufR.add(yR)
        }

        // 비트 갱신
        detL.update(idx, yL)
        detR.update(idx, yR)
        val curIdx = idx
        idx += 1

        val out = ArrayList<String>()

        // (A) 샘플 단위 STAT: 그래프용
        out += buildString {
            append("STAT ts="); append(tsMs.toLong())
            append(" PPGf_L="); append("%.3f".format(yL))
            append(" PPGf_R="); append("%.3f".format(yR))
        }

        // (B) 비트 지표
        val beatsL = detL.consume()
        val beatsR = detR.consume()
        if (beatsL.isEmpty() && beatsR.isEmpty()) return out

        // 간단 쌍매칭: peak 시각 차 <= pairTolSec 중 최단 거리
        val usedR = BooleanArray(beatsR.size)
        val tdThreshSamples = (hsiTdBase / 1000.0 / dt).roundToInt()
        val tolLn = ln(1.0 + ausprBand)

        // baseline amplitude
        val bAmpL = baseAmp(baseBufL, filtHistL)
        val bAmpR = baseAmp(baseBufR, filtHistR)

        fun fillBeatAusp(b: Beat, hist: List<Double>, amp: Double) {
            if (b.footIdx >= 0 && b.peakIdx < hist.size && b.peakIdx > b.footIdx) {
                val seg = hist.subList(b.footIdx, b.peakIdx + 1).map { max(0.0, it) / amp }
                b.AUSP = foiArea(seg, dt, foiAlpha)
                b.RTms = (b.peakIdx - b.footIdx) * 1000.0 * dt
            } else {
                b.AUSP = 0.0
                b.RTms = Double.NaN
            }
        }

        // 모든 좌측 비트에 대해 가장 가까운 우측 비트 매칭
        for (iL in beatsL.indices) {
            val bL = beatsL[iL]
            var bestJ = -1
            var bestD = 1e9
            val tL = bL.peakIdx / fsHz.toDouble()
            for (j in beatsR.indices) {
                if (usedR[j]) continue
                val tR = beatsR[j].peakIdx / fsHz.toDouble()
                val d = abs(tL - tR)
                if (d <= pairTolSec && d < bestD) {
                    bestD = d; bestJ = j
                }
            }
            if (bestJ < 0) continue

            usedR[bestJ] = true
            val bR = beatsR[bestJ]

            // AUSP/RT 채우기
            fillBeatAusp(bL, filtHistL, bAmpL)
            fillBeatAusp(bR, filtHistR, bAmpR)

            // 생리적 최솟값 미만 RT는 제외
            val minRt = listOf(bL.RTms, bR.RTms).filter { it.isFinite() }.minOrNull() ?: 1e9
            if (minRt < minRtMs) continue

            // AUSPR 안정화 (히스토리 중앙값+클램프)
            val ausprRaw = if (bL.AUSP > 1e-9) bR.AUSP / bL.AUSP else Double.NaN
            val auspr = if (ausprRaw.isFinite()) {
                if (ausprHist.size == ausprSmoothK) ausprHist.removeFirst()
                ausprHist.addLast(ausprRaw)
                val med = ausprHist.sorted()[ausprHist.size / 2]
                med.coerceIn(ausprClampLow, ausprClampHigh)
            } else 1.0

            val dtMs = abs(bL.peakIdx - bR.peakIdx) * 1000.0 * dt
            val term1 = abs(ln(max(1e-6, auspr))) / tolLn
            val term2 = max(0.0, dtMs - hsiTdBase) / hsiTdScale
            val hsi = term1 + term2

            val risk = when {
                hsi >= hsiHigh -> "HIGH"
                hsi >= hsiWarn -> "WARN"
                else -> "OK"
            }

            // side 판정 (투표: AUSPR 편향 + TD 선행)
            var voteL = 0
            var voteR = 0
            if (!auspr.isNaN()) {
                if (auspr > (1.0 + ausprBand)) voteR++     // 오른쪽이 더 큼
                if (auspr < (1.0 - ausprBand)) voteL++     // 왼쪽이 더 큼
            }
            val leadSamples = (bR.peakIdx - bL.peakIdx)
            if (leadSamples > tdThreshSamples) voteL++     // R이 늦음 → L 선행
            if (leadSamples < -tdThreshSamples) voteR++    // L이 늦음 → R 선행

            val sideStr: String? = when {
                voteL > voteR -> "left"
                voteR > voteL -> "right"
                else -> "null"
            }

            // 이유(reason) 구성 (선택)
            val reasons = buildList {
                val devLn = abs(ln(max(1e-6, auspr))) / tolLn
                if (devLn >= 1.0) add("AUSPR dev")
                if (dtMs > hsiTdBase) add("TD high")
            }

            // 비대칭 상태 표기
            val alertType = if (hsi >= hsiWarn) "asymmetry" else null

            // 비트 지표 STAT(peak 늦은 쪽 시각 기준)
            val tsBeat = ((max(bL.peakIdx, bR.peakIdx) / fsHz.toDouble()) * 1000.0).toLong()
            out += buildString {
                append("STAT ts="); append(tsBeat)
                if (sideStr != null) { append(" side="); append(sideStr) }
                append(" AUSPR="); append("%.6f".format(auspr))
                append(" DeltaTD_ms="); append("%.1f".format(dtMs))
                append(" RT_L_ms="); append("%.1f".format(bL.RTms))
                append(" RT_R_ms="); append("%.1f".format(bR.RTms))
                append(" HSI="); append("%.3f".format(hsi))
                append(" risk="); append(risk)
                if (alertType != null) {
                    append(" alert_type="); append(alertType)
                    if (reasons.isNotEmpty()) {
                        append(" reasons=[")
                        append(reasons.joinToString(","))
                        append("]")
                    }
                }
            }
        }

        return out
    }
}
