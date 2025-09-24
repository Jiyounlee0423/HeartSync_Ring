// app/src/main/java/com/example/heartsync/ui/screens/NotiLogViewModel.kt
package com.example.heartsync.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.ui.screens.model.NotiLogRow
import com.example.heartsync.ui.screens.model.NotiLogSection
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NotiLogViewModel(
    private val repo: NotiLogRepository,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    companion object {
        // Firestore 실데이터 사용 시 false
        const val USE_MOCK: Boolean = false
    }

    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val _rows = MutableStateFlow<List<NotiLogRow>>(emptyList())

    val sections: StateFlow<List<NotiLogSection>> =
        _rows.map { rows ->
            rows
                .groupBy { it.localDate(zone).format(df) }
                .toSortedMap(compareByDescending { it }) // 날짜 최신순
                .map { (date, rs) ->
                    NotiLogSection(
                        date = date,
                        rows = rs.sortedByDescending { it.instantOrNull() } // 같은 날 안에서 최신순
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (USE_MOCK) {
            // 필요하면 네가 쓰던 목 데이터 넣기
        } else {
            viewModelScope.launch {
                repo.observeAlertRows(limit = 500).collect { list ->
                    _rows.value = list
                }
            }
        }
    }
}