// app/src/main/java/com/example/heartsync/viewmodel/BleViewModel.kt
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private var scanJob: Job? = null
    private var connJob: Job? = null

    private val MAX_GRAPH_POINTS = 512

    init {
        PpgRepository.smoothedFlow            // ★ companion 쪽 Flow
            .onEach { (l, r) ->
                android.util.Log.d("BleVM", "flow L=$l R=$r")
                _graphState.update { p ->
                    p.copy(
                        smoothedL = (p.smoothedL + l).takeLast(MAX_GRAPH_POINTS),
                        smoothedR = (p.smoothedR + r).takeLast(MAX_GRAPH_POINTS)
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * 2) Firestore에서 최근 N개를 그래프로 받고 싶을 때 Activity/Fragment에서
     *    uid, sessionId가 준비된 시점에 한 번 호출해줘.
     */
    fun startFirestoreGraph(uid: String, sessionId: String, limit: Long = 512L) {
        viewModelScope.launch {
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
                .catch { e -> android.util.Log.e("BleVM", "observe error", e) }
                .collect{}
        }
    }




    fun startScan() {
        if (_scanning.value) return
        _scanning.value = true

        // 실제 스캔 시작
        client.startScan()

        // 기존 수집 중지 후 재시작
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
        // 스캔 중이면 중지
        stopScan()
        // 연결 요청
        client.connect(device)

        // 연결 상태 수집 (중복 수집 방지)
        connJob?.cancel()
        connJob = client.connectionState
            .onEach { state -> _connectionState.value = state }
            .launchIn(viewModelScope)
    }

    fun disconnect() {
        client.disconnect()
        _connectionState.value = PpgBleClient.ConnectionState.Disconnected
        // 필요시 수집 중지도 함께
        connJob?.cancel()
        clearGraph()
    }

    fun clearGraph() {
        _graphState.value = GraphState()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        connJob?.cancel()
        client.stopScan()
        client.disconnect()
    }

    /** 연결된 기기로 측정(MeasureService) 시작 */
    fun startMeasure(device: BleDevice) {
        val ctx = getApplication<Application>()
        val it = Intent(ctx, MeasureService::class.java).apply {
            putExtra(MeasureService.EXTRA_DEVICE_NAME, device.name)
            putExtra(MeasureService.EXTRA_DEVICE_ADDR, device.address)
        }
        // API 26+ 대응
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
