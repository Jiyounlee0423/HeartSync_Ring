package com.example.heartsync.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.data.model.BleDevice
import com.example.heartsync.data.model.GraphState
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.service.MeasureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BleViewModel(
    app: Application
) : AndroidViewModel(app) {

    private val client = PpgBleClient(app)

    private val _graphState = MutableStateFlow(GraphState())
    val graphState: StateFlow<GraphState> = _graphState

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _connectionState =
        MutableStateFlow<PpgBleClient.ConnectionState>(PpgBleClient.ConnectionState.Disconnected)
    val connectionState: StateFlow<PpgBleClient.ConnectionState> = _connectionState.asStateFlow()

    /** 팝업용 ALERT 스트림 (Repo가 event=ALERT & side=left/right만 방출) */
    val alerts: SharedFlow<PpgRepository.UiAlert> = PpgRepository.instance.alerts

    private var scanJob: Job? = null
    private var connJob: Job? = null
    private var fsJob: Job? = null

    private val MAX_GRAPH_POINTS = 512

    init {
        // BLE/서비스에서 바로 쏘는 실시간 smoothed 값 반영
        PpgRepository.smoothedFlow
            .onEach { (l, r) ->
                _graphState.update { p ->
                    p.copy(
                        smoothedL = (p.smoothedL + l).takeLast(MAX_GRAPH_POINTS),
                        smoothedR = (p.smoothedR + r).takeLast(MAX_GRAPH_POINTS)
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /** Firestore에서 smoothed만 따라 읽어 그래프에 반영 (프록시) */
    fun startFirestoreGraph(uid: String, sessionId: String, limit: Long = 512L) {
        // 기존 구독이 있다면 갱신
        fsJob?.cancel()
        fsJob = viewModelScope.launch {
            PpgRepository.instance
                .observeSmoothedFromFirestore(uid, sessionId, limit)
                .onEach { (l, r) ->
                    val cap = limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    _graphState.update { prev ->
                        prev.copy(
                            smoothedL = (prev.smoothedL + l).takeLast(cap),
                            smoothedR = (prev.smoothedR + r).takeLast(cap)
                        )
                    }
                }
                .catch { e -> android.util.Log.e("BleVM", "observeSmoothedFromFirestore error", e) }
                .collect()
        }
    }

    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true
        client.startScan()

        scanJob?.cancel()
        scanJob = client.scanResults
            .onEach { list -> _scanResults.value = list }
            .launchIn(viewModelScope)
    }

    fun stopScan() {
        _scanning.value = false
        scanJob?.cancel()
        client.stopScan()
    }

    fun connect(device: BleDevice) {
        stopScan()
        client.connect(device)

        connJob?.cancel()
        connJob = client.connectionState
            .onEach { state -> _connectionState.value = state }
            .launchIn(viewModelScope)
    }

    fun disconnect() {
        client.disconnect()
        _connectionState.value = PpgBleClient.ConnectionState.Disconnected
        connJob?.cancel()
        clearGraph()
        // Firestore 구독도 정리(필요 시)
        fsJob?.cancel()
    }

    fun clearGraph() {
        _graphState.value = GraphState()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        connJob?.cancel()
        fsJob?.cancel()
        client.stopScan()
        client.disconnect()
    }

    /** 연결된 기기로 측정 시작 */
    fun startMeasure(device: BleDevice) {
        val ctx = getApplication<Application>()
        val it = Intent(ctx, MeasureService::class.java).apply {
            putExtra(MeasureService.EXTRA_DEVICE_NAME, device.name)
            putExtra(MeasureService.EXTRA_DEVICE_ADDR, device.address)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(it)
        } else {
            ctx.startService(it)
        }
    }

    /** 측정 중지 */
    fun stopMeasure() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, MeasureService::class.java))
    }
}
