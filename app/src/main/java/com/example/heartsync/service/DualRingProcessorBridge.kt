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
    private val repo: PpgRepository = PpgRepository.instance
) {
    private val TAG = "DualRingBridge"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    // 최근 좌/우 샘플 버퍼 (모노토닉 초, PPG값)
    private val bufL = ArrayDeque<Pair<Double, Double>>()
    private val bufR = ArrayDeque<Pair<Double, Double>>()
    private var lastL: Pair<Double, Double>? = null
    private var lastR: Pair<Double, Double>? = null

    // On-device processor (네 프로젝트 시그니처 기준)
    private val proc = OnDeviceProcessor(
        fsHz = 50,          // Int
        minRtMs = 90.0,
        hsiTdBase = 60.0,
        hsiTdScale = 30.0,
        hsiWarn = 1.5,
        hsiHigh = 3.0,
        ausprBand = 0.30
    )

    fun start(dual: DualRingBleClient) {
        stop()

        // ✅ Auto session: 브릿지 시작 시 자동 세션 생성
        try {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                val sid = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    .toString().replace(":", "-") + "_" + java.util.UUID.randomUUID().toString().take(8)
                repo.setSessionId(sid)
                // 세션 메타는 fire-and-forget
                PpgRepository.instance.putSessionMetaFireAndForget(uid, sid)
                Log.d(TAG, "auto session started sid=$sid")
            } else {
                Log.e(TAG, "auto session skipped: not signed in")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "auto session init fail", t)
        }

        val c1 = scope.launch {
            dual.leftFlow.collect { s ->
                lastL = s.tMonoS to s.ppg.toDouble()
                bufL.addLast(lastL!!)
                trimOld(bufL, 3.0)
            }
        }
        val c2 = scope.launch {
            dual.rightFlow.collect { s ->
                lastR = s.tMonoS to s.ppg.toDouble()
                bufR.addLast(lastR!!)
                trimOld(bufR, 3.0)
            }
        }

        job = scope.launch {
            var t0: Double? = null
            var tick = 0L
            val fsHz = 50.0                 // 처리 주파수(더블)
            val dt = 1.0 / fsHz

            while (isActive) {
                // 🔧 delay에 Long 필요 → periodMs를 Long으로 변환
                val periodMs = (1000.0 / fsHz).coerceAtLeast(10.0).toLong()
                delay(periodMs)

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
        // c1/c2는 경고만; Job은 scope에 붙어 있어 stop()에서 함께 정리돼요.
        @Suppress("UNUSED_VARIABLE") val _keepRefs = arrayOf(c1, c2)
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
