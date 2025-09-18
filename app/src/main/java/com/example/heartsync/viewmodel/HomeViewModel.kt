// app/src/main/java/com/example/heartsync/viewmodel/HomeViewModel.kt
package com.example.heartsync.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.remote.PpgPoint
import com.example.heartsync.data.remote.PpgRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.ArrayDeque

class HomeViewModel(
    private val repo: PpgRepository
) : ViewModel() {

    // ===== Firestore (오늘 날짜) 데이터 =====
    private val _today = MutableStateFlow<List<PpgPoint>>(emptyList())
    val today: StateFlow<List<PpgPoint>> = _today.asStateFlow()

    // ===== BLE 실시간 데이터 (메모리 버퍼) =====
    private val _live = MutableStateFlow<List<PpgPoint>>(emptyList())
    val live: StateFlow<List<PpgPoint>> = _live.asStateFlow()

    // 로그인 여부
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * 화면에 뿌릴 최종 시리즈
     * - live가 비어있지 않으면 live 우선
     * - 아니면 today 사용
     */
    val display: StateFlow<List<PpgPoint>> =
        combine(today, live) { day, livePoints ->
            if (livePoints.isNotEmpty()) livePoints else day
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ---- 실시간 버퍼 설정(최근 N개만 유지) ----
    private val liveBuffer = ArrayDeque<PpgPoint>()
    private val LIVE_WINDOW = 600  // 필요 시 300/600/1000 등으로 조절

    init {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        _isLoggedIn.value = uid != null

        // 1) 오늘 날짜의 Firestore 데이터 구독 (기존 기록 + 백필)
        if (uid != null) {
            viewModelScope.launch {
                repo.observeDayPpg(uid, LocalDate.now())
                    .collectLatest { _today.value = it }
            }
        }

        // 2) BLE → smoothedFlow 실시간 스트림 구독 (Firestore 저장과 무관, 즉시 반영)
        startLiveStream()
    }

    /** BLE에서 방금 받은 smoothed L/R 값을 바로 그래프에 반영하는 스트림 */
    private fun startLiveStream() {
        viewModelScope.launch {
            PpgRepository.smoothedFlow.collect { (l, r) ->
                val now = System.currentTimeMillis()

                // 버퍼에 최신 포인트 추가 (윈도우 초과 시 가장 오래된 것 제거)
                if (liveBuffer.size >= LIVE_WINDOW) liveBuffer.removeFirst()
                liveBuffer.addLast(
                    PpgPoint(
                        ts = now,                 // x축(절대시간)으로 사용
                        left = l.toDouble(),
                        right = r.toDouble(),
                        serverTs = now            // 선택: 동일값 넣어도 무방
                    )
                )

                // UI 반영
                _live.value = liveBuffer.toList()
            }
        }
    }
}

class HomeVmFactory(
    private val repo: PpgRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repo) as T
    }
}
