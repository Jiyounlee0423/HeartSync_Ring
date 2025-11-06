package com.example.heartsync.service

import android.util.Log
import com.example.heartsync.ble.DualRingBleClient
import com.example.heartsync.data.remote.PpgRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.max
import java.util.ArrayDeque

/**
 * DualRingBleClient의 좌/우 RawSample(tMonoS, ppg)을 받아
 * 50Hz 타임라인으로 보간한 후 "t_ms,left,right" CSV를 생성해
 * OnDeviceProcessor로 지표(AUSPR/PAD/RT/HSI)를 계산하고
 * HeartSync_1102-main과 동일한 "STAT/ALERT" 라인으로 Firestore에 저장한다.
 */
class DualRingProcessorBridge(
    private val repo: PpgRepository,
    private val fsHz: Int = 50
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val bufL = ArrayDeque<Pair<Double, Double>>() // (tMonoS, val)
    private val bufR = ArrayDeque<Pair<Double, Double>>()

    @Volatile private var lastL: Pair<Double, Double>? = null
    @Volatile private var lastR: Pair<Double, Double>? = null

    private val proc = OnDeviceProcessor(
        fsHz = fsHz,
        dcWinSec = 0.8,                              // OK (기본 1.5, 필요시 조정)
        smoothN = (0.20 * fsHz).toInt().coerceAtLeast(1), // ← 핵심: 0.20초 창 = 샘플 개수
        foiAlpha = 0.1,
        ausprSmoothK = 5,
        ausprClampLow = 0.5,
        ausprClampHigh = 2.0,
        minPeakProm = 30.0,
        refractSec = 0.35,
        pairTolSec = 0.120,
        minRtMs = 90.0,
        hsiTdBase = 60.0,
        hsiTdScale = 30.0,
        hsiWarn = 1.5,
        hsiHigh = 3.0,
        ausprBand = 0.30
    )


    fun start(dual: DualRingBleClient) {
        stop()

        val c1 = scope.launch {
            dual.leftFlow.collect { s ->
                lastL = s.tMonoS to s.ppg.toDouble()
                bufL.addLast(lastL!!)
                trimOld(bufL, keepSec = 3.0)
            }
        }
        val c2 = scope.launch {
            dual.rightFlow.collect { s ->
                lastR = s.tMonoS to s.ppg.toDouble()
                bufR.addLast(lastR!!)
                trimOld(bufR, keepSec = 3.0)
            }
        }

        val dt = 1.0 / fsHz
        job = scope.launch {
            var t0: Double? = null
            var tick: Long = 0

            while (isActive) {
                delay((1000L / fsHz).coerceAtLeast(10L))

                val l = lastL
                val r = lastR
                if (l == null || r == null) continue

                val now = max(l.first, r.first)
                if (t0 == null) t0 = now
                val tMono = (t0 ?: now) + tick * dt
                val csv = "${((tMono - (t0 ?: now)) * 1000.0).toLong()},${interp(bufL, tMono)},${interp(bufR, tMono)}"
                tick += 1

                val lines = proc.onCsvLine(csv)
                for (line in lines) {
                    if (line.startsWith("STAT") && line.contains("PPGf_L=")) {
                        val ts = extractLong(line, "ts") ?: continue
                        val lpf = extractDouble(line, "PPGf_L") ?: continue
                        val rpf = extractDouble(line, "PPGf_R") ?: continue
                        PpgRepository.emitSmoothed(ts, lpf, rpf)
                    }
                    repo.trySaveFromLinePublic(line)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        scope.coroutineContext.cancelChildren()
        bufL.clear(); bufR.clear()
        lastL = null; lastR = null
    }

    private fun trimOld(q: ArrayDeque<Pair<Double, Double>>, keepSec: Double) {
        val cut = (q.lastOrNull()?.first ?: return) - keepSec
        while (q.isNotEmpty() && q.first.first < cut) q.removeFirst()
    }

    private fun interp(q: ArrayDeque<Pair<Double, Double>>, t: Double): Double {
        if (q.isEmpty()) return 0.0
        if (t <= q.first.first) return q.first.second
        if (t >= q.last.first)  return q.last.second
        var i = 0
        val arr = q.toList()
        while (i + 1 < arr.size && arr[i + 1].first < t) i++
        val (t0, v0) = arr[i]
        val (t1, v1) = arr[i + 1]
        val w = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        return (1 - w) * v0 + w * v1
    }

    private fun extractLong(line: String, key: String): Long? =
        Regex("\\b${key}=([0-9]+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun extractDouble(line: String, key: String): Double? =
        Regex("\\b${key}=(-?[0-9]+(?:\\.[0-9]+)?)").find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
}