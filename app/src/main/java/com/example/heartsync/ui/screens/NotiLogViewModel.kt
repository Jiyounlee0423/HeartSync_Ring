// app/src/main/java/com/example/heartsync/ui/screens/NotiLogViewModel.kt
package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.NotiLogRepository
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.example.heartsync.ui.screens.model.NotiLogSection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotiLogViewModel(
    private val repo: NotiLogRepository,
    private val uid: String, // ★ 현재 로그인 uid 주입
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    companion object { const val USE_MOCK = false }

    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val _rows = MutableStateFlow<List<NotiLogRow>>(emptyList())
    private val _date = MutableStateFlow(LocalDate.now())

    val selectedDate: StateFlow<LocalDate> = _date

    val sections: StateFlow<List<NotiLogSection>> =
        _rows.map { rows ->
            rows.groupBy { it.localDate(zone).format(df) }
                .toSortedMap(compareByDescending { it })
                .map { (date, rs) ->
                    NotiLogSection(date, rs.sortedByDescending { it.instantOrNull() })
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            _date.flatMapLatest { d ->
                if (USE_MOCK) flowOf(emptyList())
                else repo.observeAlertRowsForDate(uid, d)
            }.collect { _rows.value = it }
        }
    }

    fun setDate(d: LocalDate) { _date.value = d }
}
