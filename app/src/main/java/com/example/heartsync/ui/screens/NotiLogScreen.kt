package com.example.heartsync.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.example.heartsync.ui.screens.model.NotiLogRow
import com.example.heartsync.ui.screens.model.localTimeStr
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen() {
    val vm: NotiLogViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = notiLogViewModelFactory()
    )
    NotiLogScreen(vm = vm)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotiLogScreen(vm: NotiLogViewModel) {
    val rows    by vm.rows.collectAsState()
    val header  by vm.headerText.collectAsState()
    val loading by vm.loading.collectAsState()
    val selDate by vm.selectedDate.collectAsState()

    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // ✅ SmallTopAppBar 대신 CenterAlignedTopAppBar 사용
            CenterAlignedTopAppBar(
                title = { Text("이상 로그 알림") },
                actions = {
                    TextButton(onClick = { showPicker = true }) {
                        Text(selDate.format(DateTimeFormatter.ISO_DATE))
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner)) {
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Text(
                text = header,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("시각",    Modifier.weight(0.9f), style = MaterialTheme.typography.labelLarge)
                Text("side",    Modifier.weight(0.8f), style = MaterialTheme.typography.labelLarge)
                Text("reasons", Modifier.weight(2.2f), style = MaterialTheme.typography.labelLarge)
                Text("AmpRatio",Modifier.weight(1.1f), style = MaterialTheme.typography.labelLarge)
                Text("PAD(ms)", Modifier.weight(1.0f), style = MaterialTheme.typography.labelLarge)
                Text("dSUT(ms)",Modifier.weight(1.1f), style = MaterialTheme.typography.labelLarge)
            }

            // ✅ Divider → HorizontalDivider
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            r.localTimeStr(ZoneId.of("Asia/Seoul")),
                            Modifier.weight(0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(r.side ?: "-", Modifier.weight(0.8f), maxLines = 1)
                        Text(
                            r.reasons?.joinToString() ?: "-",
                            Modifier.weight(2.2f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // ✅ 카멜케이스 필드명
                        Text(r.ampRatio?.let { "%.2f".format(it) } ?: "-", Modifier.weight(1.1f))
                        Text(r.padMs?.let { "%.0f".format(it) } ?: "-",  Modifier.weight(1.0f))
                        Text(r.dSutMs?.let { "%.0f".format(it) } ?: "-", Modifier.weight(1.1f))
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showPicker) {
        val zone = ZoneId.systemDefault()
        val initMillis = selDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initMillis)

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = dateState.selectedDateMillis
                        if (millis != null) {
                            val picked = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                            vm.setDate(picked)
                        }
                        showPicker = false
                    }
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("취소") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }
}

/**
 * 간단한 커스텀 DatePicker 대용(Compose Material3 기본 DatePickerState 버전차 회피용)
 */
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    onPicked: (LocalDate) -> Unit
) {
    var year by remember { mutableStateOf(initial.year) }
    var month by remember { mutableStateOf(initial.monthValue) }
    var day by remember { mutableStateOf(initial.dayOfMonth) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("날짜 선택", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = year.toString(),
            onValueChange = { it.toIntOrNull()?.let { y -> if (y in 2000..2100) year = y } },
            label = { Text("연(YYYY)") }
        )
        OutlinedTextField(
            value = month.toString(),
            onValueChange = { it.toIntOrNull()?.let { m -> if (m in 1..12) month = m } },
            label = { Text("월(MM)") }
        )
        OutlinedTextField(
            value = day.toString(),
            onValueChange = { it.toIntOrNull()?.let { d -> if (d in 1..31) day = d } },
            label = { Text("일(DD)") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = {
                runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let(onPicked)
            }) { Text("확인") }
        }
    }
}
