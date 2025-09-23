// app/src/main/java/com/example/heartsync/ui/screens/DataVizScreen.kt
package com.example.heartsync.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.heartsync.ui.DataBizViewModel
import com.example.heartsync.ui.model.DayMetrics
import com.example.heartsync.ui.model.MetricStat
import com.google.firebase.auth.FirebaseAuth
import android.util.Log
import androidx.compose.foundation.lazy.LazyColumn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataVizScreen(
    deviceId: String, // 기존 파라미터는 유지
    vm: DataBizViewModel = viewModel(),
) {
    // ✅ 여기서 **반드시 uid**를 꺼내 쓴다
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }


    if (uid.isBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("로그인이 필요합니다.")
        }
        return
    }

    val seoul = remember { ZoneId.of("Asia/Seoul") }
    var selectedDate by remember { mutableStateOf(LocalDate.now(seoul)) }
    val df = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd (E)") }

    val dayMetrics by vm.dayMetrics.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    // ✅ 디버그: 현재 선택 날짜/uid 확인
    LaunchedEffect(uid, selectedDate) {
        Log.d("DataViz", "ENTER screen, uid=$uid, date=$selectedDate")
        Log.d("DataViz", "startListenDayMetrics uid=$uid date=${selectedDate} prefix=S_${selectedDate.format(DateTimeFormatter.BASIC_ISO_DATE)}")
        vm.startListenDayMetrics(
            uid = uid,   // ← 여기 중요! uid로 전달
            date = selectedDate,
            ampField = "AmpRatio",
            padField = "PAD_ms",
            dSutField = "dSUT_ms\n"
        )
    }
    DisposableEffect(Unit) { onDispose { vm.stopAll() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("날짜별 통계", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { showPicker = true }) { Text(selectedDate.format(df)) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedDate = selectedDate.minusDays(1) }, modifier = Modifier.weight(1f)) { Text("이전 날") }
            OutlinedButton(onClick = { selectedDate = selectedDate.plusDays(1) },  modifier = Modifier.weight(1f)) { Text("다음 날") }
        }

        if (showPicker) {
            DatePickerDialog(onDismissRequest = { showPicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        vm.datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis).atZone(seoul).toLocalDate()
                        }
                        showPicker = false
                    }) { Text("선택") }
                },
                dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } }
            ) { DatePicker(state = vm.datePickerState) }
        }

        Spacer(Modifier.height(16.dp))
        MetricsRow(dayMetrics)
        Spacer(Modifier.height(8.dp))
        AssistChipRow(dayMetrics)
    }



}



@Composable
private fun MetricsRow(metrics: DayMetrics?) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        item { StatCard("AmpRatio", metrics?.ampRatio) }
        item { StatCard("PAD",      metrics?.padMs) }
        item { StatCard("dSUT",     metrics?.dSutMs) }
    }
}
@Composable
private fun StatCard(title: String, m: MetricStat?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (m == null || m.count == 0) {
                Text("데이터 없음", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("평균: ${m.avg.format(2)}")
                Text("최고: ${m.max.format(2)}")
                Text("최저: ${m.min.format(2)}")
            }
        }
    }
}

@Composable
private fun AssistChipRow(metrics: DayMetrics?) {
    val totalN = metrics?.ampRatio?.count ?: 0
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AssistChip(
            onClick = {},
            label = { Text("총 표본 수: $totalN") }
        )
    }
}

private fun Double.format(digits: Int): String =
    "%.${digits}f".format(this)
