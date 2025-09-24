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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataVizScreen(
    deviceId: String, // 기존 파라미터는 유지
    vm: DataBizViewModel = viewModel(),
    uid: String? = null,
) {
    // (필수) 날짜/다이얼로그 상태가 없다면 이렇게 선언
    var showPicker by remember { mutableStateOf(false) }            // 이미 있으면 삭제 X
    var selectedDate by remember { mutableStateOf(LocalDate.now()) } // 이미 있으면 삭제 X
    // ✅ 여기서 **반드시 uid**를 꺼내 쓴다
    val uid = remember { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() }

    // uid 결정: 넘겨주면 그걸, 없으면 FirebaseAuth에서 읽기
    val effectiveUid = uid ?: FirebaseAuth.getInstance().currentUser?.uid

    if (effectiveUid.isNullOrEmpty()) {
        // 로그인 안 된 상태: 안전하게 가드
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("로그인이 필요합니다")
            // 필요하면 로그인 화면으로 이동하는 버튼 넣기
        }
        return
    }


    if (uid.isBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("로그인이 필요합니다.")
        }
        return
    }


    // 날짜 피커 on/off 상태는 기존 showPicker 사용
    val seoul = ZoneId.of("Asia/Seoul")
    val df = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd (E)") }

    val dayMetrics by vm.dayMetrics.collectAsState()

    // selectedDate는 기존에 쓰던 LocalDate 상태를 그대로 사용한다고 가정
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atStartOfDay(seoul).toInstant().toEpochMilli()
    )
    val isLoading by vm.isLoading.collectAsState()
    val fullCount by vm.lastFullCount.collectAsState()

    LaunchedEffect(effectiveUid, selectedDate) {
        effectiveUid?.let {
            vm.refreshDaySummaryPaged(
                uid = it,
                date = selectedDate,
                zone = seoul,
                pageSize = 3000L,
                force = true          // 최신 요청이 이전 작업을 취소하고 바로 실행
            )
        }
    }


    // --- 상단 로딩바는 그대로 ---
    if (isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    if (fullCount > 0 && !isLoading) {
        Text("총 ${fullCount}개 기준", style = MaterialTheme.typography.bodyMedium)
    }




    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        selectedDate = Instant.ofEpochMilli(millis).atZone(seoul).toLocalDate()
                    }
                    showPicker = false
                }) { Text("선택") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

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
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis).atZone(seoul).toLocalDate()
                        }
                        showPicker = false
                    }) { Text("선택") }
                },
                dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } }
            ) { DatePicker(state = pickerState) }
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