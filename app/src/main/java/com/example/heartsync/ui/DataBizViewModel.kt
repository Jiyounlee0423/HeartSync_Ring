// app/src/main/java/com/example/heartsync/ui/DataBizViewModel.kt
package com.example.heartsync.ui

import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.heartsync.data.remote.PpgRepository
import com.example.heartsync.ui.model.DayMetrics
import com.example.heartsync.ui.model.MetricStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
class DataBizViewModel(
    private val repo: PpgRepository = PpgRepository.default()
) : ViewModel() {

    val datePickerState = DatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        locale = Locale.getDefault()
    )
    val debugStatus = MutableStateFlow("idle")

    private val _dayMetrics = MutableStateFlow<DayMetrics?>(null)
    val dayMetrics: StateFlow<DayMetrics?> = _dayMetrics.asStateFlow()

    private var metricsStopper: (() -> Unit)? = null

    fun startListenDayMetrics(
        uid: String,                               // ← 이름만 uid로
        date: LocalDate,
        ampField: String,
        padField: String,
        dSutField: String,
        zone: ZoneId = ZoneId.of("Asia/Seoul")
    ) {
        metricsStopper?.invoke()
        metricsStopper = null

        val job = viewModelScope.launch {
            Log.d("DataViz", "observeDayRecords() call uid=$uid date=$date")
            repo.observeDayRecords(uid, date, zone).collectLatest { records ->
                Log.d("DataViz", "observeDayRecords() got ${records.size} docs")

                fun getNum(rec: Map<String, Any?>, vararg keys: String): Double? {
                    for (k in keys) (rec[k] as? Number)?.toDouble()?.let { return it }
                    return null
                }

                val amps = mutableListOf<Double>()
                val pads = mutableListOf<Double>()
                val dSuts = mutableListOf<Double>()

                for (rec in records) {
                    getNum(rec, ampField, "AmpRatio")?.let { amps += it }
                    getNum(rec, padField, "PAD_ms", "PAD")?.let { pads += it }
                    getNum(rec, dSutField, "dSUT_ms", "dSUT")?.let { dSuts += it }
                }

                _dayMetrics.value = DayMetrics(
                    ampRatio = amps.toMetricStat(),
                    padMs    = pads.toMetricStat(),
                    dSutMs   = dSuts.toMetricStat()
                )
            }
        }
        metricsStopper = { job.cancel() }
    }

    fun stopAll() {
        metricsStopper?.invoke()
        metricsStopper = null
    }
}

private fun List<Double>.toMetricStat(): MetricStat {
    if (isEmpty()) return MetricStat(count = 0, avg = 0.0, min = 0.0, max = 0.0)
    val avg = this.sum() / this.size
    val min = this.minOrNull() ?: 0.0
    val max = this.maxOrNull() ?: 0.0
    return MetricStat(count = this.size, avg = avg, min = min, max = max)
}
