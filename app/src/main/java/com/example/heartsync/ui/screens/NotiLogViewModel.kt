package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.NotiLogRepository
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.example.heartsync.ui.screens.model.localDate
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach

class NotiLogViewModel(
    private val repo: NotiLogRepository,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
) : ViewModel() {

    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)")

    private val _selectedDate = MutableStateFlow(LocalDate.now(zone))
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _rows = MutableStateFlow<List<NotiLogRow>>(emptyList())
    val rows: StateFlow<List<NotiLogRow>> = _rows.asStateFlow()

    val headerText: StateFlow<String> =
        combine(selectedDate, rows) { d, list ->
            "${d.format(df)} • ALERT ${list.size}건"
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init { reload() }

    private var collectJob: Job? = null

    fun setDate(date: LocalDate) {
        if (date != _selectedDate.value) {
            _selectedDate.value = date
            reload()
        }
    }

    fun reload() {
        // ✅ 이전 수집 취소 + 화면 즉시 초기화
        collectJob?.cancel()
        _rows.value = emptyList()
        _loading.value = true

        collectJob = viewModelScope.launch {
            var first = true
            repo.observeAlertsByDate(_selectedDate.value)
                .onEach { list ->
                    _rows.value = list          // 빈 리스트여도 바로 반영
                    if (first) {
                        first = false
                        _loading.value = false   // 첫 스냅샷(빈/유효 모두)에서 로딩 종료
                    }
                }
                .catch {
                    _rows.value = emptyList()
                    _loading.value = false
                }
                .collect()
        }
    }

}
