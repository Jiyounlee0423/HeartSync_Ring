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

    fun setDate(date: LocalDate) {
        if (date != _selectedDate.value) {
            _selectedDate.value = date
            reload()
        }
    }

    fun reload() {
        viewModelScope.launch {
            _loading.value = true
            var firstEmission = true
            repo.observeAlertsByDate(_selectedDate.value)
                .onEach { list ->
                    _rows.value = list
                    if (firstEmission) {
                        firstEmission = false
                        _loading.value = false   // ← 첫 스냅샷(빈 목록이어도) 받으면 로딩 종료
                    }
                }
                .catch {
                    _rows.value = emptyList()
                    _loading.value = false
                }
                .collect()   // 실시간 수집 유지
        }
    }
}
