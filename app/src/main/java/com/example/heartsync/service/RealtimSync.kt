
package com.example.heartsync.service

import kotlin.math.*
import java.util.ArrayDeque

object RealtimeDSP {
    data class Resampled(val t: DoubleArray, val v: DoubleArray, val nAnchors: Int, val nValid: Int)

    fun sliceAndResample(buf: ArrayDeque<Pair<Double, Double>>, tStart: Double, tNow: Double, fsMs: Double): Resampled {
        val arr = buf.filter { it.first in (tStart - 0.5)..(tNow + 0.1) }
        val nAnchors = arr.size
        val dt = fsMs / 1000.0
        val n = max(1, floor((tNow - tStart) / dt).toInt() + 1)
        val tLine = DoubleArray(n) { tStart + it * dt }
        val vLine = DoubleArray(n) { Double.NaN }
        if (arr.isEmpty()) return Resampled(tLine, DoubleArray(n){0.0}, nAnchors, 0)

        val tt = arr.map { it.first }.toDoubleArray()
        val vv = arr.map { it.second }.toDoubleArray()

        // linear interp with edge hold
        var j = 0
        for (i in 0 until n) {
            val t = tLine[i]
            while (j + 1 < tt.size && tt[j + 1] < t) j++
            when {
                t <= tt.first() -> vLine[i] = vv.first()
                t >= tt.last()  -> vLine[i] = vv.last()
                else -> {
                    val t0 = tt[j]; val t1 = tt[j+1]
                    val v0 = vv[j]; val v1 = vv[j+1]
                    val w = (t - t0) / max(1e-12, (t1 - t0))
                    vLine[i] = (1 - w) * v0 + w * v1
                }
            }
        }
        val nValid = vLine.count { it.isFinite() }
        return Resampled(tLine, vLine, nAnchors, nValid)
    }

    data class DetrendOut(val ac: DoubleArray, val std: Double)

    fun detrendAndNorm(x: DoubleArray, fsHz: Double, maMs: Int = 1500): DetrendOut {
        if (x.isEmpty()) return DetrendOut(DoubleArray(0), 0.0)
        val win = max(3, round(maMs / 1000.0 * fsHz).toInt())
        val c = DoubleArray(x.size)
        if (win >= x.size) {
            val mean = x.average()
            for (i in x.indices) c[i] = mean
        } else {
            val k = 1.0 / win
            var sum = 0.0
            for (i in x.indices) {
                sum += x[i]
                if (i - win >= 0) sum -= x[i - win]
                val left = max(0, i - win / 2)
                val right = min(x.lastIndex, i + win / 2)
                // simple centered moving average approximation
                c[i] = (sum * k).coerceIn(-1e12, 1e12)
            }
        }
        val ac = DoubleArray(x.size) { x[it] - c[it] }
        val mean = ac.average()
        var varsum = 0.0
        for (v in ac) varsum += (v - mean) * (v - mean)
        val std = sqrt(varsum / max(1, ac.size))
        val s = if (std == 0.0) 1.0 else std
        for (i in ac.indices) ac[i] = (ac[i] - mean) / s
        return DetrendOut(ac, std)
    }

    private fun xcorrAtLag(a: DoubleArray, b: DoubleArray, lag: Int): Double? {
        val n = min(a.size, b.size)
        if (n <= 1) return null
        val (aa, bb) = if (lag < 0) {
            a.sliceArray(-lag until n) to b.sliceArray(0 until n + lag)
        } else {
            a.sliceArray(0 until n - lag) to b.sliceArray(lag until n)
        }
        val m = min(aa.size, bb.size)
        if (m <= 0) return null
        var dot = 0.0
        for (i in 0 until m) dot += aa[i] * bb[i]
        return dot / m
    }

    data class PadOut(val padMs: Double, val corr: Double)

    fun estimatePadMsParabolic(l: DoubleArray, r: DoubleArray, fsHz: Double, maxLagS: Double = 1.0): PadOut {
        if (l.size < 10 || r.size < 10) return PadOut(0.0, 0.0)
        val maxLag = (maxLagS * fsHz).toInt()
        var bestLag = 0
        var bestCorr = -1e18
        for (lag in -maxLag..maxLag) {
            val c = xcorrAtLag(l, r, lag) ?: continue
            if (c > bestCorr) { bestCorr = c; bestLag = lag }
        }
        if (bestCorr <= -1e17) return PadOut(0.0, 0.0)
        val cm1 = if (bestLag - 1 >= -maxLag) xcorrAtLag(l, r, bestLag - 1) else null
        val cp1 = if (bestLag + 1 <=  maxLag) xcorrAtLag(l, r, bestLag + 1) else null
        var refined = bestLag.toDouble()
        if (cm1 != null && cp1 != null) {
            val denom = (cm1 - 2 * bestCorr + cp1)
            if (abs(denom) > 1e-12) {
                val delta = 0.5 * ((cm1 - cp1) / denom)
                if (delta in -1.5..1.5) refined = bestLag + delta
            }
        }
        val padMs = (refined / fsHz) * 1000.0
        return PadOut(padMs, bestCorr)
    }
}
