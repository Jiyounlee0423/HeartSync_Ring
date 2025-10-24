package com.example.heartsync.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ble.PpgBleClient
import com.example.heartsync.data.model.BleDevice
import com.example.heartsync.data.model.GraphState
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.service.MeasureService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch



class BleViewModel(app: Application) : AndroidViewModel(app) {

    private val client = PpgBleClient(app)

    private val _graphState = MutableStateFlow(GraphState())
    val graphState: StateFlow<GraphState> = _graphState.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults.asStateFlow()

    private val _connectionState =
        MutableStateFlow<PpgBleClient.ConnectionState>(PpgBleClient.ConnectionState.Disconnected)
    val connectionState: StateFlow<PpgBleClient.ConnectionState> = _connectionState.asStateFlow()

    /** 팝업용 ALERT 스트림 (Repo가 event=ALERT & side=left/right만 방출) */
    val alerts: SharedFlow<PpgRepository.UiAlert> = PpgRepository.instance.alerts

    /** 단발 이벤트 (스낵바 등) */
    private val _events = Channel<String>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<String> = _events.receiveAsFlow()

    private var scanJob: Job? = null
    private var connJob: Job? = null
    private var fsJob: Job? = null

    private val MAX_GRAPH_POINTS = 512

    private var leftSel: BleDevice? = null
    private var rightSel: BleDevice? = null

    init {
        // BLE → UI 실시간 smoothed 반영 (time, L, R)
        PpgRepository.smoothedFlow
            .onEach { (_, l, r) ->
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
                .catch { e -> Log.e("BleVM", "observeSmoothedFromFirestore error", e) }
                .collect()
        }
    }

    /** 스캔 */
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

    /** 단일 연결 (DualRing과 별개) */
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
        fsJob?.cancel()
    }

    /** 좌/우 기기 선택 */
    fun onSelectLeft(dev: BleDevice): Boolean {
        if (rightSel?.address == dev.address) {
            viewModelScope.launch { _events.send("왼손/오른손에 같은 기기를 지정할 수 없습니다.") }
            return false
        }
        leftSel = dev
        return true
    }

    fun onSelectRight(dev: BleDevice): Boolean {
        if (leftSel?.address == dev.address) {
            viewModelScope.launch { _events.send("왼손/오른손에 같은 기기를 지정할 수 없습니다.") }
            return false
        }
        rightSel = dev
        return true
    }

    /** 전체 초기화: BLE 연결 및 선택 해제 */
    fun resetBle() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, MeasureService::class.java).apply {
            action = MeasureService.ACTION_RESET_ALL
        })
        leftSel = null
        rightSel = null
        clearGraph()
        viewModelScope.launch { _events.send("BLE 연결을 초기화했습니다.") }
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
        if (android.os.Build.VERSION.SDK_INT >= 26)
            ctx.startForegroundService(it)
        else
            ctx.startService(it)
    }

    /** 측정 중지 */
    fun stopMeasure() {
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, MeasureService::class.java))
    }
}
