package com.example.heartsync.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ble.ConnState
import com.example.heartsync.ble.DualRingBleClient
import com.example.heartsync.ble.RawSample
import com.example.heartsync.data.DevicePrefs
import com.example.heartsync.signal.SyncResampler
import com.example.heartsync.signal.SyncedPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** 실시간 수신 상태(Hz/지연) */
data class RxStats(
    val leftHz: Double = 0.0,
    val rightHz: Double = 0.0,
    val lastLeftAgoMs: Long = Long.MAX_VALUE,
    val lastRightAgoMs: Long = Long.MAX_VALUE
)

class DualRingViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = DevicePrefs(app)

    // 저장된 MAC
    private val leftMacFlow  = prefs.leftMac.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val rightMacFlow = prefs.rightMac.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // BLE 클라이언트/잡
    private var client: DualRingBleClient? = null
    private var runJob: Job? = null

    // (기존) 동기화기 — 다른 화면/기능에서 still 사용 가능
    private val resampler = SyncResampler(fsMs = 20, windowS = 10.0)

    // 출력: 동기 포인트 / 연결상태 / 통계
    private val _points = MutableStateFlow<List<SyncedPoint>>(emptyList())
    val points: StateFlow<List<SyncedPoint>> = _points

    private val _connStates = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connStates: StateFlow<Map<String, ConnState>> = _connStates

    private val _rxStats = MutableStateFlow(RxStats())
    val rxStats: StateFlow<RxStats> = _rxStats

    // ====== 신규: ppgf(DC→MA) 스트림(좌/우) + 연결 여부 ======
    private val _ppgfLeft  = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    private val _ppgfRight = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    /** (tMonoS, ppgf) 리스트 — 최근 10초 윈도우 */
    val ppgfLeft:  StateFlow<List<Pair<Float, Float>>>  = _ppgfLeft
    val ppgfRight: StateFlow<List<Pair<Float, Float>>>  = _ppgfRight

    /** 양쪽 모두 연결되어 있을 때만 true → 홈 그래프 표시 제어 용도 */
    val bothConnected: StateFlow<Boolean> = connStates
        .map { m ->
            (m["left"]  is ConnState.Connected) && (m["right"] is ConnState.Connected)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ====== VM 내부 경량 DC→MA 필터 ======
    private class StreamingDCMA(
        fsHz: Double,
        dcWinSec: Double = 0.20, // 100Hz 기준 20샘플
        maWinSec: Double = 0.05, // 100Hz 기준 5샘플
        private val eps: Double = 1e-8
    ) {
        private val dcN = max(1, (fsHz * dcWinSec).toInt())
        private val maN = max(1, (fsHz * maWinSec).toInt())
        private val dcBuf: ArrayDeque<Double> = ArrayDeque(dcN)
        private val maBuf: ArrayDeque<Double> = ArrayDeque(maN)

        fun reset() { dcBuf.clear(); maBuf.clear() }

        fun update(raw: Double?): Double? {
            if (raw == null || raw.isNaN() || raw.isInfinite()) return null
            if (dcBuf.size == dcN) dcBuf.removeFirst()
            dcBuf.addLast(raw)
            val dcMean = if (dcBuf.isEmpty()) 0.0 else dcBuf.sum() / dcBuf.size
            val ac = raw - dcMean
            if (maBuf.size == maN) maBuf.removeFirst()
            maBuf.addLast(ac)
            val ppgf = if (maBuf.isEmpty()) null else (maBuf.sum() / maBuf.size)
            return when {
                ppgf == null -> null
                kotlin.math.abs(ppgf) < eps -> 0.0
                else -> ppgf
            }
        }
    }

    // 표시 주기(부하 줄이기): 50Hz 목표
    private val dispFsHz = 50.0
    private val emitStep = 1.0 / dispFsHz
    private val winSec = 10.0

    // 좌/우 필터 & 다운샘플 타임마크
    private val filtL = StreamingDCMA(fsHz = 1000.0 / 20.0) // dt=20ms → 50Hz 수집
    private val filtR = StreamingDCMA(fsHz = 1000.0 / 20.0)
    private var nextEmitLeftAt  = 0.0
    private var nextEmitRightAt = 0.0

    init {
        // MAC 변경 시 자동 재시작
        viewModelScope.launch {
            combine(leftMacFlow, rightMacFlow) { l, r -> l to r }
                .collect { (l, r) -> restart(l, r) }
        }
    }

    /** 두 링 모두 끊고 UI 상태/포인트 초기화 */
    fun disconnectAll() {
        client?.stop()
        client = null
        runJob?.cancel()
        runJob = null
        _connStates.value = emptyMap()
        _points.value = emptyList()
        _rxStats.value = RxStats()
        _ppgfLeft.value = emptyList()
        _ppgfRight.value = emptyList()
        nextEmitLeftAt = 0.0
        nextEmitRightAt = 0.0
        filtL.reset(); filtR.reset()
    }

    /** 저장된 MAC(또는 prefix fallback)으로 재시작 */
    private fun restart(leftMac: String?, rightMac: String?) {
        // 이전 실행 종료
        runJob?.cancel()
        client?.stop()

        // 새 클라이언트
        val c = DualRingBleClient(
            ctx = getApplication(),
            scope = viewModelScope,
            leftMac = leftMac,
            rightMac = rightMac,
            leftNamePrefix = "R02_",
            rightNamePrefix = "R02_",
            stallTimeoutSec = 5.0
        )
        client = c

        // 상태 초기화
        _ppgfLeft.value = emptyList()
        _ppgfRight.value = emptyList()
        nextEmitLeftAt = 0.0
        nextEmitRightAt = 0.0
        filtL.reset(); filtR.reset()

        // 병렬 수집 루프
        runJob = viewModelScope.launch {
            // 1) 연결 상태 중계
            launch {
                c.connStates.collect { _connStates.value = it }
            }

            // 2) 수신속도/지연 통계(1초 주기)
            launch {
                var lCount = 0
                var rCount = 0
                var lastLeftMono = 0.0
                var lastRightMono = 0.0

                launch { c.leftFlow.collect { s -> lCount++;  lastLeftMono  = s.tMonoS } }
                launch { c.rightFlow.collect { s -> rCount++; lastRightMono = s.tMonoS } }

                while (isActive) {
                    delay(1000)
                    val nowS = SystemClock.elapsedRealtimeNanos() / 1e9
                    _rxStats.value = RxStats(
                        leftHz  = lCount.toDouble(),
                        rightHz = rCount.toDouble(),
                        lastLeftAgoMs  = if (lastLeftMono  > 0) ((nowS - lastLeftMono)  * 1000).toLong() else Long.MAX_VALUE,
                        lastRightAgoMs = if (lastRightMono > 0) ((nowS - lastRightMono) * 1000).toLong() else Long.MAX_VALUE
                    )
                    lCount = 0; rCount = 0
                }
            }

            // 3) (기존) 동기 포인트 생성 — 다른 화면에서 필요할 수 있어 유지
            launch {
                resampler.fuse(c.leftFlow, c.rightFlow)
                    .runningFold(emptyList<SyncedPoint>()) { acc, v ->
                        val cap = 10.0
                        val tmin = v.tMonoS - cap
                        val trimmed = acc.dropWhile { it.tMonoS < tmin }
                        trimmed + v
                    }
                    .collect { _points.value = it }
            }

            // 4) 신규: 좌/우 ppgf(DC→MA) 생성 + 10s 창 유지(표시 50Hz로 다운샘플)
            launch {
                c.leftFlow.collect { s: RawSample ->
                    val y = filtL.update(s.ppg.toDouble()) ?: return@collect
                    if (s.tMonoS < nextEmitLeftAt) return@collect
                    nextEmitLeftAt = s.tMonoS + emitStep
                    val t0 = s.tMonoS.toFloat()
                    _ppgfLeft.update { old ->
                        val tStart = t0 - winSec.toFloat()
                        val trimmed = old.dropWhile { it.first < tStart }
                        trimmed + (t0 to y.toFloat())
                    }
                }
            }
            launch {
                c.rightFlow.collect { s: RawSample ->
                    val y = filtR.update(s.ppg.toDouble()) ?: return@collect
                    if (s.tMonoS < nextEmitRightAt) return@collect
                    nextEmitRightAt = s.tMonoS + emitStep
                    val t0 = s.tMonoS.toFloat()
                    _ppgfRight.update { old ->
                        val tStart = t0 - winSec.toFloat()
                        val trimmed = old.dropWhile { it.first < tStart }
                        trimmed + (t0 to y.toFloat())
                    }
                }
            }
        }

        // 시작
        c.start()
    }

    /** 외부에서 명시적으로 시작/중지 */
    fun start() { client?.start() ?: restart(leftMacFlow.value, rightMacFlow.value) }
    fun stop()  { client?.stop() }

    /** 두 센서 동시 안전 분리(요청형) — UI '초기화' 버튼에서 사용 */
    fun resetAll() {
        viewModelScope.launch { client?.resetAll() }
    }
}
