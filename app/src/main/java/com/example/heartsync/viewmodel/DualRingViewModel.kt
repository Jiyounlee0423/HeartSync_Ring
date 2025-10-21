// app/src/main/java/com/example/heartsync/viewmodel/DualRingViewModel.kt
package com.example.heartsync.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ble.DualRingBleClient
import com.example.heartsync.data.DevicePrefs
import com.example.heartsync.signal.SyncResampler
import com.example.heartsync.signal.SyncedPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    // 동기화기
    private val resampler = SyncResampler(fsMs = 20, windowS = 10.0)

    // 출력: 동기 포인트 / 연결상태 / 통계
    private val _points = MutableStateFlow<List<SyncedPoint>>(emptyList())
    val points: StateFlow<List<SyncedPoint>> = _points

    private val _connStates = MutableStateFlow<Map<String, Any>>(emptyMap())
    val connStates: StateFlow<Map<String, Any>> = _connStates

    private val _rxStats = MutableStateFlow(RxStats())
    val rxStats: StateFlow<RxStats> = _rxStats

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

        // 병렬 수집 루프
        runJob = viewModelScope.launch {
            // 1) 연결 상태 중계
            launch {
                c.stateFlow.collect { _connStates.value = it }
            }

            // 2) 수신속도/지연 통계(1초 주기)
            launch {
                var lCount = 0
                var rCount = 0
                var lastLeftMono = 0.0
                var lastRightMono = 0.0

                // 좌/우 샘플 카운팅
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

            // 3) 동기 포인트 생성(최근 10초만 유지)
            resampler.fuse(c.leftFlow, c.rightFlow)
                .runningFold(emptyList<SyncedPoint>()) { acc, v ->
                    val cap = 10.0
                    val tmin = v.tMonoS - cap
                    val trimmed = acc.dropWhile { it.tMonoS < tmin }
                    trimmed + v
                }
                .collect { _points.value = it }
        }

        // 시작
        c.start()
    }

    /** 외부에서 명시적으로 시작/중지 */
    fun start() { client?.start() ?: restart(leftMacFlow.value, rightMacFlow.value) }
    fun stop()  { client?.stop() }
}
